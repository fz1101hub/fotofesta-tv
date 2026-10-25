import CoreGraphics
import CoreImage
import CryptoKit
import Foundation

/// Pagine e funzioni in più del server della festa, oltre a quella dei telefoni: il QR dell'ingresso
/// ("sono arrivato"), la parete delle foto per un secondo schermo, il muro di telefoni, l'album da
/// portare a casa e la preparazione della festa. Tutte con la stessa chiave del QR. Come `PartyExtras.kt`.
///
/// Chiamate dalla coda del server: le implementazioni devono essere thread-safe.
public protocol PartyExtras: AnyObject, Sendable {
    /// Pagina da stampare o da lasciare su un tablet all'ingresso, con il QR degli arrivi.
    func doorPage() -> String
    /// Parete delle foto: slideshow delle foto approvate degli invitati, dediche e QR.
    func wallPage() -> String
    /// Stato per la parete: ultime foto approvate, dediche, annuncio, titolo della festa.
    func wallState() -> String
    /// Foto approvata `id` per la parete; nil se non c'è o non è approvata.
    func wallPhoto(_ id: String) -> Data?
    /// Foto e video approvati da portare a casa; nil se chi organizza non lo permette.
    func album() -> [ZipStream.Entry]?
    /// Muro di telefoni per il telefono `player`, in JSON; "null" se è spento.
    func phoneWallJSON(player: String?) -> String
    /// Iscrizione al muro di telefoni: stato HTTP e risposta; nil se il dispositivo non lo offre.
    func phoneWallAction(_ form: [String: String]) -> (status: Int, json: String)?
    /// Foto o video tolto da chi l'ha mandato, con la ricevuta: false se non c'è più.
    func deleteByGuest(_ id: String) -> Bool
    /// Video breve di un invitato.
    func receiveVideo(_ data: Data, sender: String?, player: String?) -> GuestUploadServer.Upload
    /// Nome, colore, logo e scaletta della festa per la pagina dei telefoni, in JSON.
    func setupJSON() -> String
    /// Tutta la preparazione della festa per la regia, in JSON.
    func setupHostJSON() -> String
    func logo() -> Data?
    func sponsor(_ id: String) -> Data?
    /// Logo, sponsor o foto da piccolo (`name`) mandati dalla regia: false se non vanno bene.
    func hostUpload(kind: String, data: Data, name: String?) -> Bool
}

public extension PartyExtras {
    func album() -> [ZipStream.Entry]? { nil }
    func phoneWallJSON(player: String?) -> String { "null" }
    func phoneWallAction(_ form: [String: String]) -> (status: Int, json: String)? { nil }
    func deleteByGuest(_ id: String) -> Bool { false }
    func receiveVideo(_ data: Data, sender: String?, player: String?) -> GuestUploadServer.Upload { .init(.unsupported) }
    func setupJSON() -> String { "null" }
    func setupHostJSON() -> String { "null" }
    func logo() -> Data? { nil }
    func sponsor(_ id: String) -> Data? { nil }
    func hostUpload(kind: String, data: Data, name: String?) -> Bool { false }
}

/// Ricevuta di una foto: firma della chiave della festa, che il telefono non conosce in anticipo.
/// Con la ricevuta, chi ha mandato una foto la può togliere. Come su Android (HMAC-SHA256, 22 caratteri).
public enum GuestReceipt {
    public static let length = 22

    public static func make(key: String, id: String) -> String {
        let code = HMAC<SHA256>.authenticationCode(for: Data("photo:\(id)".utf8), using: SymmetricKey(data: Data(key.utf8)))
        let text = Data(code).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return String(text.prefix(length))
    }

    /// Confronto a tempo costante.
    public static func matches(key: String, id: String, receipt: String?) -> Bool {
        guard let receipt else { return false }
        let a = Array(make(key: key, id: id).utf8)
        let b = Array(receipt.utf8)
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for index in a.indices { difference |= a[index] ^ b[index] }
        return difference == 0
    }
}

/// QR in SVG, per le pagine servite ai telefoni e agli altri schermi (nessuna immagine da generare).
public enum QRSVG {
    public static func encode(_ value: String, margin: Int = 2) -> String? {
        guard let matrix = modules(value) else { return nil }
        let count = matrix.count
        let size = count + margin * 2
        var path = ""
        for y in 0..<count {
            var x = 0
            while x < count {
                guard matrix[y][x] else {
                    x += 1
                    continue
                }
                // Moduli neri consecutivi della riga in un solo rettangolo: l'SVG resta piccolo.
                let start = x
                while x < count, matrix[y][x] { x += 1 }
                path += "M\(start + margin) \(y + margin)h\(x - start)v1h-\(x - start)z"
            }
        }
        return #"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 \#(size) \#(size)" shape-rendering="crispEdges">"# +
            ##"<rect width="\##(size)" height="\##(size)" fill="#fff"/><path fill="#000" d="\##(path)"/></svg>"##
    }

    /// Moduli del QR (true = nero), riga per riga dall'alto, senza il margine bianco del generatore.
    static func modules(_ value: String) -> [[Bool]]? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        let width = image.width
        let height = image.height
        var pixels = [UInt8](repeating: 255, count: width * height)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
                                          bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        var rows = (0..<height).map { y in (0..<width).map { x in pixels[y * width + x] < 128 } }
        // Il generatore lascia un bordo bianco: si toglie, il margine lo decide `encode`.
        while let first = rows.first, !first.contains(true) { rows.removeFirst() }
        while let last = rows.last, !last.contains(true) { rows.removeLast() }
        guard !rows.isEmpty else { return nil }
        let left = rows.map { $0.firstIndex(of: true) ?? Int.max }.min() ?? 0
        let right = rows.map { $0.lastIndex(of: true) ?? -1 }.max() ?? 0
        return rows.map { Array($0[left...right]) }
    }
}
