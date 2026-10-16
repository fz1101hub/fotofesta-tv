import FotoFestaCore
import Foundation
import Network
import Observation
import Security
import UIKit

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

    /// Chiamato dopo ogni cambio di foto o dello stato della festa.
    @ObservationIgnored var onChange: (() -> Void)?

    let store = GuestStorage.store
    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var server: GuestUploadServer?
    @ObservationIgnored private var serverHost: String?
    @ObservationIgnored private var monitor: NWPathMonitor?
    @ObservationIgnored private var foreground = false
    @ObservationIgnored private var serving: Task<Void, Never>?
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
    }

    init() {
        let defaults = UserDefaults.standard
        active = defaults.bool(forKey: Keys.active)
        approval = defaults.bool(forKey: Keys.approval)
        qrInSlideshow = defaults.object(forKey: Keys.qrInSlideshow) as? Bool ?? true
        template = Bundle.main.url(forResource: "index", withExtension: "html")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        approvalFlag.value = defaults.bool(forKey: Keys.approval)
        if let ssid = defaults.string(forKey: Keys.wifiSSID) {
            wifi = WifiNetwork(ssid: ssid, password: WifiPasswordKeychain.read() ?? "")
        } else {
            // Password rimasta da un'installazione precedente: senza nome della rete non serve.
            WifiPasswordKeychain.write(nil)
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
            guard wasActive else {
                store.clear()
                return
            }
            store.restore(maxAge: idleLimit)
            let newest = store.photos.map(\.receivedAt.timeIntervalSince1970).max() ?? 0
            if Date().timeIntervalSince1970 - max(startedAt, newest) > idleLimit {
                await self?.end()
            }
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
        onChange?()
    }

    func setApproval(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.approval)
        approval = enabled
        approvalFlag.value = enabled
        // Senza approvazione le foto in attesa non avrebbero più modo di comparire.
        if !enabled { approveAll() }
    }

    func setQRInSlideshow(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.qrInSlideshow)
        qrInSlideshow = enabled
    }

    func saveWifi(_ network: WifiNetwork?) {
        WifiPasswordKeychain.write(network?.password)
        defaults.set(network?.ssid, forKey: Keys.wifiSSID)
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

    /// Avvia il server sull'indirizzo attuale, o lo sposta se la rete è cambiata.
    private func refresh() {
        guard active, foreground else { return }
        startMonitor()
        let previous = serving
        serving = Task { [weak self] in
            await previous?.value
            await self?.serve()
        }
    }

    private func serve() async {
        guard active, foreground else { return }
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
        let candidate = GuestUploadServer(
            key: secret,
            page: makePageProvider(),
            onPhoto: makePhotoHandler()
        )
        do {
            let port = try await candidate.start(host: host, preferredPort: Self.preferredPort)
            // Nel frattempo l'app è andata in background o la festa è finita.
            guard active, foreground else {
                candidate.stop()
                return
            }
            server = candidate
            serverHost = host
            url = "http://\(GuestUploadServer.hostHeader(host: host, port: port))/?k=\(secret)"
            problem = nil
        } catch {
            candidate.stop()
            url = nil
            problem = .serverFailed
        }
    }

    private func stopServing() {
        serving?.cancel()
        serving = nil
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
                "\"maxBytes\":\(GuestUploadServer.maxUploadBytes),\"approval\":\(flag.value)}"
            return template.replacingOccurrences(of: "__FOTOFESTA_CONFIG__", with: config)
        }
    }

    /// Chiamato dalla coda del server per ogni foto ricevuta.
    private func makePhotoHandler() -> @Sendable (Data, String?) -> GuestUploadServer.UploadResult {
        let store = self.store
        let flag = approvalFlag
        return { data, sender in
            guard GuestImageFormat.isDisplayable(data) else { return .unsupported }
            guard let photo = store.add(data, sender: sender, approved: !flag.value) else { return .full }
            return photo.approved ? .shown : .pending
        }
    }

    /// Risoluzione dello schermo collegato (lato lungo, lato corto), tra Full HD e 4K.
    private static func screenPixels() -> (width: Int, height: Int) {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let size = scene?.screen.nativeBounds.size ?? CGSize(width: 3840, height: 2160)
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

    static func write(_ value: String?) {
        SecItemDelete(query as CFDictionary)
        guard let value else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}
