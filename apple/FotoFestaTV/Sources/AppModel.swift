import FotoFestaCore
import Foundation
import Observation

/// Chiave delle impostazioni in UserDefaults, fuori dalla classe: la legge anche la coda del server
/// degli invitati.
private let slideshowSettingsKey = "slideshowSettings"

enum Route: Hashable {
    case slideshow, settings, games, memory, mystery, puzzle, whoTookIt, poll, lottery, googleConnect, guests, guestReview, guestWifi, awards, guestbook, announce, library, network
    case partyGames, play, tournament, bets, toast, coins
    case shows, rewind, barcode, photoMosaic, credits, teletext, sky, stats
    case door, phoneWall
    case absent, artwork, soundShow, livingQR, dream, museum, chapters, onThisDay, differences, quizHosts, quizKids
    case devices, photoBooth
}

/// Da dove vengono le foto della raccolta mostrata nella home.
enum CollectionSource: Equatable {
    case demo, google, guests, library, network
}

/// Cartella di un server di rete aperta nel browser, con il percorso dalla cartella principale.
struct NetworkBrowse: Equatable {
    struct Crumb: Equatable {
        let id: String
        let title: String
    }

    let server: MediaServer
    var trail: [Crumb]
    var folders: [MediaContainer] = []
    var photosHere = 0
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
    /// Nome della raccolta per la home (Google Foto, foto demo, foto degli invitati) e sua origine.
    private(set) var collectionTitle = ""
    private(set) var collectionSource: CollectionSource = .demo
    /// Foto della raccolta scelta, senza quelle degli invitati aggiunte durante la festa.
    private(set) var collectionCount = 0
    private(set) var currentIndex = 0
    /// Cresce a ogni cambio di foto voluto (successiva, precedente, nuova raccolta). Il cambio
    /// automatico si basa su questo e non sull'indice, che si sposta quando arrivano o spariscono
    /// foto degli invitati anche se la foto mostrata resta la stessa.
    private(set) var slideTick = 0
    private(set) var isPlaying = false
    private(set) var googleState: GoogleState = .idle
    private(set) var sleepAt: Date?
    private(set) var message: String?
    private(set) var settings: SlideshowSettings
    /// Libreria Foto (Foto di iCloud): permesso, album e lettura in corso.
    private(set) var libraryAccess: PhotoLibrary.Access = PhotoLibrary.access
    private(set) var libraryAlbums: [LibraryAlbum] = []
    private(set) var libraryLoading = false
    /// Cartelle di rete (DLNA): server trovati, cartella aperta e lettura in corso.
    private(set) var networkServers: [MediaServer] = []
    private(set) var networkSearching = false
    private(set) var networkBrowse: NetworkBrowse?
    private(set) var networkLoading = false
    private(set) var networkSaved: NetworkFolder?

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
    /// Luci, stampanti e fotocamere della festa.
    let devices = PartyDevices()
    #if os(macOS)
    /// Fotocamera con il cavo USB e cartella a caldo del Mac.
    let usbCameras = USBCameras()
    let hotFolder = HotFolder()
    private(set) var usbCameraEnabled = UserDefaults.standard.bool(forKey: "usbCamera")
    #endif
    @ObservationIgnored private var basePhotos: [Photo]
    @ObservationIgnored private var baseLabel: String
    @ObservationIgnored private var baseIsDemo: Bool
    /// Sorgente e nome della raccolta scelta, per la home.
    @ObservationIgnored private var baseSource: CollectionSource = .demo
    @ObservationIgnored private var baseTitle = ""
    @ObservationIgnored private var libraryTask: Task<Void, Never>?
    @ObservationIgnored private var libraryReload: Task<Void, Never>?
    @ObservationIgnored private var libraryWatcher: PhotoLibraryWatcher?
    @ObservationIgnored private var networkTask: Task<Void, Never>?
    @ObservationIgnored private var networkSearchTask: Task<Void, Never>?
    /// Server multimediali della rete di casa. Le foto si chiedono nella versione che basta a uno
    /// schermo 4K (se il server ne offre più d'una): la riduzione finale la fa ImageLoader.
    @ObservationIgnored private let networkMedia: NetworkMedia = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 20
        return NetworkMedia(
            http: URLSessionHTTPClient(
                session: URLSession(configuration: configuration),
                maxBytes: 8 * 1024 * 1024,
                allowRedirect: { LANAddress.isLocal($0) }
            ),
            screen: ScreenSize(longEdge: 3840, shortEdge: 2160)
        )
    }()
    @ObservationIgnored private var guestPhotos: [Photo] = []
    @ObservationIgnored private var guestPartyActive = false
    @ObservationIgnored private var guestsDeveloped = true
    @ObservationIgnored private let freshPhotos = FreshPhotos()
    /// Foto approvate già viste; nil finché le foto di una festa interrotta non sono state riprese.
    @ObservationIgnored private var knownGuestIDs: Set<String>?
    @ObservationIgnored private var googleTask: Task<Void, Never>?
    @ObservationIgnored private var cleanupTask: Task<Void, Never>?
    /// Identifica il collegamento in corso: i passi di un collegamento annullato vengono ignorati.
    @ObservationIgnored private var connectionID = UUID()
    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var sleepTaskMinutes = 0
    @ObservationIgnored private var messageTask: Task<Void, Never>?
    /// Passaggi di ogni foto sullo schermo, per "Foto che invecchiano".
    @ObservationIgnored private var showCounts: [String: Int] = [:]
    @ObservationIgnored private var lastShownPhoto: Photo?
    @ObservationIgnored private var quietTask: Task<Void, Never>?
    @ObservationIgnored private var rehearsalTask: Task<Void, Never>?
    /// Prova generale in corso, con telefoni simulati.
    private(set) var rehearsal = false
    /// Tastierino del chiosco aperto e per cosa.
    var pinRequest: PinPurpose?
    /// Ultimo sblocco del chiosco: le schermate protette si riaprono senza PIN per qualche minuto.
    var kioskUnlockedUntil = Date.distantPast
    @ObservationIgnored let kioskLock = KioskLock()
    @ObservationIgnored var venueClock: Task<Void, Never>?
    @ObservationIgnored var announcedSlots: Set<String> = []
    @ObservationIgnored var lastResetDay = -1
    @ObservationIgnored let speaker = AnnouncementSpeaker()
    /// Passaggi (meno due per ogni reazione) perché una foto sia del tutto ingiallita.
    static let agingSteps = 8
    static let rehearsalTime: TimeInterval = 120

    private static let settingsKey = slideshowSettingsKey
    private static let sourceKey = "lastSource"
    private static let libraryAlbumKey = "libraryAlbumID"
    private static let libraryTitleKey = "libraryAlbumTitle"
    private static let networkFolderKey = "networkFolder"

    init() {
        let storedSettings = Self.storedSettings()
        settings = storedSettings
        ImageLoader.hdr = storedSettings.hdrPhotos
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
        baseSource = baseIsDemo ? .demo : .google
        baseTitle = baseIsDemo ? L("collection.demo") : L("home.google_title")
        order.reset(count: initialPhotos.count, shuffle: settings.shuffle)
        updateCollectionInfo()
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
        guests.onArrival = { [weak self] arrival in
            self?.showMessage(L("arrival.message", arrival.name, arrival.order))
            if self?.settings.partyLights == true { self?.devices.flash() }
        }
        guests.onSong = { [weak self] song in
            self?.showMessage(song.name.map { L("song.message_named", $0, song.text) } ?? L("song.message", song.text))
        }
        guests.onAnnouncement = { [weak self] announcement in self?.announcementArrived(announcement) }
        guestsChanged()
        // Regia dal telefono e regista di turno: i comandi arrivano dalla coda del server.
        let run: @Sendable (PartyCommand) -> Void = { [weak self] command in
            Task { @MainActor [weak self] in self?.execute(command) }
        }
        guests.hostRemote.setOnCommand(run)
        guests.partyHub.setOnDirectorCommand(run)
        guests.hostStatus = { [weak self] in self?.hostStatus() ?? HostRemote.Status() }
        if UserDefaults.standard.string(forKey: Self.sourceKey) == "library" { restoreLibraryAlbum() }
        networkSaved = UserDefaults.standard.data(forKey: Self.networkFolderKey).flatMap { try? JSONDecoder().decode(NetworkFolder.self, from: $0) }
        if UserDefaults.standard.string(forKey: Self.sourceKey) == "network", let folder = networkSaved { restoreNetworkFolder(folder) }
        startVenueClock()
        IntentTarget.model = self
        // Festa sempre attiva (locali, uso fisso): parte da sola all'apertura dell'app.
        if settings.alwaysParty {
            Task { @MainActor [weak self] in self?.startPartyAndPlay() }
        }
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
        ImageLoader.hdr = settings.hdrPhotos
        if old.shuffle != settings.shuffle { order.reset(count: photos.count, shuffle: settings.shuffle, current: currentIndex) }
        if old.sleepTimerMinutes != settings.sleepTimerMinutes { syncSleepTimer(restart: true) }
        if old.videos != settings.videos { applyCollection(restart: false) }
        if old.ftpServer != settings.ftpServer { syncDevices() }
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

    /// Le foto della raccolta senza i video, per giochi e sfondi.
    var stillPhotos: [Photo] { photos.filter { !$0.isVideo } }

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
        cancelLibraryLoad()
        cancelNetworkLoad()
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
        } else if let resumed = freshPhotos.resume(photos, next: { [order] in order.next(after: $0) }) {
            currentIndex = resumed
        } else {
            currentIndex = order.next(after: currentIndex)
        }
        slideTick += 1
    }

    func previous() {
        guard !photos.isEmpty else { return }
        currentIndex = freshPhotos.takeResume(photos) ?? order.previous(before: currentIndex)
        slideTick += 1
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
        cancelLibraryLoad()
        cancelNetworkLoad()
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

    private nonisolated static func requireToken(_ auth: GoogleAuth) async throws -> String {
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

    // MARK: - Libreria Foto (Foto di iCloud)

    /// Album della libreria in uso, per il segno "In uso".
    var libraryAlbumInUse: String? {
        collectionSource == .library ? defaults.string(forKey: Self.libraryAlbumKey) : nil
    }

    func openLibrary() {
        if path.last != .library { path.append(.library) }
        refreshLibrary()
    }

    /// Rilegge permesso e album, ad esempio tornando dalle Impostazioni.
    func refreshLibrary() {
        libraryAccess = PhotoLibrary.access
        guard libraryAccess == .authorized else {
            libraryAlbums = []
            return
        }
        libraryLoading = true
        libraryTask?.cancel()
        libraryTask = Task { [weak self] in
            let albums = await Task.detached(priority: .userInitiated) { PhotoLibrary.albums() }.value
            guard let self, !Task.isCancelled else { return }
            self.libraryAlbums = albums
            self.libraryLoading = false
        }
    }

    /// La prima volta tvOS chiede all'utente se l'app può vedere le foto.
    func requestLibraryAccess() {
        Task { [weak self] in
            let access = await PhotoLibrary.requestAccess()
            guard let self else { return }
            self.libraryAccess = access
            if access == .authorized { self.refreshLibrary() }
        }
    }

    /// "Usa questo album": diventa la raccolta e si aggiorna da solo quando l'album cambia.
    func useLibraryAlbum(_ album: LibraryAlbum) {
        libraryTask?.cancel()
        // Un aggiornamento dell'album precedente ancora in attesa non deve sostituire quello nuovo.
        libraryReload?.cancel()
        libraryLoading = true
        libraryTask = Task { [weak self] in
            let photos = await Task.detached(priority: .userInitiated) { PhotoLibrary.photos(albumID: album.id) }.value
            guard let self, !Task.isCancelled else { return }
            self.libraryLoading = false
            guard let photos, !photos.isEmpty else {
                self.showMessage(L("library.empty_album"))
                return
            }
            self.cancelGoogle()
            self.cancelNetworkLoad()
            self.defaults.set("library", forKey: Self.sourceKey)
            self.defaults.set(album.id, forKey: Self.libraryAlbumKey)
            self.defaults.set(album.title, forKey: Self.libraryTitleKey)
            self.showLibrary(photos, title: album.title)
            self.discardGoogleSelection()
            self.showMessage(L("message.library_ready"))
            if self.path.last == .library { self.path.removeLast() }
        }
    }

    /// Un album in lettura non serve più: è stata scelta un'altra sorgente.
    private func cancelLibraryLoad() {
        libraryTask?.cancel()
        libraryLoading = false
    }

    /// All'avvio: l'album salvato, se il permesso c'è ancora e l'album esiste.
    private func restoreLibraryAlbum() {
        guard let albumID = defaults.string(forKey: Self.libraryAlbumKey) else { return }
        let title = defaults.string(forKey: Self.libraryTitleKey) ?? L("home.library_title")
        libraryTask = Task { [weak self] in
            var photos: [Photo]?
            if PhotoLibrary.access == .authorized {
                photos = await Task.detached(priority: .userInitiated) { PhotoLibrary.photos(albumID: albumID) }.value
            }
            guard let self, !Task.isCancelled else { return }
            guard let photos, !photos.isEmpty else {
                self.defaults.set("demo", forKey: Self.sourceKey)
                self.showMessage(L("library.unavailable"))
                return
            }
            // Nel frattempo è stata scelta un'altra sorgente: l'album salvato non serve più.
            guard self.defaults.string(forKey: Self.sourceKey) == "library", self.baseIsDemo else { return }
            self.showLibrary(photos, title: title)
        }
    }

    private func showLibrary(_ photos: [Photo], title: String) {
        show(photos, label: L("source.library", photos.count), isDemo: false, source: .library, title: title)
        if libraryWatcher == nil {
            libraryWatcher = PhotoLibraryWatcher { [weak self] in
                Task { @MainActor [weak self] in self?.libraryChanged() }
            }
        }
    }

    /// Foto aggiunte o tolte dall'album (anche dagli altri, negli album condivisi): la raccolta si
    /// aggiorna senza ricominciare. Più cambi ravvicinati si leggono una volta sola.
    private func libraryChanged() {
        guard baseSource == .library, let albumID = defaults.string(forKey: Self.libraryAlbumKey) else { return }
        libraryReload?.cancel()
        libraryReload = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            let photos = await Task.detached(priority: .utility) { PhotoLibrary.photos(albumID: albumID) }.value
            // Nel frattempo l'utente può aver scelto un altro album o un'altra sorgente.
            guard let self, !Task.isCancelled, self.baseSource == .library,
                  self.defaults.string(forKey: Self.libraryAlbumKey) == albumID else { return }
            guard let photos, !photos.isEmpty else {
                // Album svuotato o eliminato: non si continua a mostrare foto che non ci sono più.
                self.defaults.set("demo", forKey: Self.sourceKey)
                self.show(Self.demoPhotos, label: L("source.demo"), isDemo: true)
                self.showMessage(photos == nil ? L("library.album_gone") : L("library.empty_album"))
                return
            }
            guard photos != self.basePhotos else { return }
            self.basePhotos = photos
            self.baseLabel = L("source.library", photos.count)
            self.applyCollection(restart: false)
        }
    }

    // MARK: - Cartelle di rete (DLNA)

    func openNetwork() {
        if path.last != .network { path.append(.network) }
        networkBrowse = nil
        if networkServers.isEmpty, !networkSearching { searchNetwork() }
    }

    /// Cerca i server in multicast: senza l'entitlement di Apple non risponde nessuno e resta
    /// l'indirizzo da scrivere.
    func searchNetwork() {
        networkSearchTask?.cancel()
        networkSearching = true
        networkSearchTask = Task { [weak self, networkMedia] in
            let found = await networkMedia.discover()
            guard let self, !Task.isCancelled else { return }
            self.mergeNetworkServers(found)
            self.networkSearching = false
        }
    }

    /// Server all'indirizzo scritto con il telecomando: se ce n'è uno solo si apre subito.
    func addNetworkAddress(_ text: String) {
        let host = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard LANAddress.isLocal(host) else {
            showMessage(L("network.error_address"))
            return
        }
        networkTask?.cancel()
        networkLoading = true
        networkTask = Task { [weak self, networkMedia] in
            let found = await networkMedia.servers(at: host)
            guard let self, !Task.isCancelled else { return }
            self.networkLoading = false
            guard !found.isEmpty else {
                self.showMessage(L("network.error_not_found", host))
                return
            }
            self.mergeNetworkServers(found)
            if found.count == 1 { self.openNetworkServer(found[0]) }
        }
    }

    private func mergeNetworkServers(_ found: [MediaServer]) {
        var byUDN = Dictionary(networkServers.map { ($0.udn, $0) }, uniquingKeysWith: { first, _ in first })
        for server in found { byUDN[server.udn] = server }
        networkServers = byUDN.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    func openNetworkServer(_ server: MediaServer) {
        loadNetworkPage(NetworkBrowse(server: server, trail: [.init(id: NetworkMedia.root, title: server.name)]))
    }

    func openNetworkFolder(_ folder: MediaContainer) {
        guard var browse = networkBrowse else { return }
        browse.trail.append(.init(id: folder.id, title: folder.title))
        loadNetworkPage(browse)
    }

    /// Cartella superiore; dalla cartella principale si torna all'elenco dei server.
    func networkUp() {
        guard var browse = networkBrowse else { return }
        networkTask?.cancel()
        networkLoading = false
        browse.trail.removeLast()
        if browse.trail.isEmpty {
            networkBrowse = nil
        } else {
            loadNetworkPage(browse)
        }
    }

    /// Riapre la cartella in uso, per cambiarla o sceglierne una vicina.
    func openSavedNetworkFolder() {
        guard let folder = networkSaved else { return }
        networkTask?.cancel()
        networkLoading = true
        networkTask = Task { [weak self, networkMedia] in
            let server = await Self.findSavedServer(folder, networkMedia)
            guard let self, !Task.isCancelled else { return }
            self.networkLoading = false
            guard let server else {
                self.showMessage(L("network.unreachable"))
                return
            }
            self.mergeNetworkServers([server])
            var trail = [NetworkBrowse.Crumb(id: NetworkMedia.root, title: server.name)]
            if folder.containerID != NetworkMedia.root { trail.append(.init(id: folder.containerID, title: folder.title)) }
            self.loadNetworkPage(NetworkBrowse(server: server, trail: trail))
        }
    }

    private func loadNetworkPage(_ target: NetworkBrowse) {
        guard let current = target.trail.last else { return }
        networkTask?.cancel()
        networkLoading = true
        networkTask = Task { [weak self, networkMedia] in
            do {
                let page = try await networkMedia.list(target.server, containerID: current.id)
                guard let self, !Task.isCancelled else { return }
                var browse = target
                browse.folders = page.containers
                browse.photosHere = page.files.filter { !$0.mimeType.hasPrefix("video/") }.count
                self.networkBrowse = browse
                self.networkLoading = false
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.networkLoading = false
                self.showMessage(L("network.unreachable"))
            }
        }
    }

    /// "Usa questa cartella": foto e video della cartella e delle sottocartelle diventano la raccolta.
    func useNetworkFolder() {
        guard let browse = networkBrowse, let current = browse.trail.last else { return }
        // La cartella principale prende il nome del server, le altre il proprio.
        let folder = NetworkFolder(udn: browse.server.udn, location: browse.server.location, containerID: current.id, title: current.title)
        networkTask?.cancel()
        networkLoading = true
        showMessage(L("network.indexing"))
        networkTask = Task { [weak self, networkMedia] in
            let photos = try? await networkMedia.scanPhotos(browse.server, containerID: current.id)
            guard let self, !Task.isCancelled else { return }
            self.networkLoading = false
            guard let photos else {
                self.showMessage(L("network.unreachable"))
                return
            }
            guard !photos.isEmpty else {
                self.showMessage(L("network.empty"))
                return
            }
            self.cancelGoogle()
            self.cancelLibraryLoad()
            self.applyNetworkFolder(folder, photos: photos)
            self.discardGoogleSelection()
            self.showMessage(L("message.network_ready"))
            if self.path.last == .network { self.path.removeLast() }
        }
    }

    private func applyNetworkFolder(_ folder: NetworkFolder, photos: [Photo]) {
        defaults.set("network", forKey: Self.sourceKey)
        if let data = try? JSONEncoder().encode(folder) { defaults.set(data, forKey: Self.networkFolderKey) }
        networkSaved = folder
        show(photos, label: L("source.network", photos.count), isDemo: false, source: .network, title: folder.title)
    }

    /// Server della cartella salvata. Quello ricordato in questa sessione può aver cambiato
    /// indirizzo dopo lo standby: se non risponde più si dimentica e si ricerca una volta.
    private nonisolated static func findSavedServer(_ folder: NetworkFolder, _ networkMedia: NetworkMedia) async -> MediaServer? {
        let remembered = await networkMedia.known(folder.udn) != nil
        guard let server = await networkMedia.find(udn: folder.udn, location: folder.location) else { return nil }
        if remembered, (try? await networkMedia.list(server, containerID: NetworkMedia.root)) == nil {
            await networkMedia.forget(folder.udn)
            return await networkMedia.find(udn: folder.udn, location: folder.location)
        }
        return server
    }

    /// All'avvio: la cartella salvata, se il server è acceso (anche con un nuovo indirizzo).
    private func restoreNetworkFolder(_ folder: NetworkFolder) {
        networkTask = Task { [weak self, networkMedia] in
            var photos: [Photo]?
            if let server = await Self.findSavedServer(folder, networkMedia) {
                photos = try? await networkMedia.scanPhotos(server, containerID: folder.containerID)
            }
            guard let self, !Task.isCancelled else { return }
            guard let photos, !photos.isEmpty else {
                self.showMessage(L("network.unavailable"))
                return
            }
            guard self.defaults.string(forKey: Self.sourceKey) == "network", self.baseIsDemo else { return }
            self.show(photos, label: L("source.network", photos.count), isDemo: false, source: .network, title: folder.title)
        }
    }

    private func cancelNetworkLoad() {
        networkTask?.cancel()
        networkLoading = false
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

    /// "Avvia la festa" della home: accende la ricezione delle foto e fa partire lo slideshow con il QR
    /// nell'angolo. Con le foto demo lo slideshow mostra il QR grande in attesa della prima foto.
    func startPartyAndPlay() {
        guests.start()
        if !guests.qrInSlideshow { guests.setQRInSlideshow(true) }
        play()
    }

    /// Stato dello schermo per la pagina di regia sul telefono.
    private func hostStatus() -> HostRemote.Status {
        HostRemote.Status(
            slideshow: path.last == .slideshow,
            playing: isPlaying,
            title: currentPhoto?.title,
            photos: photos.count,
            pendingPhotos: guests.photos.filter { !$0.approved }.map { HostRemote.PendingPhoto(id: $0.id, sender: $0.sender) }
        )
    }

    /// Comando della regia dal telefono o del regista di turno.
    private func execute(_ command: PartyCommand) {
        let inSlideshow = path.last == .slideshow
        switch command {
        case .next:
            if inSlideshow { next() } else { play() }
        case .previous: if inSlideshow { previous() }
        case .play: if !inSlideshow || !isPlaying { play() }
        case .pause: if inSlideshow { pause() }
        case let .approvePhoto(id): guests.approve(id)
        case let .rejectPhoto(id): guests.reject(id)
        case let .approveMessage(id): guests.partyHub.approveMessage(id)
        case let .rejectMessage(id): guests.partyHub.removeMessage(id)
        case .approveAll:
            guests.approveAll()
            guests.partyHub.approveAllMessages()
        case let .announce(text): guests.announce(text)
        case .startDirector: startDirector()
        case .startQuiet: startQuiet()
        case .develop: develop()
        case let .songDone(id): guests.songDone(id)
        case let .setBrandName(name): guests.partySetup.setName(name)
        case let .setAccent(color): guests.partySetup.setAccent(color)
        case let .setSchedule(text): guests.partySetup.setSchedule(text)
        case let .setGuestList(text): guests.partySetup.setGuestList(text)
        case .removeLogo: guests.partySetup.setLogo(nil)
        case let .removeSponsor(id): guests.partySetup.removeSponsor(id)
        case let .setQuiz(text): guests.partySetup.setQuiz(text)
        case let .removeKid(id): guests.partySetup.removeKid(id)
        case let .chapter(title):
            guests.partyHub.chapter(title)
            showMessage(L("message.chapter", title))
        }
        guests.refreshWallAndSetup()
    }

    /// Foto sullo schermo: le reazioni dei telefoni contano per lei, con "Foto che invecchiano" si
    /// contano i passaggi e con "Festa senza tracce" la foto degli invitati appena lasciata si cancella.
    func photoShown(_ photo: Photo?) {
        guests.partyHub.showing(photo?.id)
        let previous = lastShownPhoto
        lastShownPhoto = photo
        if let photo { showCounts[photo.id, default: 0] += 1 }
        // Le luci della festa prendono il colore della foto sullo schermo.
        if settings.partyLights, !devices.config.lights.isEmpty, let photo, !photo.isVideo {
            Task { [devices] in
                if let result = await pixels(of: photo, width: 8, height: 8) { devices.showPhotoColor(PartyShows.average(rgba: result.rgba)) }
            }
        }
        if guests.noTraces, let previous, previous.id != photo?.id {
            if case let .guest(id) = previous.source { guests.reject(id) }
            if case let .guestVideo(id) = previous.source { guests.removeVideo(id) }
        }
    }

    /// Quanto è "invecchiata" la foto: 0 nuova, 1 del tutto ingiallita.
    func photoAge(_ photo: Photo) -> Double {
        guard guests.agingPhotos else { return 0 }
        let shows = (showCounts[photo.id] ?? 1) - 1
        let reactions = guests.partyHub.reactionCount(photo.id)
        return Double(min(max(shows - 2 * reactions, 0), Self.agingSteps)) / Double(Self.agingSteps)
    }

    /// Sviluppo del rullino usa e getta: le foto scattate finora compaiono.
    func develop() {
        guests.develop()
        showMessage(L("message.developed"))
    }

    /// Tre minuti senza telefoni: lo slideshow si ferma su una foto e i telefoni aspettano.
    func startQuiet() {
        guard guests.active else { return }
        guests.startQuiet()
        quietTask?.cancel()
        play()
        pause()
        quietTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(PartyHub.quietTime))
            guard let self, !Task.isCancelled else { return }
            self.guests.stopQuiet()
            if self.path.last == .slideshow, !self.isPlaying { self.play() }
        }
    }

    /// Prova generale: per due minuti otto telefoni finti mandano foto demo, reazioni, dediche e
    /// risposte, così si provano schermo e audio prima della festa. Alla fine si cancella tutto.
    func startRehearsal() {
        guard !guests.active else {
            showMessage(L("message.rehearsal_party"))
            return
        }
        guests.start()
        rehearsalTask?.cancel()
        rehearsal = true
        play()
        showMessage(L("message.rehearsal_started"))
        rehearsalTask = Task { [weak self] in
            let names = ["Giulia", "Marco", "Sara", "Luca", "Anna", "Paolo", "Elena", "Davide"]
            let players = names.map { "prova-\($0.lowercased())-telefono" }
            let texts = [L("rehearsal.message_1"), L("rehearsal.message_2"), L("rehearsal.message_3")]
            let pictures = ["demo_coast", "demo_picnic", "demo_mountains", "demo_birthday"]
            let end = Date().addingTimeInterval(Self.rehearsalTime)
            var step = 0
            while Date() < end, !Task.isCancelled {
                guard let self, self.guests.active else { break }
                let guest = step % names.count
                let hub = self.guests.partyHub
                switch step % 4 {
                case 0:
                    if let url = Bundle.main.url(forResource: pictures[(step / 4) % pictures.count], withExtension: "jpg"),
                       let data = try? Data(contentsOf: url) {
                        self.guests.addRehearsalPhoto(data, sender: names[guest])
                    }
                case 1:
                    for offset in 0..<3 {
                        let index = (guest + offset) % names.count
                        _ = hub.react(player: players[index], index: Int.random(in: 0..<PartyHub.Reaction.allCases.count), name: names[index])
                    }
                case 2:
                    _ = hub.message(player: players[guest], text: texts[step % texts.count], name: names[guest])
                default:
                    if let question = self.guests.phoneGame.current(), question.open, !question.choices.isEmpty {
                        _ = self.guests.phoneGame.answer(round: question.round, choice: Int.random(in: 0..<question.choices.count), player: players[guest], name: names[guest])
                    }
                }
                step += 1
                try? await Task.sleep(for: .milliseconds(Int.random(in: 2_000...4_500)))
            }
            guard let self, !Task.isCancelled else { return }
            if self.guests.active { self.endGuestParty() }
            self.rehearsal = false
            self.showMessage(L("message.rehearsal_done"))
        }
    }

    // MARK: - Spettacoli sullo schermo

    /// Titolo della festa (dal modello scelto) per sigla, TG, titoli di coda e Televideo.
    var partyTitle: String { Self.partyTitle(for: settings.template) }

    nonisolated static func partyTitle(for template: PartyTemplate) -> String {
        switch template {
        case .none: L("template.title_none")
        case .birthday: L("template.title_birthday")
        case .wedding: L("template.title_wedding")
        case .newYear: L("template.title_new_year")
        case .graduation: L("template.title_graduation")
        case .kids: L("template.title_kids")
        case .reunion: L("template.title_reunion")
        }
    }

    /// Modello di festa: applica titolo, transizione, disposizione, filtro ed effetti.
    func applyTemplate(_ template: PartyTemplate) {
        updateSettings { $0 = template.apply(to: $0) }
    }

    /// La festa in numeri.
    func partyStats() -> PartyShows.Stats {
        PartyShows.stats(
            photos: photos,
            messages: guests.messages.count,
            reactionsByPhoto: guests.partyHub.allReactionCounts(),
            reactionsByKind: guests.partyHub.reactionTotals(),
            coins: guests.partyPlay.leaderboard(limit: Int.max).reduce(0) { $0 + $1.coins }
        )
    }

    /// Titoli di coda: fotografi, dediche, campioni dei giochi e gli altri invitati.
    func credits() -> [PartyShows.CreditSection] {
        PartyShows.credits(
            photoSenders: guestPhotos.map(\.sender),
            messageNames: guests.messages.map(\.name),
            champions: guests.phoneGame.leaderboard(limit: 3).map(\.name) + guests.partyPlay.leaderboard(limit: 3).map(\.name),
            participants: lotteryCandidates()
        )
    }

    // MARK: - Giochi con i telefoni

    /// Foto da rifare in "Ricrea la foto".
    private(set) var recreateOriginal: Photo?
    /// Monete per chi vince una scommessa.
    static let betCoins = 3

    /// Nuovo gioco `kind` con i testi tradotti; false (con un messaggio) se mancano invitati con il nome.
    @discardableResult
    func startPlay(_ kind: PartyPlay.Kind) -> Bool {
        func texts(_ list: String) -> [String] {
            list.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.shuffled()
        }
        let prompt: String
        var pool: [String] = []
        switch kind {
        case .drawGuess: prompt = L("play.prompt_draw_guess"); pool = texts(L("play.words"))
        case .drawingWall: prompt = texts(L("play.wall_themes")).first ?? ""
        case .story: prompt = L("play.prompt_story"); pool = texts(L("play.story_starts"))
        case .whisper: prompt = L("play.prompt_whisper"); pool = texts(L("play.whisper_phrases"))
        case .compliments: prompt = L("play.prompt_compliments")
        case .missions: prompt = L("play.prompt_missions"); pool = texts(L("play.missions"))
        case .paparazzi: prompt = L("play.prompt_paparazzi")
        case .sameMoment: prompt = L("play.prompt_moment")
        case .recreate:
            guard let original = stillPhotos.randomElement() else {
                showMessage(L("games.need_photos", 1))
                return false
            }
            recreateOriginal = original
            prompt = L("play.prompt_recreate")
            pool = [original.id]
        case .mole: prompt = L("play.prompt_mole"); pool = texts(L("play.mole_missions"))
        case .capsule: prompt = L("play.prompt_capsule")
        }
        guard guests.partyPlay.start(kind, prompt: prompt, pool: pool) != nil else {
            let people = guests.partyHub.participants()
            showMessage(kind == .compliments && !people.isEmpty ? L("play.need_two") : L("play.need_names"))
            return false
        }
        guests.refreshPlay()
        if path.last != .play { path.append(.play) }
        return true
    }

    func playReveal() {
        guests.partyPlay.reveal()
        guests.refreshPlay()
    }

    func playVoting() {
        guests.partyPlay.startVoting()
        guests.refreshPlay()
    }

    /// Stesso gioco, nuovo turno (nuova parola, nuova talpa, nuova star).
    func playAgain() {
        guard let kind = guests.play?.kind else { return }
        startPlay(kind)
    }

    func stopPlay() {
        guests.partyPlay.stop()
        guests.refreshPlay()
    }

    /// Foto degli invitati arrivate nel gioco in corso (missioni, paparazzi, stesso istante, ricrea).
    func playPhotos(round: String) -> [Photo] {
        let byID = Dictionary(guestPhotos.compactMap { photo -> (String, Photo)? in
            if case let .guest(id) = photo.source { return (id, photo) }
            return nil
        }, uniquingKeysWith: { first, _ in first })
        return guests.partyPlay.taggedPhotos(round: round).compactMap { byID[$0] }
    }

    /// Didascalia della foto nello slideshow se è nata da un gioco ("Missione: un brindisi").
    func photoTag(_ photo: Photo) -> String? {
        guard case let .guest(id) = photo.source, let tag = guests.partyPlay.tag(id) else { return nil }
        switch tag.kind {
        case .missions: return tag.text.map { L("tag.mission", $0) }
        case .paparazzi: return tag.text.map { L("tag.paparazzi", $0) }
        case .sameMoment: return L("tag.moment")
        case .recreate: return L("tag.recreate")
        default: return nil
        }
    }

    /// Caccia al QR: il codice compare per trenta secondi sullo schermo.
    func startTreasure() {
        guard guests.active else { return }
        guests.partyPlay.startTreasure()
        guests.refreshPlay()
    }

    /// Indirizzo del QR della caccia in corso.
    var treasureURL: String? {
        guard let token = guests.treasure, let url = guests.url else { return nil }
        return url + "&t=" + token
    }

    func setLights(_ lights: PartyPlay.Lights?) {
        guests.partyPlay.setLights(lights)
        guests.refreshPlay()
    }

    /// Scommessa decisa: chi ha scelto `choice` vince tre monete. Restituisce quanti hanno vinto.
    func settleBet(_ choice: Int) -> Int {
        let winners = guests.phoneGame.voters(choice)
        for player in winners { guests.partyPlay.award(player, amount: Self.betCoins) }
        guests.phoneGame.stop()
        guests.refreshPlay()
        return winners.count
    }

    /// Roulette dei brindisi: il nome estratto compare anche sui telefoni come annuncio.
    func announceToast(_ name: String) {
        guests.announce(L("toast.winner", name))
    }

    /// Regista per due minuti: un invitato con il nome dirige lo slideshow dal suo telefono.
    func startDirector() {
        guard let director = guests.startDirector() else {
            showMessage(L("message.director_none"))
            return
        }
        play()
        showMessage(L("message.director_started", director.name))
    }

    /// Chi può vincere all'estrazione: nomi scritti con foto, giochi, reazioni e dediche.
    func lotteryCandidates() -> [String] {
        PrizeDraw.candidates(
            guests.partyHub.participants().map(\.name),
            guests.phoneGame.names(),
            guests.photos.filter(\.approved).sorted { $0.receivedAt < $1.receivedAt }.map(\.sender)
        )
    }

    func endGuestParty() {
        quietTask?.cancel()
        showCounts = [:]
        guests.end()
        freshPhotos.clear()
        knownGuestIDs = nil
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

    /// Aggiorna la raccolta mostrata. Le foto approvate da poco (arrivate o approvate dopo la ripresa
    /// della festa) passano davanti nello slideshow; quelle riprese da una festa interrotta no. Non si
    /// usa l'orologio del dispositivo, che può cambiare all'improvviso.
    private func guestsChanged() {
        let all = guests.photos
        let approved = all.filter(\.approved)
        let clips = guests.videos
        if guests.ready, let known = knownGuestIDs {
            autoPrint(approved.filter { !known.contains($0.id) })
            freshPhotos.add(approved.filter { !known.contains($0.id) }.map { "guest:\($0.id)" } +
                            clips.filter { !known.contains($0.id) }.map { "guestvideo:\($0.id)" })
        }
        knownGuestIDs = guests.ready ? Set(approved.map(\.id) + clips.map(\.id)) : nil
        // Foto e video degli invitati insieme, nell'ordine di arrivo.
        let items = (approved.map(Self.photo(for:)) + clips.map(Self.video(for:)))
            .sorted { ($0.receivedAt ?? .distantPast) < ($1.receivedAt ?? .distantPast) }
        if items != guestPhotos || guests.active != guestPartyActive || guests.developed != guestsDeveloped {
            guestPhotos = items
            guestsDeveloped = guests.developed
            guestPartyActive = guests.active
            applyCollection(restart: false)
        }
        // Approvate o scartate tutte: si torna alla schermata della festa.
        if path.last == .guestReview, all.allSatisfy(\.approved) { path.removeLast() }
        syncDevices()
    }

    /// Stampa e scontrino automatici per le foto nuove degli invitati (fino al limite della festa).
    private func autoPrint(_ fresh: [GuestPhoto]) {
        guard settings.autoPrint || settings.autoReceipt, !fresh.isEmpty else { return }
        let title = guests.setup.brand.name.isEmpty ? partyTitle : guests.setup.brand.name
        for guest in fresh {
            let photo = Self.photo(for: guest)
            Task { [devices, settings] in
                if settings.autoPrint { _ = await devices.print(photo, automatic: true) }
                if settings.autoReceipt { _ = await devices.receipt(photo, title: title, automatic: true) }
            }
        }
    }

    /// Server FTP per le fotocamere Wi‑Fi: acceso durante la festa, se scelto.
    func syncDevices() {
        let wanted = guests.active && settings.ftpServer
        if wanted, !devices.ftpActive {
            let party = guests
            devices.startFTP { _, data in
                guard GuestImageFormat.sniff(data) == .jpeg else { return }
                party.addCameraPhoto(data, sender: L("devices.camera_sender"))
            }
        } else if !wanted, devices.ftpActive {
            devices.stopFTP()
        }
        if !guests.active, devices.printed > 0 { devices.resetPrintCount() }
        #if os(macOS)
        let party = guests
        let add: (Data) -> Void = { data in party.addCameraPhoto(data, sender: L("devices.camera_sender")) }
        usbCameras.onPhoto = add
        hotFolder.onPhoto = add
        if guests.active, usbCameraEnabled { usbCameras.start() } else if usbCameras.running { usbCameras.stop() }
        if guests.active { hotFolder.start() } else { hotFolder.stop() }
        #endif
    }

    #if os(macOS)
    func setUSBCamera(_ enabled: Bool) {
        usbCameraEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "usbCamera")
        syncDevices()
    }
    #endif

    private static func video(for video: GuestVideoStore.Video) -> Photo {
        Photo(
            id: "guestvideo:\(video.id)",
            title: video.sender.map { L("guest.video_from", $0) } ?? L("guest.video"),
            source: .guestVideo(id: video.id),
            receivedAt: video.receivedAt,
            sender: video.sender,
            isVideo: true
        )
    }

    private static func photo(for guest: GuestPhoto) -> Photo {
        Photo(
            id: "guest:\(guest.id)",
            title: guest.sender.map { L("guest.photo_from", $0) } ?? L("guest.photo"),
            source: .guest(id: guest.id),
            receivedAt: guest.receivedAt,
            sender: guest.sender
        )
    }

    // MARK: - Interni

    /// Sostituisce la raccolta scelta ripartendo dalla prima foto dell'ordine di riproduzione.
    private func show(_ newPhotos: [Photo], label: String, isDemo: Bool, source: CollectionSource? = nil, title: String? = nil) {
        basePhotos = newPhotos
        baseLabel = label
        baseIsDemo = isDemo
        baseSource = source ?? (isDemo ? .demo : .google)
        baseTitle = title ?? (isDemo ? L("collection.demo") : L("home.google_title"))
        // Fuori dalla libreria Foto non serve sapere quando cambia.
        if baseSource != .library {
            libraryReload?.cancel()
            libraryWatcher = nil
        }
        applyCollection(restart: true)
    }

    /// Ricalcola la raccolta mostrata. Con `restart` riparte dall'inizio; altrimenti resta sulla foto
    /// corrente (arrivano o spariscono foto degli invitati mentre lo slideshow va avanti).
    private func applyCollection(restart: Bool) {
        let base = settings.videos ? basePhotos : basePhotos.filter { !$0.isVideo }
        // Il rullino usa e getta non ancora sviluppato tiene nascoste le foto degli invitati.
        let visibleGuests = guests.developed ? guestPhotos : []
        let newPhotos = combineWithGuests(base: base, baseIsDemo: baseIsDemo, guests: visibleGuests, partyActive: guestPartyActive)
        let oldPhotos = photos
        let currentID = currentPhoto?.id
        let index: Int
        if restart {
            index = settings.shuffle && !newPhotos.isEmpty ? Int.random(in: 0..<newPhotos.count) : 0
            order.reset(count: newPhotos.count, shuffle: settings.shuffle, current: index)
            slideTick += 1
        } else {
            if let currentID, let same = newPhotos.firstIndex(where: { $0.id == currentID }) {
                index = same
            } else {
                index = max(min(currentIndex, newPhotos.count - 1), 0)
            }
            // L'ordine del giro corrente resta: le foto nuove si inseriscono tra quelle non ancora viste.
            var newIndexByID: [String: Int] = [:]
            for (position, photo) in newPhotos.enumerated() { newIndexByID[photo.id] = position }
            order.remap(count: newPhotos.count, current: index) { old in
                oldPhotos.indices.contains(old) ? newIndexByID[oldPhotos[old].id] : nil
            }
            // La foto mostrata è sparita (ad esempio scartata): quella al suo posto conta come un cambio.
            let shownID = newPhotos.indices.contains(index) ? newPhotos[index].id : nil
            if shownID != currentID { slideTick += 1 }
        }
        photos = newPhotos
        currentIndex = index
        sourceLabel = collectionLabel()
        updateCollectionInfo()
    }

    private func updateCollectionInfo() {
        // Con le foto demo e la festa in corso si mostrano solo le foto degli invitati.
        if baseIsDemo, guestPartyActive || !guestPhotos.isEmpty {
            collectionSource = .guests
            collectionTitle = L("home.guests_title")
            collectionCount = guestPhotos.count
        } else {
            collectionSource = baseSource
            collectionTitle = baseTitle
            collectionCount = basePhotos.count
        }
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
            case .noNetwork: return L("error.google_no_network")
            case .consentFailed: return L("error.google_consent_failed")
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
