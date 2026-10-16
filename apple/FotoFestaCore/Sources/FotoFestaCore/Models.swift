import Foundation

/// Origine del file di una foto.
public enum PhotoSource: Codable, Hashable, Sendable {
    /// Risorsa inclusa nell'app (nome del file con estensione nel bundle principale).
    case bundled(fileName: String)
    /// Foto di Google Foto caricata al momento da Google: sul dispositivo non ne resta una copia.
    case google(mediaID: String)
    /// Foto mandata da un invitato, tenuta da GuestPhotoStore solo per la durata della festa.
    case guest(id: String)
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
    case fade, slide, zoom
}

/// Come una foto riempie lo schermo. Con `blur` la foto è intera e le bande ai lati (foto verticali o
/// non 16:9) mostrano la stessa foto ingrandita e sfocata.
public enum FitMode: String, Codable, CaseIterable, Sendable {
    case blur, contain, crop
}

/// Disposizione dello slideshow: una foto alla volta, o la foto nuova in grande con accanto le precedenti.
public enum SlideshowLayout: String, Codable, CaseIterable, Sendable {
    case single, mosaic
}

/// Impostazioni dello slideshow, salvate in UserDefaults come JSON.
/// La decodifica tollera chiavi mancanti o valori sconosciuti, così gli aggiornamenti non azzerano le scelte.
public struct SlideshowSettings: Codable, Equatable, Sendable {
    public static let intervalOptions = [3, 5, 8, 12, 20]
    public static let sleepTimerOptions = [30, 60, 120]

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

    public init() {}

    private enum CodingKeys: String, CodingKey {
        case intervalSeconds, transition, fitMode, layout, shuffle, reducedMotion, showClock, kenBurns, sleepTimerMinutes
        case countdown, countdownMinutes, countdownEvent, videos, videoSound
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
    }

    /// Il movimento lento è attivo solo se non è stata chiesta la riduzione del movimento.
    public var isKenBurnsActive: Bool { kenBurns && !reducedMotion }
}

/// Errori mostrati all'utente; l'app li traduce in testi localizzati.
public enum FotoFestaError: Error, Equatable, Sendable {
    case notConfigured
    case accessDenied
    case codeExpired
    case selectionExpired
    case sessionExpired
    case nothingSelected
    /// La sessione Picker è scaduta o non è più accessibile: le foto vanno scelte di nuovo.
    case selectionGone
    /// La foto non è più nella selezione restituita da Google.
    case photoUnavailable
}
