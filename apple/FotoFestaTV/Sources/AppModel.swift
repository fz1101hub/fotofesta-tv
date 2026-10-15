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
    case complete(Int)
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
    private let auth: GoogleDeviceAuth
    private let picker: PhotosPicker
    private let importFlow: GoogleImportFlow
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
        let http = URLSessionHTTPClient()
        let info = Bundle.main.infoDictionary ?? [:]
        auth = GoogleDeviceAuth(
            http: http,
            tokens: KeychainTokenStore(),
            clientID: (info["PhotosClientID"] as? String) ?? "",
            clientSecret: (info["PhotosClientSecret"] as? String) ?? ""
        )
        // Su tvOS la cartella Caches è l'unico spazio locale ampio; il sistema può svuotarla.
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        picker = PhotosPicker(http: http, cacheRoot: caches)
        importFlow = GoogleImportFlow(auth: auth, picker: picker)
        settings = (UserDefaults.standard.data(forKey: Self.settingsKey))
            .flatMap { try? JSONDecoder().decode(SlideshowSettings.self, from: $0) } ?? SlideshowSettings()
        var initialPhotos = Self.demoPhotos
        var initialLabel = L("source.demo")
        var initialMessage: String?
        if UserDefaults.standard.string(forKey: Self.sourceKey) == "google" {
            let cached = picker.cachedPhotos()
            if cached.isEmpty {
                // tvOS ha liberato spazio cancellando la cache: si torna alle foto demo e lo si spiega.
                UserDefaults.standard.set("demo", forKey: Self.sourceKey)
                initialMessage = L("message.google_cache_purged")
            } else {
                initialPhotos = cached
                initialLabel = L("source.google", cached.count)
            }
        }
        photos = initialPhotos
        sourceLabel = initialLabel
        order.reset(count: initialPhotos.count, shuffle: settings.shuffle)
        if let initialMessage { showMessage(initialMessage) }
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

    var isGoogleConfigured: Bool { auth.isConfigured }

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
        guard auth.isConfigured else {
            googleState = .failed(L("error.google_not_configured"))
            return
        }
        cancelGoogle()
        googleState = .working(nil)
        let connection = UUID()
        connectionID = connection
        // Una cancellazione ancora in corso ("Disconnetti e cancella") deve finire prima del nuovo collegamento.
        let cleanup = cleanupTask
        googleTask = Task { [weak self, importFlow] in
            await cleanup?.value
            if Task.isCancelled { return }
            do {
                let result = try await importFlow.run { [weak self] step in
                    Task { @MainActor [weak self] in
                        self?.applyStep(step, connection: connection)
                    }
                }
                guard let self else { return }
                guard !Task.isCancelled else {
                    self.reloadShownGooglePhotos()
                    return
                }
                self.defaults.set("google", forKey: Self.sourceKey)
                self.show(result.photos, label: L("source.google", result.photos.count))
                self.googleState = .complete(result.photos.count)
                self.showMessage(result.skipped > 0 ? L("message.google_skipped", result.skipped) : L("message.google_ready"))
            } catch {
                guard let self else { return }
                guard !Task.isCancelled, !(error is CancellationError) else {
                    self.reloadShownGooglePhotos()
                    return
                }
                self.googleState = .failed(Self.describe(error))
            }
        }
    }

    private func applyStep(_ step: ImportStep, connection: UUID) {
        // Dopo un annullamento lo stato non è più "working", o appartiene a un nuovo collegamento:
        // i passi del collegamento annullato vengono ignorati.
        guard connection == connectionID, case let .working(current) = googleState else { return }
        // I passi arrivano con Task separati, senza ordine garantito: il contatore non torna indietro.
        if case let .downloading(done, _)? = current, case let .downloading(newDone, _) = step, newDone < done { return }
        googleState = .working(step)
    }

    /// Un annullamento può arrivare quando l'archivio è già stato sostituito su disco: se sono mostrate
    /// foto Google, l'elenco deve seguire i file presenti.
    private func reloadShownGooglePhotos() {
        guard photos.contains(where: { if case .file = $0.source { true } else { false } }) else { return }
        let cached = picker.cachedPhotos()
        guard !cached.isEmpty, cached.map(\.id) != photos.map(\.id) else { return }
        show(cached, label: L("source.google", cached.count))
    }

    /// tvOS può svuotare Caches mentre l'app è sospesa: al ritorno in primo piano si ricontrolla l'archivio.
    func checkCachedPhotos() {
        guard defaults.string(forKey: Self.sourceKey) == "google" else { return }
        if case .working = googleState { return }
        guard picker.cachedPhotos().isEmpty else { return }
        defaults.set("demo", forKey: Self.sourceKey)
        if photos.contains(where: { if case .file = $0.source { true } else { false } }) {
            show(Self.demoPhotos, label: L("source.demo"))
        }
        showMessage(L("message.google_cache_purged"))
    }

    func cancelGoogleConnect() {
        cancelGoogle()
        googleState = .idle
    }

    func disconnectGoogle() {
        let pendingImport = googleTask
        cancelGoogle()
        let wasShowingGoogle = photos.contains { if case .file = $0.source { true } else { false } }
        if wasShowingGoogle || defaults.string(forKey: Self.sourceKey) == "google" {
            defaults.set("demo", forKey: Self.sourceKey)
        }
        if wasShowingGoogle { show(Self.demoPhotos, label: L("source.demo")) }
        googleState = .idle
        showMessage(L("message.google_removed"))
        let previousCleanup = cleanupTask
        cleanupTask = Task.detached { [auth, picker] in
            await previousCleanup?.value
            // I token locali spariscono per primi (poi la revoca presso Google); intanto si cancellano i file.
            async let revoke: Void = auth.revokeAndDisconnect()
            picker.clearCache()
            // Un download annullato a un passo dalla fine può aver appena salvato l'archivio.
            await pendingImport?.value
            picker.clearCache()
            await revoke
            // L'importazione annullata può aver rinnovato il token mentre chiudeva la sessione Picker.
            await auth.disconnect()
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
            case .downloadFailed: return L("error.google_download_failed")
            case .cacheWrite: return L("error.cache_write")
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
