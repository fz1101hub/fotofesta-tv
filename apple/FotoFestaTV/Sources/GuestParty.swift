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

/// Quante foto può mandare ogni invitato: senza limite, una sola, o il rullino usa e getta.
enum PhotoLimit: String, CaseIterable {
    case unlimited, one, disposable

    var perGuest: Int {
        switch self {
        case .unlimited: 0
        case .one: 1
        case .disposable: PartyHub.disposableShots
        }
    }

    var label: String {
        switch self {
        case .unlimited: L("photo_limit.none")
        case .one: L("photo_limit.one")
        case .disposable: L("photo_limit.disposable")
        }
    }
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

    /// Video brevi degli invitati: al massimo 1 GB (e il 10% dello spazio libero) per festa.
    static let videos: GuestVideoStore = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return GuestVideoStore(directory: caches.appendingPathComponent("guest_videos", isDirectory: true), maxTotalBytes: 1024 * 1024 * 1024)
    }()

    /// Preparazione della festa. Sul Mac resta in Application Support; tvOS non ha uno spazio
    /// permanente per le app, quindi lì sta nella cache (il sistema può svuotarla).
    static let setup: PartySetup = {
        #if os(macOS)
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FotoFesta", isDirectory: true)
        #else
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        #endif
        return PartySetup(directory: base.appendingPathComponent("party_setup", isDirectory: true))
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
    /// Ultimo annuncio della regia e invitato che dirige lo slideshow, per lo schermo.
    private(set) var announcement: PartyHub.Announcement?
    private(set) var director: PartyHub.Director?
    /// Chi organizza comanda la festa dal telefono con il PIN mostrato sulla TV.
    private(set) var hostEnabled: Bool
    /// PIN della regia dal telefono; nil se la regia è spenta.
    private(set) var hostPIN: String?
    /// Limite di foto per invitato; con il rullino le foto si vedono dopo lo sviluppo.
    private(set) var photoLimit: PhotoLimit
    /// Le foto ingialliscono a ogni passaggio; le reazioni le ringiovaniscono.
    private(set) var agingPhotos: Bool
    /// Ogni foto degli invitati si mostra una volta sola e poi si cancella.
    private(set) var noTraces: Bool
    /// Ogni foto va subito anche su disco: se l'app si chiude, la festa riprende.
    private(set) var resumeParty: Bool
    /// Copia delle foto degli invitati nella cartella Immagini (solo Mac).
    private(set) var backupCopy: Bool
    /// Fine dei tre minuti senza telefoni; nil se non sono in corso.
    private(set) var quietUntil: Date?
    /// Rullino usa e getta già sviluppato (o nessun rullino).
    private(set) var developed = true
    /// Scatti usati con il limite a testa o il rullino.
    private(set) var shotsTaken = 0
    /// Stato dello schermo per la pagina di regia: lo fornisce il modello dell'app.
    @ObservationIgnored var hostStatus: (() -> HostRemote.Status)?

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
        static let host = "guests.host"
        static let photoLimit = "guests.photoLimit"
        static let agingPhotos = "guests.agingPhotos"
        static let noTraces = "guests.noTraces"
        static let resumeParty = "guests.resumeParty"
        static let backupCopy = "guests.backupCopy"
        static let songs = "guests.songs"
        static let album = "guests.album"
        static let videos = "guests.videos"
    }

    /// Dediche, nomi, reazioni e scatti, per riprendere la festa dopo un'interruzione.
    private nonisolated static let hubFile = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("party_activities.json")

    /// Gioco con i telefoni, servito dalla stessa pagina della festa.
    let phoneGame = PhoneGame()
    /// Reazioni e dediche dei telefoni: si cancellano con le foto a fine festa.
    let partyHub: PartyHub
    /// Giochi con i telefoni oltre ai quiz: disegni, missioni, talpa, caccia al QR, monete.
    let partyPlay: PartyPlay
    /// Gioco in corso, caccia al QR, luci e monete, per lo schermo.
    private(set) var play: PartyPlay.State?
    private(set) var treasure: String?
    private(set) var treasureFinders: [String] = []
    private(set) var lights: PartyPlay.Lights?
    private(set) var coins: [PartyPlay.Coins] = []
    @ObservationIgnored private var playRevision = -1
    /// Regia dal telefono: PIN nuovo a ogni festa.
    let hostRemote = HostRemote()
    /// Video brevi degli invitati, su disco per la durata della festa.
    let videoStore = GuestStorage.videos
    let phoneWall: PhoneWall
    /// Nome, colore, logo, sponsor, scaletta, invitati attesi, quiz e foto da piccoli: restano tra una festa e l'altra.
    let partySetup = GuestStorage.setup
    /// Pagine e funzioni in più del server (ingresso, parete, album, video, muro di telefoni).
    let pages: PartyPages
    private(set) var songsEnabled: Bool
    private(set) var albumEnabled: Bool
    private(set) var videosEnabled: Bool
    private(set) var videos: [GuestVideoStore.Video] = []
    private(set) var songs: [PartyHub.SongRequest] = []
    private(set) var arrivals: [PartyHub.Arrival] = []
    private(set) var phoneWallState: PhoneWall.State?
    private(set) var setup = PartySetup.State()
    /// Un invitato appena arrivato dal QR dell'ingresso: la TV gli dà il benvenuto.
    @ObservationIgnored var onArrival: ((PartyHub.Arrival) -> Void)?
    /// Una canzone appena chiesta: chi fa la musica la vede anche sulla TV.
    @ObservationIgnored var onSong: ((PartyHub.SongRequest) -> Void)?
    /// Un annuncio nuovo della regia o della scaletta: si può leggere ad alta voce.
    @ObservationIgnored var onAnnouncement: ((PartyHub.Announcement) -> Void)?
    @ObservationIgnored private var wallRevision = -1
    @ObservationIgnored private var setupRevision = -1
    @ObservationIgnored private var messageRevision = -1
    @ObservationIgnored private var messagePolling: Task<Void, Never>?
    @ObservationIgnored private var savedState: (revision: Int, reactions: Int)?
    @ObservationIgnored private let backupFlag = LockedFlag()

    init() {
        let sharedHub = PartyHub()
        partyHub = sharedHub
        partyPlay = PartyPlay(hub: sharedHub)
        let wall = PhoneWall()
        phoneWall = wall
        pages = PartyPages(store: GuestStorage.store, videos: GuestStorage.videos, hub: sharedHub, phoneWall: wall, setup: GuestStorage.setup)
        let defaults = UserDefaults.standard
        songsEnabled = defaults.object(forKey: Keys.songs) as? Bool ?? true
        albumEnabled = defaults.object(forKey: Keys.album) as? Bool ?? true
        videosEnabled = defaults.object(forKey: Keys.videos) as? Bool ?? true
        active = defaults.bool(forKey: Keys.active)
        approval = defaults.bool(forKey: Keys.approval)
        qrInSlideshow = defaults.object(forKey: Keys.qrInSlideshow) as? Bool ?? true
        reactionsEnabled = defaults.object(forKey: Keys.reactions) as? Bool ?? true
        messagesEnabled = defaults.object(forKey: Keys.messages) as? Bool ?? true
        hostEnabled = defaults.object(forKey: Keys.host) as? Bool ?? true
        photoLimit = defaults.string(forKey: Keys.photoLimit).flatMap(PhotoLimit.init(rawValue:)) ?? .unlimited
        agingPhotos = defaults.bool(forKey: Keys.agingPhotos)
        noTraces = defaults.bool(forKey: Keys.noTraces)
        resumeParty = defaults.object(forKey: Keys.resumeParty) as? Bool ?? true
        backupCopy = defaults.bool(forKey: Keys.backupCopy)
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
        syncPartyRules()
        sharedHub.setOnArrival { [weak self] arrival in
            Task { @MainActor [weak self] in
                self?.refreshMessages()
                self?.onArrival?(arrival)
            }
        }
        sharedHub.setOnSong { [weak self] song in
            Task { @MainActor [weak self] in
                self?.refreshMessages()
                self?.onSong?(song)
            }
        }
        GuestStorage.videos.setOnChange { [weak self] in
            Task { @MainActor [weak self] in self?.videosChanged() }
        }
        hostRemote.setEnabled(hostEnabled)
        hostPIN = hostRemote.currentPIN()
        // Dediche, annunci e regista arrivano dalla coda del server: si controlla una volta al secondo
        // se sono cambiati, e intanto si aggiorna lo stato per la pagina di regia.
        messagePolling = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.refreshMessages()
                self.refreshPlay()
                self.refreshWallAndSetup()
                self.saveHubIfChanged()
                if let status = self.hostStatus?() { self.hostRemote.updateStatus(status) }
                // Mezzo secondo: il disegno di "Disegna e indovina" deve crescere quasi dal vivo.
                try? await Task.sleep(for: .milliseconds(500))
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
        let hub = partyHub
        Task.detached(priority: .utility) { [weak self] in
            if wasActive {
                store.restore(maxAge: idleLimit)
                GuestStorage.videos.restore(maxAge: idleLimit)
                let newest = store.photos.map(\.receivedAt.timeIntervalSince1970).max() ?? 0
                if Date().timeIntervalSince1970 - max(startedAt, newest) > idleLimit {
                    await self?.end()
                } else if let saved = try? Data(contentsOf: Self.hubFile) {
                    // Dediche, nomi, reazioni e scatti del rullino della festa interrotta.
                    hub.restore(saved)
                }
            } else {
                store.clear()
                GuestStorage.videos.clear()
            }
            await self?.markReady()
        }
    }

    // MARK: - Azioni

    /// Inizia a ricevere foto: il QR resta lo stesso finché la festa non finisce.
    func start() {
        guard !active else { return }
        hostRemote.reset()
        hostPIN = hostRemote.currentPIN()
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
        videoStore.clear()
        phoneWall.stop()
        defaults.set(false, forKey: Keys.active)
        defaults.removeObject(forKey: Keys.secret)
        defaults.removeObject(forKey: Keys.startedAt)
        active = false
        problem = nil
        // Subito, senza aspettare la notifica dell'archivio: la raccolta non deve usare foto cancellate.
        photos = []
        videos = []
        partyHub.clear()
        partyPlay.clear()
        refreshPlay()
        try? FileManager.default.removeItem(at: Self.hubFile)
        savedState = nil
        hostRemote.reset()
        hostPIN = hostRemote.currentPIN()
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

    func setHostEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.host)
        hostEnabled = enabled
        hostRemote.setEnabled(enabled)
        hostPIN = hostRemote.currentPIN()
    }

    /// Annuncio dalla TV.
    @discardableResult
    func announce(_ text: String) -> Bool {
        let shown = partyHub.announce(text)
        refreshMessages()
        return shown
    }

    /// Regista per due minuti; nil se nessun invitato ha ancora scritto il proprio nome.
    func startDirector() -> PartyHub.Director? {
        let chosen = partyHub.startDirector()
        refreshMessages()
        return chosen
    }

    func setPhotoLimit(_ value: PhotoLimit) {
        defaults.set(value.rawValue, forKey: Keys.photoLimit)
        photoLimit = value
        syncPartyRules()
        refreshMessages()
    }

    func setAgingPhotos(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.agingPhotos)
        agingPhotos = enabled
    }

    func setNoTraces(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.noTraces)
        noTraces = enabled
        syncPartyRules()
        syncPartyFeatures()
    }

    func setResumeParty(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.resumeParty)
        resumeParty = enabled
        syncPartyRules()
    }

    func setBackupCopy(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.backupCopy)
        backupCopy = enabled
        syncPartyRules()
    }

    /// Sviluppo del rullino usa e getta: le foto scattate finora compaiono.
    func develop() {
        partyHub.develop()
        refreshMessages()
    }

    /// Tre minuti senza telefoni (o la loro fine, in anticipo).
    func startQuiet() {
        partyHub.startQuiet()
        refreshMessages()
    }

    func stopQuiet() {
        partyHub.stopQuiet()
        refreshMessages()
    }

    /// Foto di un telefono simulato durante la prova generale.
    func addRehearsalPhoto(_ data: Data, sender: String) {
        guard active else { return }
        let store = self.store
        let approved = !approval
        Task.detached(priority: .utility) { _ = store.add(data, sender: sender, approved: approved) }
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

    func setSongsEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.songs)
        songsEnabled = enabled
        syncPartyFeatures()
    }

    func setAlbumEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.album)
        albumEnabled = enabled
        syncPartyFeatures()
    }

    func setVideosEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Keys.videos)
        videosEnabled = enabled
        syncPartyFeatures()
    }

    /// Canzone suonata o scartata.
    func songDone(_ id: String) {
        partyHub.removeSong(id)
        refreshMessages()
    }

    /// Video tolto da chi organizza.
    func removeVideo(_ id: String) {
        let videos = videoStore
        Task.detached(priority: .userInitiated) { videos.remove(id) }
    }

    // MARK: - Muro di telefoni

    func startPhoneWall() {
        phoneWall.start()
        refreshWallAndSetup()
    }

    @discardableResult
    func showOnPhoneWall(_ id: String) -> Bool {
        let shown = phoneWall.show(id)
        refreshWallAndSetup()
        return shown
    }

    func rearrangePhoneWall() {
        phoneWall.rearrange()
        refreshWallAndSetup()
    }

    func phoneWallFinale() {
        phoneWall.finale()
        refreshWallAndSetup()
    }

    func stopPhoneWall() {
        phoneWall.stop()
        refreshWallAndSetup()
    }

    /// Con l'approvazione delle foto anche le dediche aspettano il via libera; i video no (non c'è
    /// un modo per approvarli), quindi con l'approvazione non si accettano.
    private func syncPartyFeatures() {
        // Con la festa senza tracce niente album da portare a casa e niente video.
        partyHub.setFeatures(PartyHub.Features(reactions: reactionsEnabled, messages: messagesEnabled, moderateMessages: approval,
                                               songs: songsEnabled, album: albumEnabled && !noTraces, videos: videosEnabled && !approval && !noTraces))
        pages.albumEnabled.value = albumEnabled && !noTraces
        pages.videosEnabled.value = videosEnabled && !noTraces
        pages.approval.value = approval
    }

    /// Muro di telefoni e preparazione della festa: si rileggono solo quando sono cambiati.
    func refreshWallAndSetup() {
        let wall = phoneWall.currentRevision()
        if wall != wallRevision {
            wallRevision = wall
            phoneWallState = phoneWall.state()
        }
        let revision = partySetup.currentRevision()
        if revision != setupRevision {
            setupRevision = revision
            setup = partySetup.state
        }
    }

    private func videosChanged() {
        videos = videoStore.videos
        onChange?()
    }

    /// Regole delle foto degli invitati, ripresa della festa e copia nella cartella Immagini.
    private func syncPartyRules() {
        partyHub.setPhotoRules(PartyHub.PhotoRules(perGuest: photoLimit.perGuest, disposable: photoLimit == .disposable))
        store.setDurable(resumeParty && !noTraces)
        #if os(macOS)
        backupFlag.value = backupCopy && !noTraces
        #endif
    }

    /// Giochi con i telefoni: si rilegge lo stato solo quando è cambiato.
    func refreshPlay() {
        let revision = partyPlay.currentRevision()
        // La caccia al QR scade da sola: senza cambi si ricontrolla comunque il codice.
        let currentTreasure = partyPlay.currentTreasure()
        if currentTreasure != treasure { treasure = currentTreasure }
        guard revision != playRevision else { return }
        playRevision = revision
        play = partyPlay.current()
        treasureFinders = partyPlay.treasureFinders()
        lights = partyPlay.currentLights()
        coins = partyPlay.leaderboard()
    }

    private func refreshMessages() {
        let revision = partyHub.currentRevision()
        guard revision != messageRevision else { return }
        messageRevision = revision
        messages = partyHub.approvedMessages()
        pendingMessages = partyHub.pendingMessages()
        let previousAnnouncement = announcement?.serial
        announcement = partyHub.currentAnnouncement()
        if let announcement, announcement.serial != previousAnnouncement { onAnnouncement?(announcement) }
        director = partyHub.currentDirector()
        let quietLeft = partyHub.quietTimeLeft()
        quietUntil = quietLeft > 0 ? Date().addingTimeInterval(quietLeft) : nil
        shotsTaken = partyHub.photosTaken()
        songs = partyHub.songRequests()
        arrivals = partyHub.allArrivals()
        let wasDeveloped = developed
        developed = partyHub.isDeveloped()
        // Il rullino appena sviluppato (o la regola cambiata) cambia le foto in mostra.
        if wasDeveloped != developed { onChange?() }
    }

    /// Salva lo stato delle attività quando è cambiato, al massimo una volta al secondo.
    private func saveHubIfChanged() {
        guard active else { return }
        let current = (revision: partyHub.currentRevision(), reactions: partyHub.lastReactionSerial())
        if let savedState, savedState == current { return }
        savedState = current
        let hub = partyHub
        Task.detached(priority: .utility) {
            try? hub.snapshot().write(to: Self.hubFile, options: .atomic)
        }
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
        videos = videoStore.videos
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
            onFailure: { [weak self] in
                Task { @MainActor [weak self] in
                    if let server = failure.server { self?.serverFailed(server) }
                }
            },
            game: phoneGame,
            hub: partyHub,
            host: hostRemote,
            hostPhoto: { [store] id in store.read(id) },
            play: partyPlay,
            onUpload: makePhotoHandler(),
            extras: pages
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
                "\"maxVideoBytes\":\(GuestUploadServer.maxVideoBytes),\"videoSeconds\":\(GuestUploadServer.videoSeconds)," +
                "\"approval\":\(flag.value)}"
            return template.replacingOccurrences(of: "__FOTOFESTA_CONFIG__", with: config)
        }
    }

    /// Chiamato dalla coda del server per ogni foto ricevuta.
    private func makePhotoHandler() -> @Sendable (Data, String?, String?) -> GuestUploadServer.Upload {
        let store = self.store
        let flag = approvalFlag
        let hub = partyHub
        let play = partyPlay
        let backup = backupFlag
        return { data, sender, player in
            guard GuestImageFormat.isDisplayable(data) else { return .init(.unsupported) }
            // Limite di foto a testa (una a testa, rullino) e tre minuti senza telefoni.
            switch hub.takePhoto(player: player) {
            case .quiet: return .init(.quiet)
            case .limit, .noPlayer: return .init(.limit)
            case .allowed: break
            }
            switch store.add(data, sender: sender, approved: !flag.value) {
            case let .added(photo):
                play.photoArrived(player: player, photoID: photo.id)
                if backup.value { Self.backup(data, sender: sender) }
                return .init(photo.approved ? .shown : .pending, photoID: photo.id)
            case .full:
                hub.returnPhoto(player: player)
                return .init(.full)
            case .tooManyPending:
                hub.returnPhoto(player: player)
                return .init(.tooManyPending)
            }
        }
    }

    /// Copia di una foto degli invitati in Immagini/FotoFesta/<data>, sul Mac.
    private nonisolated static func backup(_ data: Data, sender: String?) {
        let manager = FileManager.default
        guard let pictures = manager.urls(for: .picturesDirectory, in: .userDomainMask).first else { return }
        let now = Date()
        let day = now.formatted(.iso8601.year().month().day())
        let folder = pictures.appendingPathComponent("FotoFesta", isDirectory: true).appendingPathComponent(day, isDirectory: true)
        let suffix: String
        switch GuestImageFormat.sniff(data) {
        case .png: suffix = "png"
        case .webp: suffix = "webp"
        default: suffix = "jpg"
        }
        var name = "festa_\(Int(now.timeIntervalSince1970 * 1000))"
        if let sender {
            let clean = String(sender.filter { $0.isLetter || $0.isNumber }.prefix(20))
            if !clean.isEmpty { name += "_" + clean }
        }
        do {
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: folder.appendingPathComponent(name + "." + suffix), options: .atomic)
        } catch {
            // La copia è solo una sicurezza in più: la festa va avanti lo stesso.
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
