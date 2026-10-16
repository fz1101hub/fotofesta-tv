import FotoFestaCore
import Foundation
import Observation

/// Chiave delle impostazioni in UserDefaults, fuori dalla classe: la legge anche la coda del server
/// degli invitati.
private let slideshowSettingsKey = "slideshowSettings"

enum Route: Hashable {
    case slideshow, settings, games, memory, mystery, googleConnect, guests, guestReview, guestWifi
}

enum GoogleState: Equatable {
    case idle
    case working(ImportStep?)
    case failed(String)
    /// `expiresAt`: fino a quando Google rende disponibili le foto scelte, se noto.
    case complete(Int, expiresAt: Date?)
}

/// Stato dell'app Apple TV: raccolta attiva, impostazioni, riproduzione e collegamento Google Foto.
@MainActor
@Observable
final class AppModel {
    var path: [Route] = []
    /// Raccolta mostrata: quella scelta (demo o Google) più le foto approvate degli invitati.
    private(set) var photos: [Photo]
    private(set) var sourceLabel: String
    private(set) var currentIndex = 0
    private(set) var isPlaying = false
    private(set) var googleState: GoogleState = .idle
    private(set) var sleepAt: Date?
    private(set) var message: String?
    private(set) var settings: SlideshowSettings

    static let demoPhotos: [Photo] = [
        Photo(id: "demo:coast", title: L("demo.coast"), source: .bundled(fileName: "demo_coast.jpg")),
        Photo(id: "demo:picnic", title: L("demo.picnic"), source: .bundled(fileName: "demo_picnic.jpg")),
        Photo(id: "demo:mountains", title: L("demo.mountains"), source: .bundled(fileName: "demo_mountains.jpg")),
        Photo(id: "demo:birthday", title: L("demo.birthday"), source: .bundled(fileName: "demo_birthday.jpg")),
    ]

    private let defaults = UserDefaults.standard
    private let order = PlaybackOrder()
    private let google = GoogleServices.shared
    /// Festa con le foto degli invitati.
    let guests = GuestParty()
    @ObservationIgnored private var basePhotos: [Photo]
    @ObservationIgnored private var baseLabel: String
    @ObservationIgnored private var baseIsDemo: Bool
    @ObservationIgnored private var guestPhotos: [Photo] = []
    @ObservationIgnored private var guestPartyActive = false
    @ObservationIgnored private let freshPhotos = FreshPhotos()
    @ObservationIgnored private var knownGuestIDs: Set<String>?
    @ObservationIgnored private var knownPendingIDs: Set<String> = []
    @ObservationIgnored private let createdAt = Date()
    @ObservationIgnored private var googleTask: Task<Void, Never>?
    @ObservationIgnored private var cleanupTask: Task<Void, Never>?
    /// Identifica il collegamento in corso: i passi di un collegamento annullato vengono ignorati.
    @ObservationIgnored private var connectionID = UUID()
    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var sleepTaskMinutes = 0
    @ObservationIgnored private var messageTask: Task<Void, Never>?

    private static let settingsKey = slideshowSettingsKey
    private static let sourceKey = "lastSource"

    init() {
        settings = Self.storedSettings()
        var initialPhotos = Self.demoPhotos
        var initialLabel = L("source.demo")
        var initialMessage: String?
        let savedSelection = GoogleServices.shared.selections.current()
        if UserDefaults.standard.string(forKey: Self.sourceKey) == "google" {
            // La scadenza la verifica Google al primo caricamento: l'orologio del dispositivo può
            // essere sbagliato all'accensione.
            if let savedSelection {
                initialPhotos = savedSelection.photos
                initialLabel = L("source.google", savedSelection.items.count)
            } else {
                // Selezione persa, o salvata da una versione che scaricava le foto.
                UserDefaults.standard.set("demo", forKey: Self.sourceKey)
                initialMessage = L("error.google_selection_gone")
            }
        }
        photos = initialPhotos
        sourceLabel = initialLabel
        basePhotos = initialPhotos
        baseLabel = initialLabel
        baseIsDemo = initialPhotos == Self.demoPhotos
        order.reset(count: initialPhotos.count, shuffle: settings.shuffle)
        if let initialMessage { showMessage(initialMessage) }
        // Selezione rimasta da una sorgente abbandonata: non verrà più usata.
        if savedSelection != nil, UserDefaults.standard.string(forKey: Self.sourceKey) != "google" {
            discardGoogleSelection()
        }
        // Le versioni precedenti scaricavano le foto Google in Caches: ora si caricano da Google.
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        Task.detached(priority: .utility) { PhotosPicker.deleteLegacyDownloads(in: caches) }
        Task { [weak self, google] in
            await google.stream.setOnSelectionGone { [weak self] sessionID, reason in
                Task { @MainActor [weak self] in self?.handleSelectionGone(sessionID: sessionID, reason: reason) }
            }
            // Una selezione persa prima che il gestore fosse registrato: si allinea subito la schermata.
            self?.reconcileLostSelection()
        }
        guests.onChange = { [weak self] in self?.guestsChanged() }
        guestsChanged()
    }

    /// Impostazioni salvate; usate anche dalla coda del server degli invitati (adattamento delle foto).
    nonisolated static func storedSettings() -> SlideshowSettings {
        UserDefaults.standard.data(forKey: slideshowSettingsKey)
            .flatMap { try? JSONDecoder().decode(SlideshowSettings.self, from: $0) } ?? SlideshowSettings()
    }

    /// Modifica e salva le impostazioni, aggiornando ordine e pausa automatica se necessario.
    func updateSettings(_ change: (inout SlideshowSettings) -> Void) {
        let old = settings
        change(&settings)
        guard settings != old else { return }
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: Self.settingsKey) }
        if old.shuffle != settings.shuffle { order.reset(count: photos.count, shuffle: settings.shuffle, current: currentIndex) }
        if old.sleepTimerMinutes != settings.sleepTimerMinutes { syncSleepTimer(restart: true) }
    }

    func showMessage(_ text: String) {
        message = text
        messageTask?.cancel()
        messageTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.message = nil
        }
    }

    var currentPhoto: Photo? { photos.indices.contains(currentIndex) ? photos[currentIndex] : nil }

    var upcomingPhoto: Photo? {
        if let fresh = freshPhotos.peek(photos, currentIndex: currentIndex) { return photos[fresh] }
        let from = freshPhotos.peekResume(photos) ?? currentIndex
        return order.peekNext(after: from).flatMap { photos.indices.contains($0) ? photos[$0] : nil }
    }

    var isGoogleConfigured: Bool { google.auth.isConfigured }

    /// Informativa privacy pubblicata (chiave PrivacyPolicyURL di Info.plist); nil finché resta il segnaposto.
    let privacyPolicyURL: String? = {
        guard let url = Bundle.main.object(forInfoDictionaryKey: "PrivacyPolicyURL") as? String,
              url.hasPrefix("https://"), !url.contains("EXAMPLE") else { return nil }
        return url
    }()

    // MARK: - Riproduzione

    func startDemo() {
        cancelGoogle()
        defaults.set("demo", forKey: Self.sourceKey)
        show(Self.demoPhotos, label: L("source.demo"), isDemo: true)
        discardGoogleSelection()
        play()
    }

    func play() {
        // Durante la festa lo slideshow parte anche vuoto: mostra il QR in attesa della prima foto.
        guard !photos.isEmpty || guests.active else {
            showMessage(L("message.choose_source"))
            return
        }
        isPlaying = true
        if path.last != .slideshow { path = [.slideshow] }
        syncSleepTimer(restart: false)
    }

    func pause() {
        isPlaying = false
        syncSleepTimer(restart: false)
    }

    func togglePlayback() {
        isPlaying ? pause() : play()
    }

    /// Le foto appena mandate dagli invitati passano davanti, poi si riprende dal punto lasciato.
    func next() {
        guard !photos.isEmpty else { return }
        if let fresh = freshPhotos.take(photos, currentIndex: currentIndex) {
            currentIndex = fresh
        } else {
            currentIndex = order.next(after: freshPhotos.takeResume(photos) ?? currentIndex)
        }
    }

    func previous() {
        guard !photos.isEmpty else { return }
        currentIndex = freshPhotos.takeResume(photos) ?? order.previous(before: currentIndex)
    }

    // MARK: - Google Foto

    func startGoogleConnect() {
        if path.last != .googleConnect { path.append(.googleConnect) }
        guard google.auth.isConfigured else {
            googleState = .failed(L("error.google_not_configured"))
            return
        }
        cancelGoogle()
        googleState = .working(nil)
        let connection = UUID()
        connectionID = connection
        // Una cancellazione ancora in corso ("Disconnetti e cancella") deve finire prima del nuovo collegamento.
        let cleanup = cleanupTask
        googleTask = Task { [weak self, google] in
            await cleanup?.value
            if Task.isCancelled { return }
            do {
                let result = try await google.importFlow.run { [weak self] step in
                    Task { @MainActor [weak self] in
                        self?.applyStep(step, connection: connection)
                    }
                }
                guard let self else { return }
                guard !Task.isCancelled else {
                    // Annullato a selezione appena fatta: la sessione non verrà usata.
                    Self.deleteSession(result.selection.sessionID, google: google)
                    return
                }
                self.applyImport(result)
            } catch {
                guard let self, !Task.isCancelled, !(error is CancellationError) else { return }
                self.googleState = .failed(Self.describe(error))
            }
        }
    }

    /// Salva la nuova selezione (solo identificativi e nomi) e la mostra; chiude la sessione precedente.
    /// Nessuna sospensione: "Disconnetti e cancella" non può inserirsi a metà. Lo stream si accorge da
    /// solo del cambio di sessione.
    private func applyImport(_ result: GoogleImportFlow.Result) {
        let previous = google.selections.current()
        google.selections.save(result.selection)
        defaults.set("google", forKey: Self.sourceKey)
        show(result.selection.photos, label: L("source.google", result.selection.items.count), isDemo: false)
        googleState = .complete(result.selection.items.count, expiresAt: result.selection.expiresAt)
        showMessage(result.skipped > 0 ? L("message.google_skipped", result.skipped) : L("message.google_ready"))
        if let previous, previous.sessionID != result.selection.sessionID {
            Self.deleteSession(previous.sessionID, google: google)
        }
    }

    /// Chiude una sessione Picker presso Google, in background e senza attendere l'esito.
    private nonisolated static func deleteSession(_ sessionID: String, google: GoogleServices) {
        Task.detached {
            await google.picker.deleteSession(token: { try await AppModel.requireToken(google.auth) }, sessionID: sessionID)
        }
    }

    /// L'utente è passato a un'altra sorgente: la selezione Google non verrà più usata (il pulsante
    /// Google Foto ne crea sempre una nuova), quindi si cancella e si chiude la sessione presso Google.
    private func discardGoogleSelection() {
        guard let selection = google.selections.current() else { return }
        google.selections.clear()
        Self.deleteSession(selection.sessionID, google: google)
    }

    private nonisolated static func requireToken(_ auth: GoogleDeviceAuth) async throws -> String {
        guard let token = try await auth.validAccessToken() else { throw FotoFestaError.sessionExpired }
        return token
    }

    private func applyStep(_ step: ImportStep, connection: UUID) {
        // Dopo un annullamento lo stato non è più "working", o appartiene a un nuovo collegamento:
        // i passi del collegamento annullato vengono ignorati.
        guard connection == connectionID, case .working = googleState else { return }
        googleState = .working(step)
    }

    /// La selezione non è più raggiungibile (sessione scaduta o accesso revocato) ed è già stata tolta
    /// dall'archivio: si torna alle foto demo e si spiega cosa fare.
    private func handleSelectionGone(sessionID: String, reason: GooglePhotosStream.GoneReason) {
        // Nel frattempo è arrivata una selezione nuova: quella persa non è più mostrata.
        guard google.selections.current() == nil else { return }
        if defaults.string(forKey: Self.sourceKey) == "google" { defaults.set("demo", forKey: Self.sourceKey) }
        if showingGooglePhotos { show(Self.demoPhotos, label: L("source.demo"), isDemo: true) }
        showMessage(reason == .accessLost ? L("error.google_session_expired") : L("error.google_selection_gone"))
    }

    private func reconcileLostSelection() {
        guard google.selections.current() == nil, showingGooglePhotos else { return }
        handleSelectionGone(sessionID: "", reason: .selectionExpired)
    }

    private var showingGooglePhotos: Bool {
        basePhotos.contains { if case .google = $0.source { true } else { false } }
    }

    func cancelGoogleConnect() {
        cancelGoogle()
        googleState = .idle
    }

    func disconnectGoogle() {
        let pendingImport = googleTask
        cancelGoogle()
        let wasShowingGoogle = showingGooglePhotos
        if wasShowingGoogle || defaults.string(forKey: Self.sourceKey) == "google" {
            defaults.set("demo", forKey: Self.sourceKey)
        }
        if wasShowingGoogle { show(Self.demoPhotos, label: L("source.demo"), isDemo: true) }
        googleState = .idle
        showMessage(L("message.google_removed"))
        // Sul dispositivo restano solo l'elenco delle foto e le immagini in memoria: spariscono subito.
        let google = self.google
        let selection = google.selections.current()
        google.selections.clear()
        let previousCleanup = cleanupTask
        cleanupTask = Task.detached {
            await ImageLoader.shared.removeAll()
            await google.stream.reset()
            await previousCleanup?.value
            // La sessione si chiude finché il token è valido; subito dopo token cancellati e revocati.
            if let selection {
                await google.picker.deleteSession(token: { try await AppModel.requireToken(google.auth) }, sessionID: selection.sessionID)
            }
            await google.auth.revokeAndDisconnect()
            // Un collegamento annullato può aver rinnovato il token mentre si chiudeva: va cancellato.
            await pendingImport?.value
            await google.auth.disconnect()
        }
    }

    /// Il riferimento resta: "Disconnetti e cancella" aspetta la fine di un'importazione annullata
    /// (attendere un Task già concluso termina subito).
    private func cancelGoogle() {
        googleTask?.cancel()
    }

    // MARK: - Foto degli invitati

    /// Foto in attesa di approvazione, dalla più vecchia.
    var pendingGuestPhotos: [Photo] {
        guests.photos.filter { !$0.approved }.map(Self.photo(for:))
    }

    var approvedGuestCount: Int { guestPhotos.count }

    func openGuests() {
        if path.last != .guests { path.append(.guests) }
    }

    func startGuestParty() {
        guests.start()
    }

    func endGuestParty() {
        guests.end()
        freshPhotos.clear()
        showMessage(L("message.guests_ended"))
    }

    /// Salva la rete per il QR della Wi‑Fi; false (con un messaggio) se i dati non vanno bene.
    @discardableResult
    func saveGuestWifi(ssid: String, password: String) -> Bool {
        if let problem = WifiNetwork.validate(ssid: ssid, password: password) {
            switch problem {
            case .missingName: showMessage(L("wifi.error_name"))
            case .nameTooLong: showMessage(L("wifi.error_name_long"))
            case .badPassword: showMessage(L("wifi.error_password"))
            }
            return false
        }
        guests.saveWifi(WifiNetwork(ssid: ssid.trimmingCharacters(in: .whitespaces), password: password))
        if path.last == .guestWifi { path.removeLast() }
        return true
    }

    func approveGuestPhoto(_ photo: Photo) {
        if case let .guest(id) = photo.source { guests.approve(id) }
    }

    func discardGuestPhoto(_ photo: Photo) {
        if case let .guest(id) = photo.source { guests.reject(id) }
    }

    /// Aggiorna la raccolta mostrata. Le foto approvate da poco (arrivate dopo l'apertura dell'app,
    /// o appena approvate) passano davanti nello slideshow.
    private func guestsChanged() {
        let all = guests.photos
        let approved = all.filter(\.approved)
        if let known = knownGuestIDs {
            let fresh = approved.filter { !known.contains($0.id) && ($0.receivedAt >= createdAt || knownPendingIDs.contains($0.id)) }
            freshPhotos.add(fresh.map { "guest:\($0.id)" })
        }
        knownGuestIDs = Set(approved.map(\.id))
        knownPendingIDs = Set(all.filter { !$0.approved }.map(\.id))
        let items = approved.map(Self.photo(for:))
        if items != guestPhotos || guests.active != guestPartyActive {
            guestPhotos = items
            guestPartyActive = guests.active
            applyCollection(restart: false)
        }
        // Approvate o scartate tutte: si torna alla schermata della festa.
        if path.last == .guestReview, all.allSatisfy(\.approved) { path.removeLast() }
    }

    private static func photo(for guest: GuestPhoto) -> Photo {
        Photo(
            id: "guest:\(guest.id)",
            title: guest.sender.map { L("guest.photo_from", $0) } ?? L("guest.photo"),
            source: .guest(id: guest.id)
        )
    }

    // MARK: - Interni

    /// Sostituisce la raccolta scelta ripartendo dalla prima foto dell'ordine di riproduzione.
    private func show(_ newPhotos: [Photo], label: String, isDemo: Bool) {
        basePhotos = newPhotos
        baseLabel = label
        baseIsDemo = isDemo
        applyCollection(restart: true)
    }

    /// Ricalcola la raccolta mostrata. Con `restart` riparte dall'inizio; altrimenti resta sulla foto
    /// corrente (arrivano o spariscono foto degli invitati mentre lo slideshow va avanti).
    private func applyCollection(restart: Bool) {
        let newPhotos = combineWithGuests(base: basePhotos, baseIsDemo: baseIsDemo, guests: guestPhotos, partyActive: guestPartyActive)
        let index: Int
        if restart {
            index = settings.shuffle && !newPhotos.isEmpty ? Int.random(in: 0..<newPhotos.count) : 0
        } else if let currentID = currentPhoto?.id, let same = newPhotos.firstIndex(where: { $0.id == currentID }) {
            index = same
        } else {
            index = max(min(currentIndex, newPhotos.count - 1), 0)
        }
        order.reset(count: newPhotos.count, shuffle: settings.shuffle, current: index)
        photos = newPhotos
        currentIndex = index
        sourceLabel = collectionLabel()
    }

    private func collectionLabel() -> String {
        if guestPhotos.isEmpty, !(baseIsDemo && guestPartyActive) { return baseLabel }
        if baseIsDemo { return L("source.guests", guestPhotos.count) }
        return L("source.with_guests", baseLabel, guestPhotos.count)
    }

    /// Pausa automatica: si avvia con la riproduzione e si azzera alla pausa o al cambio di durata.
    private func syncSleepTimer(restart: Bool) {
        let minutes = settings.sleepTimerMinutes
        let shouldRun = isPlaying && minutes > 0
        if shouldRun, !restart, sleepTask != nil, sleepTaskMinutes == minutes { return }
        sleepTask?.cancel()
        sleepTask = nil
        guard shouldRun else {
            sleepAt = nil
            return
        }
        sleepTaskMinutes = minutes
        sleepAt = Date().addingTimeInterval(TimeInterval(minutes * 60))
        sleepTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60 * 1_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.sleepTask = nil
            self.pause()
            self.showMessage(L("message.sleep_timer"))
        }
    }

    static func describe(_ error: Error) -> String {
        switch error {
        case let error as FotoFestaError:
            switch error {
            case .notConfigured: return L("error.google_not_configured")
            case .accessDenied: return L("error.google_access_denied")
            case .codeExpired: return L("error.google_code_expired")
            case .selectionExpired: return L("error.google_selection_expired")
            case .sessionExpired: return L("error.google_session_expired")
            case .nothingSelected: return L("error.google_nothing_selected")
            case .selectionGone: return L("error.google_selection_gone")
            case .photoUnavailable: return L("photo.unavailable")
            }
        case let error as HTTPStatusError:
            return error.serverMessage ?? L("error.google_http", error.code)
        case is URLError:
            return L("error.network")
        default:
            return L("error.generic")
        }
    }
}
