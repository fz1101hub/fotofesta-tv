import Foundation

/// Stampa delle foto su una stampante di casa con IPP (AirPrint parla IPP sulla porta 631), senza
/// la finestra di stampa del sistema: serve alla stampa automatica di ogni foto degli invitati.
public enum Ipp {
    public static let port = 631
    public static let serviceType = "_ipp._tcp"
    static let printJob: UInt16 = 0x0002
    static let getPrinterAttributes: UInt16 = 0x000B

    private static let operationTag: UInt8 = 0x01
    private static let jobTag: UInt8 = 0x02
    private static let endTag: UInt8 = 0x03
    private static let integerTag: UInt8 = 0x21
    private static let keywordTag: UInt8 = 0x44
    private static let uriTag: UInt8 = 0x45
    private static let charsetTag: UInt8 = 0x47
    private static let languageTag: UInt8 = 0x48
    private static let mimeTag: UInt8 = 0x49
    private static let nameTag: UInt8 = 0x42

    /// Stampante trovata o scritta a mano: `path` è la risorsa ("ipp/print").
    public struct Printer: Codable, Equatable, Hashable, Sendable {
        public let host: String
        public let port: Int
        public let path: String
        public let name: String

        public init(host: String, port: Int = Ipp.port, path: String = "ipp/print", name: String? = nil) {
            self.host = host
            self.port = port
            self.path = path
            self.name = name ?? host
        }

        private var hostPart: String { host.contains(":") ? "[\(host)]" : host }
        private var cleanPath: String { String(path.drop(while: { $0 == "/" })) }
        public var httpURL: String { "http://\(hostPart):\(port)/\(cleanPath)" }
        public var ippURI: String { "ipp://\(hostPart):\(port)/\(cleanPath)" }
    }

    public enum Value: Equatable, Sendable {
        case integer(Int32)
        case text(String)
        case bytes(Data)
    }

    public struct Attribute: Equatable, Sendable {
        public let tag: UInt8
        public let name: String
        public var values: [Value]
    }

    public struct Response: Equatable, Sendable {
        public let status: UInt16
        public let requestID: Int32
        public let attributes: [Attribute]

        public var ok: Bool { status <= 0x00FF }

        public func strings(_ name: String) -> [String] {
            attributes.filter { $0.name == name }.flatMap(\.values).compactMap { if case let .text(text) = $0 { text } else { nil } }
        }
    }

    public static func getPrinterAttributesRequest(_ printer: Printer, requestID: Int32) -> Data {
        var writer = Writer(operation: getPrinterAttributes, requestID: requestID)
        writer.operation(printer)
        writer.string(keywordTag, "requested-attributes", "document-format-supported")
        writer.tag(endTag)
        return writer.data
    }

    /// Lavoro di stampa con la foto in `document` (di solito JPEG) in `copies` copie.
    public static func printJobRequest(_ printer: Printer, requestID: Int32, jobName: String, format: String, document: Data, copies: Int = 1) -> Data {
        var writer = Writer(operation: printJob, requestID: requestID)
        writer.operation(printer)
        writer.string(nameTag, "job-name", String(jobName.prefix(60)))
        writer.string(mimeTag, "document-format", format)
        writer.tag(jobTag)
        writer.integer("copies", Int32(min(max(copies, 1), 10)))
        // Foto a tutta pagina senza deformarla: "fill" taglia i bordi.
        writer.string(keywordTag, "print-scaling", "fill")
        writer.tag(endTag)
        return writer.data + document
    }

    /// Il formato da mandare: JPEG se la stampante lo accetta, altrimenti nil.
    public static func chooseFormat(_ supported: [String]) -> String? {
        let lower = supported.map { $0.lowercased() }
        return lower.contains("image/jpeg") || lower.contains("application/octet-stream") ? "image/jpeg" : nil
    }

    public static func parse(_ data: Data) -> Response? {
        let bytes = [UInt8](data)
        guard bytes.count >= 8 else { return nil }
        var index = 2
        func u16() -> Int? {
            guard index + 2 <= bytes.count else { return nil }
            defer { index += 2 }
            return Int(bytes[index]) << 8 | Int(bytes[index + 1])
        }
        guard let status = u16() else { return nil }
        let requestID = Int32(bitPattern: UInt32(bytes[4]) << 24 | UInt32(bytes[5]) << 16 | UInt32(bytes[6]) << 8 | UInt32(bytes[7]))
        index = 8
        var attributes: [Attribute] = []
        while index < bytes.count {
            let tag = bytes[index]
            index += 1
            if tag == endTag { break }
            if tag < 0x10 { continue }
            guard let nameLength = u16(), index + nameLength <= bytes.count else { break }
            let name = String(decoding: bytes[index..<index + nameLength], as: UTF8.self)
            index += nameLength
            guard let valueLength = u16(), index + valueLength <= bytes.count else { break }
            let raw = Array(bytes[index..<index + valueLength])
            index += valueLength
            let value: Value
            switch tag {
            case integerTag, 0x23:
                value = .integer(raw.count == 4 ? Int32(bitPattern: UInt32(raw[0]) << 24 | UInt32(raw[1]) << 16 | UInt32(raw[2]) << 8 | UInt32(raw[3])) : 0)
            case 0x40...0x4F:
                value = .text(String(decoding: raw, as: UTF8.self))
            default:
                value = .bytes(Data(raw))
            }
            if nameLength == 0, !attributes.isEmpty {
                attributes[attributes.count - 1].values.append(value)
            } else {
                attributes.append(Attribute(tag: tag, name: name, values: [value]))
            }
        }
        return Response(status: UInt16(status), requestID: requestID, attributes: attributes)
    }

    private struct Writer {
        var data = Data()

        init(operation: UInt16, requestID: Int32) {
            data += [2, 0, UInt8(operation >> 8), UInt8(operation & 0xFF)] // IPP 2.0
            let id = UInt32(bitPattern: requestID)
            data += [UInt8(id >> 24 & 0xFF), UInt8(id >> 16 & 0xFF), UInt8(id >> 8 & 0xFF), UInt8(id & 0xFF)]
        }

        mutating func tag(_ value: UInt8) {
            data.append(value)
        }

        mutating func string(_ tag: UInt8, _ name: String, _ value: String) {
            let bytes = Data(value.utf8)
            header(tag, name, bytes.count)
            data += bytes
        }

        mutating func integer(_ name: String, _ value: Int32) {
            header(Ipp.integerTag, name, 4)
            let raw = UInt32(bitPattern: value)
            data += [UInt8(raw >> 24 & 0xFF), UInt8(raw >> 16 & 0xFF), UInt8(raw >> 8 & 0xFF), UInt8(raw & 0xFF)]
        }

        mutating func operation(_ printer: Printer) {
            tag(Ipp.operationTag)
            string(Ipp.charsetTag, "attributes-charset", "utf-8")
            string(Ipp.languageTag, "attributes-natural-language", "en")
            string(Ipp.uriTag, "printer-uri", printer.ippURI)
            string(Ipp.nameTag, "requesting-user-name", "FotoFesta")
        }

        private mutating func header(_ tag: UInt8, _ name: String, _ length: Int) {
            let nameBytes = Data(name.utf8)
            data += [tag, UInt8(nameBytes.count >> 8), UInt8(nameBytes.count & 0xFF)]
            data += nameBytes
            data += [UInt8(length >> 8 & 0xFF), UInt8(length & 0xFF)]
        }
    }
}

/// Manda i messaggi IPP alla stampante, solo nella rete di casa.
public actor IppClient {
    private let http: HTTPClient
    private var nextID: Int32 = 1

    public init(http: HTTPClient) {
        self.http = http
    }

    private func call(_ printer: Ipp.Printer, _ body: Data) async throws -> Ipp.Response {
        guard let url = URL(string: printer.httpURL), LANAddress.isLocal(url) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.httpMethod = "POST"
        request.setValue("application/ipp", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await http.send(request)
        guard response.statusCode == 200, let parsed = Ipp.parse(data) else { throw URLError(.badServerResponse) }
        return parsed
    }

    private func newID() -> Int32 {
        defer { nextID &+= 1 }
        return nextID
    }

    /// Il formato con cui stampare le foto, o nil se la stampante non ne accetta nessuno adatto.
    public func format(_ printer: Ipp.Printer) async throws -> String? {
        let response = try await call(printer, Ipp.getPrinterAttributesRequest(printer, requestID: newID()))
        return Ipp.chooseFormat(response.strings("document-format-supported"))
    }

    public func print(_ printer: Ipp.Printer, jpeg: Data, jobName: String, copies: Int = 1) async throws -> Bool {
        guard let format = try await format(printer) else { return false }
        return try await call(printer, Ipp.printJobRequest(printer, requestID: newID(), jobName: jobName, format: format, document: jpeg, copies: copies)).ok
    }
}
