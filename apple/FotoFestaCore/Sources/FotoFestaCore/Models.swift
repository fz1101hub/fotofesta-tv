import Foundation

/// Origine del file di una foto.
public enum PhotoSource: Codable, Hashable, Sendable {
    /// Risorsa inclusa nell'app (nome del file con estensione nel bundle principale).
    case bundled(fileName: String)
    /// Foto di Google Foto caricata al momento da Google: sul dispositivo non ne resta una copia.
    case google(mediaID: String)
    /// Foto mandata da un invitato, tenuta da GuestPhotoStore solo per la durata della festa.
    case guest(id: String)
    /// Video breve mandato da un invitato, tenuto da GuestVideoStore solo per la durata della festa.
    case guestVideo(id: String)
    /// Foto o video della libreria Foto del dispositivo (Foto di iCloud, album condivisi), letto con
    /// PhotoKit al momento: l'app non ne fa copie.
    case library(localIdentifier: String)
    /// Foto o video di un server multimediale (DLNA) della rete di casa, letto al momento in http.
    case network(url: String)
}

public struct Photo: Identifiable, Hashable, Codable, Sendable {
    public let id: String
    public let title: String
    public let source: PhotoSource
    /// Quando la foto è arrivata al dispositivo, per le foto degli invitati.
    public let receivedAt: Date?
    /// Nome con cui l'invitato ha firmato la foto, per le foto degli invitati.
    public let sender: String?
    /// È un video: nello slideshow si guarda fino alla fine; i giochi lo saltano.
    public let isVideo: Bool

    public init(id: String, title: String, source: PhotoSource, receivedAt: Date? = nil, sender: String? = nil, isVideo: Bool = false) {
        self.id = id
        self.title = title
        self.source = source
        self.receivedAt = receivedAt
        self.sender = sender
        self.isVideo = isVideo
    }
}

public enum TransitionStyle: String, Codable, CaseIterable, Sendable {
    /// `zapping`: un attimo di "neve" della TV e il numero del canale, come cambiando canale.
    case fade, slide, zoom, zapping
}

/// Come una foto riempie lo schermo. Con `blur` la foto è intera e le bande ai lati (foto verticali o
/// non 16:9) mostrano la stessa foto ingrandita e sfocata.
public enum FitMode: String, Codable, CaseIterable, Sendable {
    case blur, contain, crop
}

/// Disposizione dello slideshow: una foto alla volta, o la foto nuova in grande con accanto le precedenti.
public enum SlideshowLayout: String, Codable, CaseIterable, Sendable {
    /// `stack`: le foto cadono come polaroid su una pila, una sopra l'altra.
    case single, mosaic, stack
}

/// Filtro sulle foto dello slideshow.
public enum PhotoFilter: String, Codable, CaseIterable, Sendable {
    case none, blackWhite, sepia, warm, cool, vintage
}

/// Modelli di festa: un clic sceglie titolo, transizione, disposizione, filtro ed effetti adatti
/// all'occasione. Le altre impostazioni restano come sono.
public enum PartyTemplate: String, Codable, CaseIterable, Sendable {
    case none, birthday, wedding, newYear, graduation, kids, reunion

    public func apply(to settings: SlideshowSettings) -> SlideshowSettings {
        var result = settings
        result.template = self
        switch self {
        case .none:
            break
        case .birthday:
            result.transition = .zoom
            result.layout = .stack
            result.filter = .none
            result.confetti = true
            result.openingTitles = true
        case .wedding:
            result.transition = .fade
            result.layout = .single
            result.filter = .warm
            result.confetti = false
            result.openingTitles = true
            result.kenBurns = true
        case .newYear:
            result.transition = .zapping
            result.layout = .mosaic
            result.confetti = true
            result.countdown = true
            result.countdownMinutes = 0
            result.countdownEvent = .midnight
        case .graduation:
            result.transition = .slide
            result.layout = .single
            result.newsMode = true
            result.confetti = true
        case .kids:
            result.transition = .zoom
            result.layout = .stack
            result.filter = .none
            result.confetti = true
            result.newsMode = false
        case .reunion:
            result.transition = .fade
            result.layout = .stack
            result.filter = .vintage
            result.newsMode = true
        }
        return result
    }
}

/// Cornice dell'evento sullo slideshow, con nome e logo scelti dalla regia.
public enum FrameStyle: String, Codable, CaseIterable, Sendable {
    case none, band, border
}

/// Impostazioni dello slideshow, salvate in UserDefaults come JSON.
/// La decodifica tollera chiavi mancanti o valori sconosciuti, così gli aggiornamenti non azzerano le scelte.
public struct SlideshowSettings: Codable, Equatable, Sendable {
    public static let intervalOptions = [3, 5, 8, 12, 20]
    public static let sleepTimerOptions = [30, 60, 120]
    /// Uno sponsor ogni tante foto (0 = mai).
    public static let sponsorOptions = [0, 5, 10, 20]
    /// Margini dello schermo in percentuale, per i proiettori che tagliano i bordi.
    public static let marginOptions = [0, 2, 4, 6]
    /// Ora dell'azzeramento notturno (-1 = mai).
    public static let resetOptions = [-1, 2, 3, 4, 5, 6]

    public var intervalSeconds = 8
    public var transition: TransitionStyle = .fade
    public var fitMode: FitMode = .blur
    public var layout: SlideshowLayout = .single
    public var shuffle = false
    public var reducedMotion = false
    public var showClock = true
    public var kenBurns = false
    /// Minuti di riproduzione prima della pausa automatica; 0 = disattivata.
    public var sleepTimerMinutes = 0
    /// Conto alla rovescia nello slideshow verso `countdownMinutes` (minuti dalla mezzanotte, ora locale).
    public var countdown = false
    public var countdownMinutes = 0
    public var countdownEvent: CountdownEvent = .midnight
    /// Video della raccolta nello slideshow, insieme alle foto.
    public var videos = true
    /// Audio dei video.
    public var videoSound = true
    /// Filtro sulle foto dello slideshow.
    public var filter: PhotoFilter = .none
    /// Coriandoli quando arriva una foto nuova degli invitati o una reazione 🎉.
    public var confetti = false
    /// "TG della festa": titolo in sovrimpressione e notizie che scorrono.
    public var newsMode = false
    /// Sigla d'apertura all'avvio dello slideshow.
    public var openingTitles = false
    /// TV montata in verticale (totem): lo slideshow ruota di 90°.
    public var portrait = false
    /// Modello di festa scelto: dà il titolo a sigla, TG, titoli di coda e Televideo.
    public var template: PartyTemplate = .none
    /// Cornice dell'evento (nome e logo dalla regia).
    public var frameStyle: FrameStyle = .none
    /// Uno sponsor ogni `sponsorEvery` foto (0 = mai).
    public var sponsorEvery = 0
    /// Margini dello schermo in percentuale.
    public var screenMargin = 0
    /// Gli annunci della regia si leggono anche ad alta voce.
    public var speakAnnouncements = false
    /// Chiosco: impostazioni e fine della festa chiedono il PIN.
    public var kiosk = false
    /// All'apertura dell'app la festa parte da sola (locali, uso fisso).
    public var alwaysParty = false
    /// Ora in cui la festa si azzera ogni notte (-1 = mai).
    public var nightlyReset = -1
    /// Le foto molto larghe (panoramiche) scorrono da un lato all'altro.
    public var panoramas = true
    /// Ogni tanto arriva un messaggio scherzoso dalla "festa rivale".
    public var rivalParty = false
    /// Senza foto nuove dagli invitati per un po', lo schermo fa capire che si annoia.
    public var boredScreen = false
    /// Le luci collegate (Hue, WLED, Nanoleaf) prendono il colore della foto.
    public var partyLights = true
    /// Ogni foto nuova degli invitati si stampa (IPP).
    public var autoPrint = false
    /// Ogni foto nuova degli invitati esce come scontrino.
    public var autoReceipt = false
    /// Server FTP per le fotocamere con il Wi‑Fi durante la festa.
    public var ftpServer = false
    /// Live Photo della libreria Foto: si anima il breve video prima della foto.
    public var livePhotos = true
    /// Foto HDR con la luminosità piena sugli schermi che la supportano.
    public var hdrPhotos = false

    public init() {}

    private enum CodingKeys: String, CodingKey {
        case intervalSeconds, transition, fitMode, layout, shuffle, reducedMotion, showClock, kenBurns, sleepTimerMinutes
        case countdown, countdownMinutes, countdownEvent, videos, videoSound
        case filter, confetti, newsMode, openingTitles, portrait, template
        case frameStyle, sponsorEvery, screenMargin, speakAnnouncements, kiosk, alwaysParty, nightlyReset
        case panoramas, rivalParty, boredScreen, partyLights, autoPrint, autoReceipt, ftpServer, livePhotos, hdrPhotos
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // try? su un valore opzionale restituisce già un opzionale semplice (SE-0230).
        func value<T: Decodable>(_ type: T.Type, _ key: CodingKeys) -> T? {
            try? container.decodeIfPresent(type, forKey: key)
        }
        if let seconds = value(Int.self, .intervalSeconds) { intervalSeconds = min(max(seconds, 2), 120) }
        if let transition = value(TransitionStyle.self, .transition) { self.transition = transition }
        if let fitMode = value(FitMode.self, .fitMode) { self.fitMode = fitMode }
        if let layout = value(SlideshowLayout.self, .layout) { self.layout = layout }
        if let shuffle = value(Bool.self, .shuffle) { self.shuffle = shuffle }
        if let reducedMotion = value(Bool.self, .reducedMotion) { self.reducedMotion = reducedMotion }
        if let showClock = value(Bool.self, .showClock) { self.showClock = showClock }
        if let kenBurns = value(Bool.self, .kenBurns) { self.kenBurns = kenBurns }
        if let minutes = value(Int.self, .sleepTimerMinutes) { sleepTimerMinutes = min(max(minutes, 0), 240) }
        if let countdown = value(Bool.self, .countdown) { self.countdown = countdown }
        if let minutes = value(Int.self, .countdownMinutes), Countdown.timeOptions.contains(minutes) { countdownMinutes = minutes }
        if let event = value(CountdownEvent.self, .countdownEvent) { countdownEvent = event }
        if let videos = value(Bool.self, .videos) { self.videos = videos }
        if let videoSound = value(Bool.self, .videoSound) { self.videoSound = videoSound }
        if let filter = value(PhotoFilter.self, .filter) { self.filter = filter }
        if let confetti = value(Bool.self, .confetti) { self.confetti = confetti }
        if let newsMode = value(Bool.self, .newsMode) { self.newsMode = newsMode }
        if let openingTitles = value(Bool.self, .openingTitles) { self.openingTitles = openingTitles }
        if let portrait = value(Bool.self, .portrait) { self.portrait = portrait }
        if let template = value(PartyTemplate.self, .template) { self.template = template }
        if let style = value(FrameStyle.self, .frameStyle) { frameStyle = style }
        if let every = value(Int.self, .sponsorEvery), Self.sponsorOptions.contains(every) { sponsorEvery = every }
        if let margin = value(Int.self, .screenMargin), Self.marginOptions.contains(margin) { screenMargin = margin }
        if let speak = value(Bool.self, .speakAnnouncements) { speakAnnouncements = speak }
        if let kiosk = value(Bool.self, .kiosk) { self.kiosk = kiosk }
        if let always = value(Bool.self, .alwaysParty) { alwaysParty = always }
        if let hour = value(Int.self, .nightlyReset), Self.resetOptions.contains(hour) { nightlyReset = hour }
        if let panoramas = value(Bool.self, .panoramas) { self.panoramas = panoramas }
        if let rival = value(Bool.self, .rivalParty) { rivalParty = rival }
        if let bored = value(Bool.self, .boredScreen) { boredScreen = bored }
        if let lights = value(Bool.self, .partyLights) { partyLights = lights }
        if let print = value(Bool.self, .autoPrint) { autoPrint = print }
        if let receipt = value(Bool.self, .autoReceipt) { autoReceipt = receipt }
        if let ftp = value(Bool.self, .ftpServer) { ftpServer = ftp }
        if let live = value(Bool.self, .livePhotos) { livePhotos = live }
        if let hdr = value(Bool.self, .hdrPhotos) { hdrPhotos = hdr }
    }

    /// Il movimento lento è attivo solo se non è stata chiesta la riduzione del movimento.
    public var isKenBurnsActive: Bool { kenBurns && !reducedMotion }
}

/// Errori mostrati all'utente; l'app li traduce in testi localizzati.
public enum FotoFestaError: Error, Equatable, Sendable {
    case notConfigured
    case accessDenied
    /// Il telefono non ha completato l'autorizzazione entro il tempo della pagina.
    case codeExpired
    /// La TV non è in una rete di casa: il telefono non potrebbe raggiungere la sua pagina.
    case noNetwork
    /// Google ha rifiutato la richiesta di autorizzazione (client OAuth configurato male).
    case consentFailed
    case selectionExpired
    case sessionExpired
    case nothingSelected
    /// La sessione Picker è scaduta o non è più accessibile: le foto vanno scelte di nuovo.
    case selectionGone
    /// La foto non è più nella selezione restituita da Google.
    case photoUnavailable
}
