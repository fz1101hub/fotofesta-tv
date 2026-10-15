import FotoFestaCore
import Foundation
import Observation

enum Route: Hashable {
    case slideshow, settings, games, memory, mystery, googleConnect
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
    @ObservationIgnored private var googleTask: Task<Void, Never>?
    @ObservationIgnored private var cleanupTask: Task<Void, Never>?
    /// Identifica il collegamento in corso: i passi di un collegamento annullato vengono ignorati.
    @ObservationIgnored private var connectionID = UUID()
    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var sleepTaskMinutes = 0
    @ObservationIgnored private var messageTask: Task<Void, Never>?

    private static let settingsKey = "slideshowSettings"
    private static let sourceKey = "lastSource"

    init() {
        settings = (UserDefaults.standard.data(forKey: Self.settingsKey))
            .flatMap { try? JSONDecoder().decode(SlideshowSettings.self, from: $0) } ?? SlideshowSettings()
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
        }
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

    var upcomingPhoto: Photo? { order.peekNext(after: currentIndex).flatMap { photos.indices.contains($0) ? photos[$0] : nil } }

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
        show(Self.demoPhotos, label: L("source.demo"))
        discardGoogleSelection()
        play()
    }

    func play() {
        guard !photos.isEmpty else {
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

    func next() {
        guard !photos.isEmpty else { return }
        currentIndex = order.next(after: currentIndex)
    }

    func previous() {
        guard !photos.isEmpty else { return }
        currentIndex = order.previous(before: currentIndex)
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
        show(result.selection.photos, label: L("source.google", result.selection.items.count))
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
        if showingGooglePhotos { show(Self.demoPhotos, label: L("source.demo")) }
        showMessage(reason == .accessLost ? L("error.google_session_expired") : L("error.google_selection_gone"))
    }

    private var showingGooglePhotos: Bool {
        photos.contains { if case .google = $0.source { true } else { false } }
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
        if wasShowingGoogle { show(Self.demoPhotos, label: L("source.demo")) }
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

    // MARK: - Interni

    private func show(_ newPhotos: [Photo], label: String) {
        let start = settings.shuffle && !newPhotos.isEmpty ? Int.random(in: 0..<newPhotos.count) : 0
        order.reset(count: newPhotos.count, shuffle: settings.shuffle, current: start)
        photos = newPhotos
        currentIndex = start
        sourceLabel = label
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
