import Foundation
import Network

/// Scontrini della festa su una stampante termica di rete (ESC/POS, porta 9100): intestazione, la
/// foto in bianco e nero a puntini, chi l'ha mandata e l'ora. La foto arriva già in toni di grigio.
public enum EscPos {
    public static let port: UInt16 = 9100

    /// Larghezza in punti: 384 per i rotoli da 58 mm, 576 per quelli da 80 mm.
    public enum Paper: Int, Codable, CaseIterable, Sendable {
        case mm58 = 384, mm80 = 576
    }

    /// Foto in toni di grigio, un byte per punto (0 nero, 255 bianco), riga per riga.
    public struct Gray: Sendable {
        public let width: Int
        public let height: Int
        public let pixels: [UInt8]

        public init?(width: Int, height: Int, pixels: [UInt8]) {
            guard width > 0, height > 0, pixels.count == width * height else { return nil }
            self.width = width
            self.height = height
            self.pixels = pixels
        }
    }

    private static let esc: UInt8 = 0x1B
    private static let gs: UInt8 = 0x1D
    private static let lf: UInt8 = 0x0A

    /// Lo scontrino completo, da mandare così com'è alla stampante.
    public static func receipt(title: String, photo: Gray?, lines: [String], footer: String) -> Data {
        var out = Data([esc, 0x40])
        out += [esc, 0x61, 1] // centrato
        out += [gs, 0x21, 0x11] // doppia altezza e larghezza
        out += text(title)
        out += [lf, gs, 0x21, 0x00, lf]
        if let photo {
            out += raster(of: photo)
            out.append(lf)
        }
        for line in lines {
            out += text(line)
            out.append(lf)
        }
        out.append(lf)
        out += text(footer)
        out += [lf, lf, lf, lf]
        out += [gs, 0x56, 0x42, 0x00] // taglio parziale, dove c'è la taglierina
        return out
    }

    /// Floyd–Steinberg: true = punto nero. I bianchi restano puliti, i grigi diventano puntini.
    public static func dither(_ photo: Gray) -> [Bool] {
        let w = photo.width
        let h = photo.height
        var values = photo.pixels.map(Float.init)
        var black = [Bool](repeating: false, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let index = y * w + x
                let old = values[index]
                let new: Float = old < 128 ? 0 : 255
                black[index] = new == 0
                let error = old - new
                if x + 1 < w { values[index + 1] += error * 7 / 16 }
                if y + 1 < h {
                    if x > 0 { values[index + w - 1] += error * 3 / 16 }
                    values[index + w] += error * 5 / 16
                    if x + 1 < w { values[index + w + 1] += error / 16 }
                }
            }
        }
        return black
    }

    /// Immagine raster (GS v 0): righe di byte, un bit per punto, il più alto a sinistra.
    public static func raster(_ black: [Bool], width: Int, height: Int) -> Data {
        let bytesPerRow = (width + 7) / 8
        var out = Data([gs, 0x76, 0x30, 0x00, UInt8(bytesPerRow & 0xFF), UInt8(bytesPerRow >> 8 & 0xFF), UInt8(height & 0xFF), UInt8(height >> 8 & 0xFF)])
        out.reserveCapacity(out.count + bytesPerRow * height)
        for y in 0..<height {
            for column in 0..<bytesPerRow {
                var byte: UInt8 = 0
                for bit in 0..<8 {
                    let x = column * 8 + bit
                    if x < width, black[y * width + x] { byte |= 0x80 >> UInt8(bit) }
                }
                out.append(byte)
            }
        }
        return out
    }

    public static func raster(of photo: Gray) -> Data {
        raster(dither(photo), width: photo.width, height: photo.height)
    }

    /// Testo per la tabella di caratteri predefinita (PC437): le lettere accentate perdono l'accento
    /// invece di diventare simboli strani.
    public static func text(_ value: String) -> Data {
        let plain = value.folding(options: .diacriticInsensitive, locale: nil)
        return Data(plain.unicodeScalars.map { (0x20...0x7E).contains($0.value) ? UInt8($0.value) : UInt8(ascii: "?") })
    }

    /// Manda lo scontrino alla stampante, solo nella rete di casa.
    public static func send(_ bytes: Data, host: String, port: UInt16 = EscPos.port) async throws {
        guard LANAddress.isLocal(host), let endpointPort = NWEndpoint.Port(rawValue: port) else { throw URLError(.badURL) }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
        defer { connection.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let once = ResumeOnce(continuation)
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    connection.send(content: bytes, completion: .contentProcessed { error in
                        if let error { once.resume(throwing: error) } else { once.resume() }
                    })
                case let .failed(error), let .waiting(error):
                    once.resume(throwing: error)
                default:
                    break
                }
            }
            connection.start(queue: .global(qos: .utility))
            DispatchQueue.global().asyncAfter(deadline: .now() + 10) { once.resume(throwing: URLError(.timedOut)) }
        }
    }
}

/// Una continuazione che riprende una volta sola, qualunque evento arrivi prima.
final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?

    init(_ continuation: CheckedContinuation<Void, Error>) {
        self.continuation = continuation
    }

    func resume(throwing error: Error? = nil) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        if let error { pending?.resume(throwing: error) } else { pending?.resume() }
    }
}
