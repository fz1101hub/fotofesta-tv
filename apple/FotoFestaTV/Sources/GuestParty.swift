import FotoFestaCore
import Foundation
import Network
import Observation
import Security
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Perché la Apple TV non riesce a ricevere le foto degli invitati.
enum GuestProblem: Equatable {
    case noNetwork, serverFailed
}

/// Archivio delle foto della festa, condiviso da modello e caricamento delle immagini.
enum GuestStorage {
    static let store: GuestPhotoStore = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        // 64 MB in RAM bastano per decine di foto 4K già ridotte dal telefono; poi la cartella di
        // riserva, al massimo 300 MB e mai più del 10% dello spazio libero.
        return GuestPhotoStore(
            directory: caches.appendingPathComponent("guest_photos", isDirectory: true),
            memoryBudget: 64 * 1024 * 1024,
            maxDiskBytes: 300 * 1024 * 1024
        )
    }()
}

/// Festa con le foto degli invitati: tiene acceso il server sulla rete di casa mentre l'app è in
/// primo piano, segue i cambi di rete e ricorda le impostazioni.
@MainActor
@Observable
final class GuestParty {
    private(set) var active: Bool
    private(set) var url: String?
    private(set) var problem: GuestProblem?
    private(set) var approval: Bool
    private(set) var qrInSlideshow: Bool
    private(set) var wifi: WifiNetwork?
    /// Tutte le foto ricevute, approvate e da approvare, dalla più vecchia.
    private(set) var photos: [GuestPhoto] = []
    /// Vero quando le foto di una festa interrotta sono state riprese (o cancellate) all'avvio.
    private(set) var ready = false
    /// I telefoni mandano reazioni alla foto sullo schermo.
    private(set) var reactionsEnabled: Bool
    /// I telefoni mandano dediche, mostrate nello slideshow e nel libro degli ospiti.
    private(set) var messagesEnabled: Bool
    /// Dediche in mostra (dalla più vecchia) e da approvare.
    private(set) var messages: [PartyHub.Message] = []
    private(set) var pendingMessages: [PartyHub.Message] = []

    /// Chiamato dopo ogni cambio di foto o dello stato della festa.
    @ObservationIgnored var onChange: (() -> Void)?

    let store = GuestStorage.store
    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var server: GuestUploadServer?
    @ObservationIgnored private var serverHost: String?
    @ObservationIgnored private var monitor: NWPathMonitor?
    @ObservationIgnored private var foreground = false
    @ObservationIgnored private var serving: Task<Void, Never>?
    /// Un solo nuovo tentativo programmato alla volta, dopo un avvio non riuscito.
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private let approvalFlag = LockedFlag()
    /// Pagina per i telefoni (shared/assets/guest/index.html), con i valori da completare.
    @ObservationIgnored private let template: String

    private static let preferredPort: UInt16 = 8765
    /// Dopo 12 ore senza foto la festa si considera finita.
    private static let idleLimit: TimeInterval = 12 * 60 * 60

    private enum Keys {
        static let active = "guests.active"
        static let secret = "guests.secret"
        static let startedAt = "guests.startedAt"
        static let approval = "guests.approval"
        static let qrInSlideshow = "guests.qrInSlideshow"
        static let wifiSSID = "guests.wifiSSID"
        static let wifiHasPassword = "guests.wifiHasPassword"
        static let reactions = "guests.reactions"
        static let messages = "guests.messages"
    }

    /// Gioco con i telefoni, servito dalla stessa pagina della festa.
    let phoneGame = PhoneGame()
    /// Reazioni e dediche dei telefoni: si cancellano con le foto a fine festa.
    let partyHub = PartyHub()
    @ObservationIgnored private var messageRevision = -1
    @ObservationIgnored private var messagePolling: Task<Void, Never>?

    init() {
        let defaults = UserDefaults.standard
        active = defaults.bool(forKey: Keys.active)
        approval = defaults.bool(forKey: Keys.approval)
        qrInSlideshow = defaults.object(forKey: Keys.qrInSlideshow) as? Bool ?? true
        reactionsEnabled = defaults.object(forKey: Keys.reactions) as? Bool ?? true
        messagesEnabled = defaults.object(forKey: Keys.messages) as? Bool ?? true
        template = Bundle.main.url(forResource: "index", withExtension: "html")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        approvalFlag.value = defaults.bool(forKey: Keys.approval)
        if let ssid = defaults.string(forKey: Keys.wifiSSID) {
            let password = WifiPasswordKeychain.read()
            // Senza la password nel Portachiavi una rete protetta sembrerebbe aperta: niente QR.
            if password == nil, defaults.bool(forKey: Keys.wifiHasPassword) {
                wifi = nil
            } else {
                wifi = WifiNetwork(ssid: ssid, password: password ?? "")
            }
        } else {
            // Password rimasta da un'installazione precedente: senza nome della rete non serve.
            _ = WifiPasswordKeychain.write(nil)
        }
        syncPartyFeatures()
        // Le dediche arrivano dalla coda del server: si controlla una volta al secondo se sono cambiate.
        messagePolling = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                self?.refreshMessages()
                try? await Task.sleep(for: .seconds(1))
            }
        }
        let store = self.store
        store.setOnChange { [weak self] _ in
            // Si rilegge l'archivio sul thread principale: i Task possono arrivare in ordine diverso.
            Task { @MainActor [weak self] in self?.photosChanged() }
        }
        let wasActive = defaults.bool(forKey: Keys.active)
        let startedAt = defaults.double(forKey: Keys.startedAt)
        let idleLimit = Self.idleLimit
        Task.detached(priority: .utility) { [weak self] in
            if wasActive {
                store.restore(maxAge: idleLimit)
                let newest = store.photos.map(\.receivedAt.timeIntervalSince1970).max() ?? 0
                if Date().timeIntervalSince1970 - max(startedAt, newest) > idleLimit {
                    await self?.end()
                }
            } else {
                store.clear()
            }
            await self?.markReady()
        }
    }

    // MARK: - Azioni

    /// Inizia a ricevere foto: il QR resta lo stesso finché la festa non finisce.
    func start() {
        guard !active else { return }
        defaults.set(true, forKey: Keys.active)
        defaults.set(Self.newSecret(), forKey: Keys.secret)
        defaults.set(Date().timeIntervalSince1970, forKey: Keys.startedAt)
        active = true
        onChange?()
        if foreground { refresh() }
    }

    /// Fine della festa: il server si ferma e tutte le foto ricevute vengono cancellate.
    func end() {
        stopServing()
        // Subito, non in background: una festa ricominciata un attimo dopo non deve perdere le foto nuove.
        store.clear()
        defaults.set(false, forKey: Keys.active)
        defaults.removeObject(forKey: Keys.secret)
        defaults.removeObject(forKey: Keys.startedAt)
        active = false
        problem = nil
        // Subito, senza aspettare la notifica dell'archivio: la raccolta non deve usare foto cancellate.
        photos = []
        partyHub.clear()
        refreshMessages()
        onChange?()
    }

    func setApproval(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.approval)
        approval = enabled
        approvalFlag.value = enabled
        // Senza approvazione le foto in attesa non avrebbero più modo di comparire.
        if !enabled { approveAll() }
        syncPartyFeatures()
        refreshMessages()
    }

    func setReactionsEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.reactions)
        reactionsEnabled = enabled
        syncPartyFeatures()
    }

    func setMessagesEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.messages)
        messagesEnabled = enabled
        syncPartyFeatures()
    }

    func approveMessage(_ message: PartyHub.Message) {
        partyHub.approveMessage(message.id)
        refreshMessages()
    }

    func discardMessage(_ message: PartyHub.Message) {
        partyHub.removeMessage(message.id)
        refreshMessages()
    }

    func approveAllMessages() {
        partyHub.approveAllMessages()
        refreshMessages()
    }

    /// Con l'approvazione delle foto anche le dediche aspettano il via libera.
    private func syncPartyFeatures() {
        partyHub.setFeatures(PartyHub.Features(reactions: reactionsEnabled, messages: messagesEnabled, moderateMessages: approval))
    }

    private func refreshMessages() {
        let revision = partyHub.currentRevision()
        guard revision != messageRevision else { return }
        messageRevision = revision
        messages = partyHub.approvedMessages()
        pendingMessages = partyHub.pendingMessages()
    }

    func setQRInSlideshow(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.qrInSlideshow)
        qrInSlideshow = enabled
    }

    func saveWifi(_ network: WifiNetwork?) {
        // Per una rete aperta non c'è niente da mettere nel Portachiavi.
        let saved = WifiPasswordKeychain.write(network.flatMap { $0.isOpen ? nil : $0.password })
        if let network, saved {
            defaults.set(network.ssid, forKey: Keys.wifiSSID)
            defaults.set(!network.isOpen, forKey: Keys.wifiHasPassword)
        } else {
            // Portachiavi non disponibile: la rete vale solo finché l'app resta aperta, e non resta
            // salvato un nome senza password che al riavvio sembrerebbe una rete aperta.
            defaults.removeObject(forKey: Keys.wifiSSID)
            defaults.removeObject(forKey: Keys.wifiHasPassword)
        }
        wifi = network
    }

    func approve(_ id: String) {
        let store = self.store
        Task.detached(priority: .userInitiated) { store.approve(id) }
    }

    func approveAll() {
        let store = self.store
        Task.detached(priority: .userInitiated) {
            for photo in store.photos where !photo.approved { store.approve(photo.id) }
        }
    }

    func reject(_ id: String) {
        let store = self.store
        Task.detached(priority: .userInitiated) { store.remove(id) }
    }

    /// Primo piano: server acceso se la festa è in corso. In background il server si ferma e le foto
    /// passano su disco, perché il sistema potrebbe chiudere l'app.
    func setForeground(_ isForeground: Bool) {
        guard isForeground != foreground else { return }
        foreground = isForeground
        if isForeground {
            if active { refresh() }
        } else {
            stopServing()
            let store = self.store
            Task.detached(priority: .utility) { store.spillAll() }
        }
    }

    func lowMemory() {
        let store = self.store
        Task.detached(priority: .utility) { store.spillAll() }
    }

    // MARK: - Server

    private func photosChanged() {
        photos = store.photos
        onChange?()
    }

    private func markReady() {
        photos = store.photos
        ready = true
        onChange?()
        // Il server parte solo ora: una foto ricevuta durante la ripresa andrebbe persa.
        if active, foreground { refresh() }
    }

    /// Il server si è fermato da solo (ad esempio la rete è caduta): si riavvia.
    private func serverFailed(_ failed: GuestUploadServer) {
        guard failed === server else { return }
        failed.stop()
        server = nil
        serverHost = nil
        refresh()
    }

    /// Avvia il server sull'indirizzo attuale, o lo sposta se la rete è cambiata.
    private func refresh() {
        guard active, foreground, ready else { return }
        startMonitor()
        let previous = serving
        serving = Task { [weak self] in
            await previous?.value
            await self?.serve()
        }
    }

    private func serve() async {
        guard !Task.isCancelled, active, foreground else { return }
        guard let host = LocalNetwork.currentIPv4() else {
            server?.stop()
            server = nil
            serverHost = nil
            url = nil
            problem = .noNetwork
            return
        }
        if server != nil, host == serverHost { return }
        server?.stop()
        server = nil
        let secret = defaults.string(forKey: Keys.secret) ?? {
            let value = Self.newSecret()
            defaults.set(value, forKey: Keys.secret)
            return value
        }()
        let failure = FailureRelay()
        let candidate = GuestUploadServer(
            key: secret,
            page: makePageProvider(),
            onPhoto: makePhotoHandler(),
            onFailure: { [weak self] in
                Task { @MainActor [weak self] in
                    if let server = failure.server { self?.serverFailed(server) }
                }
            },
            game: phoneGame,
            hub: partyHub
        )
        failure.server = candidate
        do {
            let port = try await candidate.start(host: host, preferredPort: Self.preferredPort)
            // Nel frattempo l'app è andata in background, la festa è finita o è partito un altro avvio.
            guard !Task.isCancelled, active, foreground else {
                candidate.stop()
                return
            }
            server = candidate
            serverHost = host
            url = "http://\(GuestUploadServer.hostHeader(host: host, port: port))/?k=\(secret)"
            problem = nil
        } catch {
            candidate.stop()
            guard !Task.isCancelled, active, foreground else { return }
            url = nil
            problem = .serverFailed
            // Nuovo tentativo tra qualche secondo, se la festa è ancora in primo piano.
            retryTask?.cancel()
            retryTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard !Task.isCancelled else { return }
                self?.refresh()
            }
        }
    }

    private func stopServing() {
        // Il tentativo annullato resta il "precedente": il prossimo avvio aspetta che finisca.
        serving?.cancel()
        retryTask?.cancel()
        retryTask = nil
        monitor?.cancel()
        monitor = nil
        server?.stop()
        server = nil
        serverHost = nil
        url = nil
        problem = nil
    }

    private func startMonitor() {
        guard monitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] _ in
            Task { @MainActor [weak self] in self?.refresh() }
        }
        monitor.start(queue: DispatchQueue(label: "it.fotofesta.guest-network"))
        self.monitor = monitor
    }

    /// Pagina per i telefoni con la misura dello schermo e le impostazioni attuali.
    private func makePageProvider() -> @Sendable () -> String {
        let template = self.template
        let flag = approvalFlag
        let screen = Self.screenPixels()
        return {
            let fit = AppModel.storedSettings().fitMode == .crop ? "cover" : "contain"
            // Un 10% in più lascia spazio allo zoom lento dello slideshow senza perdere nitidezza.
            let width = Int((Double(screen.width) * 1.1).rounded())
            let height = Int((Double(screen.height) * 1.1).rounded())
            let config = "{\"maxWidth\":\(width),\"maxHeight\":\(height),\"fit\":\"\(fit)\",\"quality\":0.88," +
                "\"maxBytes\":\(GuestUploadServer.maxUploadBytes),\"maxPixels\":\(GuestImageFormat.maxImagePixels),\"maxSide\":\(GuestImageFormat.maxImageSide)," +
                "\"approval\":\(flag.value)}"
            return template.replacingOccurrences(of: "__FOTOFESTA_CONFIG__", with: config)
        }
    }

    /// Chiamato dalla coda del server per ogni foto ricevuta.
    private func makePhotoHandler() -> @Sendable (Data, String?) -> GuestUploadServer.UploadResult {
        let store = self.store
        let flag = approvalFlag
        return { data, sender in
            guard GuestImageFormat.isDisplayable(data) else { return .unsupported }
            switch store.add(data, sender: sender, approved: !flag.value) {
            case let .added(photo): return photo.approved ? .shown : .pending
            case .full: return .full
            case .tooManyPending: return .tooManyPending
            }
        }
    }

    /// Risoluzione dello schermo collegato (lato lungo, lato corto), tra Full HD e 4K.
    private static func screenPixels() -> (width: Int, height: Int) {
        let size = screenPixelSize() ?? CGSize(width: 3840, height: 2160)
        let long = Int(max(size.width, size.height))
        let short = Int(min(size.width, size.height))
        return (min(max(long, 1280), 3840), min(max(short, 720), 2160))
    }

    private static func newSecret() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        if SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) != errSecSuccess {
            var generator = SystemRandomNumberGenerator()
            bytes = bytes.map { _ in UInt8.random(in: 0...255, using: &generator) }
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// Server a cui si riferisce la notifica di errore, impostato subito dopo la creazione.
private final class FailureRelay: @unchecked Sendable {
    weak var server: GuestUploadServer?
}

/// Valore booleano letto dalla coda del server e scritto dal thread principale.
final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = false

    var value: Bool {
        get {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
        set {
            lock.lock()
            stored = newValue
            lock.unlock()
        }
    }
}

/// Password della Wi‑Fi per il QR degli invitati, nel Portachiavi e solo su questo dispositivo.
enum WifiPasswordKeychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "it.fotofesta.tv.guests",
        kSecAttrAccount as String: "wifiPassword",
    ]

    static func read() -> String? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Salva (o con nil cancella) la password; false se il Portachiavi non l'ha accettata.
    @discardableResult
    static func write(_ value: String?) -> Bool {
        SecItemDelete(query as CFDictionary)
        guard let value else { return true }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        #if os(tvOS)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        #endif
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }
}
