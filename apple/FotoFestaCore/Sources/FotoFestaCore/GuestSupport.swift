import Foundation
import ImageIO

/// Nome facoltativo con cui l'invitato firma le foto, ripulito prima di mostrarlo sulla TV.
public enum GuestSender {
    public static let maxLength = 40

    /// Toglie caratteri di controllo, invisibili o che cambiano la direzione del testo, compatta gli
    /// spazi e tiene al massimo `maxLength` caratteri Unicode. Nil se non resta nulla.
    public static func clean(_ value: String?) -> String? {
        clean(value, maxLength: maxLength)
    }

    /// Come `clean(_:)`, con un limite diverso (le dediche arrivano a 160 caratteri).
    public static func clean(_ value: String?, maxLength: Int) -> String? {
        guard let value else { return nil }
        var result = String.UnicodeScalarView()
        var count = 0
        var pendingSpace = false
        for scalar in value.unicodeScalars {
            if count >= maxLength { break }
            if scalar.properties.isWhitespace {
                pendingSpace = !result.isEmpty
                continue
            }
            switch scalar.properties.generalCategory {
            case .control, .format, .surrogate, .privateUse, .unassigned:
                continue
            default:
                break
            }
            if pendingSpace {
                result.append(" ")
                count += 1
                pendingSpace = false
                if count >= maxLength { break }
            }
            result.append(scalar)
            count += 1
        }
        let text = String(result)
        return text.isEmpty ? nil : text
    }
}

/// Formati accettati, riconosciuti dai primi byte e non dall'intestazione mandata dal telefono.
public enum GuestImageFormat: Equatable, Sendable {
    case jpeg, png, webp

    public static func sniff(_ data: Data) -> GuestImageFormat? {
        let bytes = [UInt8](data.prefix(12))
        if bytes.count >= 3, bytes[0] == 0xFF, bytes[1] == 0xD8, bytes[2] == 0xFF { return .jpeg }
        if bytes.count >= 8, bytes[0..<8] == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] { return .png }
        if bytes.count >= 12, bytes[0..<4] == Array("RIFF".utf8)[0..<4], bytes[8..<12] == Array("WEBP".utf8)[0..<4] { return .webp }
        return nil
    }

    /// Legge solo le dimensioni: un file danneggiato o enorme non arriva mai allo slideshow. Il tetto
    /// ai pixel è il doppio di uno schermo 4K: la pagina per i telefoni non manda mai di più.
    public static let maxImageSide = 8_192
    public static let maxImagePixels = 2 * 3840 * 2160

    public static func isDisplayable(_ data: Data, maxSide: Int = maxImageSide, maxPixels: Int = maxImagePixels) -> Bool {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { return false }
        return (1...maxSide).contains(width) && (1...maxSide).contains(height) && width * height <= maxPixels
    }
}

/// Rete Wi‑Fi di casa, per il QR che fa entrare gli invitati senza dettare la password.
public struct WifiNetwork: Equatable, Sendable {
    public enum Problem: Equatable, Sendable {
        case missingName, nameTooLong, badPassword
    }

    public static let maxSSIDBytes = 32
    public static let passwordLengths = 8...63

    public let ssid: String
    public let password: String

    public init(ssid: String, password: String) {
        self.ssid = ssid
        self.password = password
    }

    public var isOpen: Bool { password.isEmpty }

    /// Testo del QR nel formato riconosciuto dalle fotocamere di Android e iPhone:
    /// `WIFI:T:WPA;S:<nome>;P:<password>;;`. WPA vale anche per WPA2 e per le reti WPA3 in modalità mista.
    public var qrPayload: String {
        var text = "WIFI:" + (isOpen ? "T:nopass;" : "T:WPA;")
        text += "S:" + Self.escape(ssid) + ";"
        if !isOpen { text += "P:" + Self.escape(password) + ";" }
        return text + ";"
    }

    /// Errore da mostrare per i valori inseriti, oppure nil se la rete è valida.
    public static func validate(ssid: String, password: String) -> Problem? {
        if ssid.trimmingCharacters(in: .whitespaces).isEmpty { return .missingName }
        if ssid.utf8.count > maxSSIDBytes { return .nameTooLong }
        if !password.isEmpty, !passwordLengths.contains(password.count) { return .badPassword }
        return nil
    }

    /// Nel formato WIFI: i caratteri `\ ; , : "` vanno preceduti da una barra rovesciata.
    static func escape(_ value: String) -> String {
        var result = ""
        for character in value {
            if "\\;,:\"".contains(character) { result.append("\\") }
            result.append(character)
        }
        return result
    }
}

/// Raccolta mostrata: quella scelta più le foto approvate degli invitati, in coda. Con le sole foto
/// demo gli invitati le sostituiscono, così alla festa compaiono solo le foto vere; durante la festa
/// la raccolta può quindi essere vuota, e lo slideshow mostra il QR in attesa della prima foto.
public func combineWithGuests(base: [Photo], baseIsDemo: Bool, guests: [Photo], partyActive: Bool) -> [Photo] {
    if baseIsDemo, partyActive || !guests.isEmpty { return guests }
    return base + guests
}

/// Foto appena arrivate dagli invitati: lo slideshow le mostra subito dopo quella corrente, così chi
/// le ha mandate le vede entro pochi secondi, poi riprende dal punto in cui si era fermato.
public final class FreshPhotos {
    private let limit: Int
    private var queue: [String] = []
    private var resumeAfter: String?
    /// Foto nuove già mostrate da quando si è lasciato l'ordine normale.
    private var shownInRun: Set<String> = []

    public init(limit: Int = 30) {
        self.limit = limit
    }

    public func add(_ ids: [String]) {
        for id in ids where !queue.contains(id) { queue.append(id) }
        // Con un'ondata di foto, le più vecchie della coda tornano nell'ordine normale.
        if queue.count > limit { queue.removeFirst(queue.count - limit) }
    }

    public func clear() {
        queue = []
        resumeAfter = nil
        shownInRun = []
    }

    /// Indice della prossima foto nuova in `photos`, oppure nil se non ce ne sono.
    public func take(_ photos: [Photo], currentIndex: Int) -> Int? {
        while !queue.isEmpty {
            let id = queue.removeFirst()
            if let index = photos.firstIndex(where: { $0.id == id }), index != currentIndex {
                if resumeAfter == nil, photos.indices.contains(currentIndex) { resumeAfter = photos[currentIndex].id }
                shownInRun.insert(photos[index].id)
                return index
            }
        }
        return nil
    }

    /// Prossima foto nuova senza toglierla dalla coda, per il precaricamento.
    public func peek(_ photos: [Photo], currentIndex: Int) -> Int? {
        for id in queue {
            if let index = photos.firstIndex(where: { $0.id == id }), index != currentIndex { return index }
        }
        return nil
    }

    /// Foto da cui riprendere l'ordine normale dopo quelle nuove (per "Precedente"); la dimentica.
    public func takeResume(_ photos: [Photo]) -> Int? {
        guard let id = resumeAfter else { return nil }
        resumeAfter = nil
        shownInRun = []
        return photos.firstIndex { $0.id == id }
    }

    /// Foto successiva nell'ordine normale dopo una serie di foto nuove: riparte da quella mostrata
    /// prima e salta le foto nuove appena viste, che altrimenti ricomparirebbero subito. `next` è il
    /// passo dell'ordine di riproduzione. Nil se non si stava mostrando una serie di foto nuove.
    public func resume(_ photos: [Photo], next: (Int) -> Int) -> Int? {
        guard let id = resumeAfter else { return nil }
        resumeAfter = nil
        let shown = shownInRun
        shownInRun = []
        guard let start = photos.firstIndex(where: { $0.id == id }) else { return nil }
        var index = next(start)
        for _ in 0..<photos.count {
            if !photos.indices.contains(index) || !shown.contains(photos[index].id) { return index }
            index = next(index)
        }
        return index
    }

    public func peekResume(_ photos: [Photo]) -> Int? {
        resumeAfter.flatMap { id in photos.firstIndex { $0.id == id } }
    }
}
