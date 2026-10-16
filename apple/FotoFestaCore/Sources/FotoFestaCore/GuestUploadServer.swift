import Foundation
import Network

/// Richiesta HTTP letta dal server degli invitati.
public struct GuestHTTPRequest: Equatable, Sendable {
    public let method: String
    public let path: String
    public let query: [String: String]
    /// Nomi in minuscolo.
    public let headers: [String: String]
}

/// Errore HTTP con il codice da restituire al telefono.
public struct GuestHTTPError: Error, Equatable, Sendable {
    public let status: Int
    public let code: String
}

/// Piccolo server HTTP sulla rete di casa: dà agli invitati la pagina per mandare le foto e le
/// riceve. Le foto non passano da internet: vanno dal telefono alla TV attraverso il router.
///
/// Solo chi ha inquadrato il QR conosce `key`, richiesta sia per aprire la pagina sia per inviare.
/// Ogni richiesta usa una connessione propria, con limiti su intestazioni, dimensione e tempi, e
/// sono accettati solo telefoni con un indirizzo della rete locale.
public final class GuestUploadServer: @unchecked Sendable {
    public enum UploadResult: Sendable {
        case shown, pending, full, unsupported
    }

    public static let maxUploadBytes = 12 * 1024 * 1024
    static let maxHeaderBytes = 16 * 1024
    static let maxConnections = 16

    private let key: String
    private let maxUploadBytes: Int
    private let readTimeout: TimeInterval
    private let page: @Sendable () -> String
    private let onPhoto: @Sendable (Data, String?) -> UploadResult
    private let queue = DispatchQueue(label: "it.fotofesta.guest-upload")
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: GuestConnection] = [:]
    private var expectedHost = ""

    public init(
        key: String,
        maxUploadBytes: Int = GuestUploadServer.maxUploadBytes,
        readTimeout: TimeInterval = 20,
        page: @escaping @Sendable () -> String,
        onPhoto: @escaping @Sendable (Data, String?) -> UploadResult
    ) {
        self.key = key
        self.maxUploadBytes = maxUploadBytes
        self.readTimeout = readTimeout
        self.page = page
        self.onPhoto = onPhoto
    }

    /// Avvia il server, preferibilmente sulla porta `preferredPort` (così il QR resta uguale tra un
    /// avvio e l'altro), altrimenti su una porta libera. `host` è l'indirizzo scritto nel QR: le
    /// richieste con un'intestazione Host diversa vengono rifiutate. Restituisce la porta usata.
    public func start(host: String, preferredPort: UInt16) async throws -> UInt16 {
        stop()
        do {
            return try await listen(host: host, port: NWEndpoint.Port(rawValue: preferredPort) ?? .any)
        } catch {
            return try await listen(host: host, port: .any)
        }
    }

    public func stop() {
        queue.sync {
            listener?.cancel()
            listener = nil
            for connection in connections.values { connection.close() }
            connections = [:]
        }
    }

    private func listen(host: String, port: NWEndpoint.Port) async throws -> UInt16 {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: port)
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        let actualPort: UInt16 = try await withCheckedThrowingContinuation { continuation in
            var resumed = false
            listener.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    continuation.resume(returning: listener.port?.rawValue ?? 0)
                case let .failed(error):
                    resumed = true
                    listener.cancel()
                    continuation.resume(throwing: error)
                case .cancelled:
                    resumed = true
                    continuation.resume(throwing: CancellationError())
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        queue.sync {
            self.listener = listener
            self.expectedHost = Self.hostHeader(host: host, port: actualPort)
        }
        return actualPort
    }

    /// Chiamato sulla coda del server.
    private func accept(_ connection: NWConnection) {
        guard connections.count < Self.maxConnections, Self.isLocal(connection.endpoint) else {
            // Troppe richieste insieme, o un indirizzo fuori dalla rete di casa.
            connection.cancel()
            return
        }
        let handler = GuestConnection(connection: connection, server: self, readTimeout: readTimeout)
        let id = ObjectIdentifier(handler)
        connections[id] = handler
        handler.onClose = { [weak self] in self?.connections[id] = nil }
        handler.start(on: queue)
    }

    /// Risposta a una richiesta completa. `body` contiene il corpo già letto per intero.
    func respond(to request: GuestHTTPRequest, body: Data) -> GuestHTTPResponse {
        do {
            try checkHost(request)
            switch request.path {
            case "/":
                guard request.method == "GET" || request.method == "HEAD" else { throw GuestHTTPError(status: 405, code: "method") }
                guard keyMatches(request.query["k"]) else { return .html(403, Self.expiredPage) }
                return .html(200, page(), head: request.method == "HEAD")
            case "/photos":
                return try receivePhoto(request, body: body)
            default:
                return .json(404, #"{"error":"not_found"}"#)
            }
        } catch let error as GuestHTTPError {
            return .json(error.status, #"{"error":"\#(error.code)"}"#)
        } catch {
            return .json(400, #"{"error":"request"}"#)
        }
    }

    /// Controlli da fare appena arrivano le intestazioni: un upload rifiutato non viene letto.
    /// Restituisce quanti byte di corpo attendere.
    func expectedBodyLength(for request: GuestHTTPRequest) throws -> Int {
        try checkHost(request)
        guard request.path == "/photos" else { return 0 }
        guard request.method == "POST" else { throw GuestHTTPError(status: 405, code: "method") }
        guard keyMatches(request.headers["x-fotofesta-key"]) else { throw GuestHTTPError(status: 401, code: "key") }
        if request.headers["transfer-encoding"] != nil { throw GuestHTTPError(status: 411, code: "length") }
        guard let lengthText = request.headers["content-length"], let length = Int(lengthText.trimmingCharacters(in: .whitespaces)) else {
            throw GuestHTTPError(status: 411, code: "length")
        }
        guard length > 0 else { throw GuestHTTPError(status: 400, code: "empty") }
        guard length <= maxUploadBytes else { throw GuestHTTPError(status: 413, code: "too_large") }
        return length
    }

    private func receivePhoto(_ request: GuestHTTPRequest, body: Data) throws -> GuestHTTPResponse {
        _ = try expectedBodyLength(for: request)
        guard GuestImageFormat.sniff(body) != nil else { throw GuestHTTPError(status: 415, code: "unsupported") }
        let sender = request.headers["x-fotofesta-sender"].flatMap { $0.removingPercentEncoding }.flatMap(GuestSender.clean)
        switch onPhoto(body, sender) {
        case .shown: return .json(200, #"{"status":"shown"}"#)
        case .pending: return .json(200, #"{"status":"pending"}"#)
        case .full: throw GuestHTTPError(status: 507, code: "full")
        case .unsupported: throw GuestHTTPError(status: 415, code: "unsupported")
        }
    }

    private func checkHost(_ request: GuestHTTPRequest) throws {
        if let host = request.headers["host"], host.lowercased() != expectedHost.lowercased() {
            throw GuestHTTPError(status: 421, code: "host")
        }
    }

    /// Confronto a tempo costante: la durata non rivela quanti caratteri della chiave sono giusti.
    private func keyMatches(_ candidate: String?) -> Bool {
        guard let candidate else { return false }
        let a = Array(candidate.utf8)
        let b = Array(key.utf8)
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for index in a.indices { difference |= a[index] ^ b[index] }
        return difference == 0
    }

    // MARK: - Funzioni pure (testate)

    public static func hostHeader(host: String, port: UInt16) -> String {
        host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)"
    }

    /// Indirizzi della rete di casa: privati IPv4, link-local, loopback e IPv6 locali (ULA, link-local).
    static func isLocal(_ endpoint: NWEndpoint) -> Bool {
        guard case let .hostPort(host, _) = endpoint else { return false }
        switch host {
        case let .ipv4(address):
            return isLocalIPv4([UInt8](address.rawValue))
        case let .ipv6(address):
            let bytes = [UInt8](address.rawValue)
            guard bytes.count == 16 else { return false }
            // IPv4 scritto come IPv6 (::ffff:a.b.c.d).
            if bytes[0..<10].allSatisfy({ $0 == 0 }), bytes[10] == 0xFF, bytes[11] == 0xFF {
                return isLocalIPv4(Array(bytes[12..<16]))
            }
            let loopback = bytes[0..<15].allSatisfy { $0 == 0 } && bytes[15] == 1
            return loopback || (bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80) || bytes[0] & 0xFE == 0xFC
        default:
            return false
        }
    }

    static func isLocalIPv4(_ octets: [UInt8]) -> Bool {
        guard octets.count == 4 else { return false }
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 172 && (16...31).contains(octets[1])) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    /// Legge riga di richiesta e intestazioni (senza la riga vuota finale).
    public static func parseHead(_ head: Data) throws -> GuestHTTPRequest {
        guard let text = String(data: head, encoding: .isoLatin1) else { throw GuestHTTPError(status: 400, code: "request") }
        var lines = text.components(separatedBy: "\n").map { $0.hasSuffix("\r") ? String($0.dropLast()) : $0 }
        while lines.last?.isEmpty == true { lines.removeLast() }
        guard let first = lines.first else { throw GuestHTTPError(status: 400, code: "request") }
        let parts = first.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[2].hasPrefix("HTTP/1."), parts[1].hasPrefix("/") else {
            throw GuestHTTPError(status: 400, code: "request")
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { throw GuestHTTPError(status: 400, code: "header") }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            // Due Content-Length diversi renderebbero ambigua la fine del corpo.
            if name == "content-length", let existing = headers[name], existing != value { throw GuestHTTPError(status: 400, code: "header") }
            headers[name] = value
        }
        let target = String(parts[1])
        let path = target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? target
        var query: [String: String] = [:]
        if let questionMark = target.firstIndex(of: "?") {
            for pair in target[target.index(after: questionMark)...].split(separator: "&") where !pair.isEmpty {
                let pieces = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                query[String(pieces[0])] = pieces.count > 1 ? String(pieces[1]) : ""
            }
        }
        return GuestHTTPRequest(method: String(parts[0]), path: path, query: query, headers: headers)
    }

    /// Fine delle intestazioni (CRLF CRLF oppure LF LF) e posizione del corpo, se già arrivata.
    static func headEnd(in buffer: Data) -> (head: Range<Data.Index>, bodyStart: Data.Index)? {
        let bytes = buffer
        var index = bytes.startIndex
        while index < bytes.endIndex {
            if bytes[index] == 0x0A {
                let next = bytes.index(after: index)
                if next < bytes.endIndex, bytes[next] == 0x0A {
                    return (bytes.startIndex..<index, bytes.index(after: next))
                }
                if next < bytes.endIndex, bytes[next] == 0x0D {
                    let afterCR = bytes.index(after: next)
                    if afterCR < bytes.endIndex, bytes[afterCR] == 0x0A {
                        return (bytes.startIndex..<index, bytes.index(after: afterCR))
                    }
                }
            }
            index = bytes.index(after: index)
        }
        return nil
    }

    static let pagePolicy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
        "img-src blob: data:; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

    static let expiredPage = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1"><title>FotoFesta</title></head>
    <body style="font:18px/1.5 system-ui,sans-serif;margin:32px 20px;background:#0b1222;color:#eef2fa">
    <h1 style="font-size:1.4rem">FotoFesta</h1>
    <p>Questo link non è più valido: inquadra di nuovo il QR sulla TV.</p>
    <p>This link is no longer valid: scan the QR code on the TV again.</p>
    </body></html>
    """
}

/// Risposta HTTP pronta da inviare.
struct GuestHTTPResponse {
    let status: Int
    let contentType: String
    let body: Data
    var extraHeaders: [(String, String)] = []
    var head = false

    static func json(_ status: Int, _ body: String) -> GuestHTTPResponse {
        GuestHTTPResponse(status: status, contentType: "application/json; charset=utf-8", body: Data(body.utf8))
    }

    static func html(_ status: Int, _ body: String, head: Bool = false) -> GuestHTTPResponse {
        GuestHTTPResponse(
            status: status,
            contentType: "text/html; charset=utf-8",
            body: Data(body.utf8),
            extraHeaders: [("Content-Security-Policy", GuestUploadServer.pagePolicy)],
            head: head
        )
    }

    var serialized: Data {
        var text = "HTTP/1.1 \(status) \(Self.reason(status))\r\n"
        text += "Content-Type: \(contentType)\r\n"
        text += "Content-Length: \(body.count)\r\n"
        text += "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
        for (name, value) in extraHeaders { text += "\(name): \(value)\r\n" }
        text += "\r\n"
        var data = Data(text.utf8)
        if !head { data.append(body) }
        return data
    }

    static func reason(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 411: return "Length Required"
        case 413: return "Payload Too Large"
        case 415: return "Unsupported Media Type"
        case 421: return "Misdirected Request"
        case 431: return "Request Header Fields Too Large"
        case 507: return "Insufficient Storage"
        default: return "Error"
        }
    }
}

/// Una connessione del server: legge intestazioni e corpo, risponde e chiude. Usata solo sulla coda
/// del server.
final class GuestConnection {
    private let connection: NWConnection
    private weak var server: GuestUploadServer?
    private let readTimeout: TimeInterval
    private var buffer = Data()
    private var request: GuestHTTPRequest?
    private var bodyStart: Data.Index = 0
    private var expectedBody = 0
    private var timeout: DispatchWorkItem?
    private var queue: DispatchQueue?
    private var finished = false
    var onClose: (() -> Void)?

    init(connection: NWConnection, server: GuestUploadServer, readTimeout: TimeInterval) {
        self.connection = connection
        self.server = server
        self.readTimeout = readTimeout
    }

    func start(on queue: DispatchQueue) {
        self.queue = queue
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.finish()
            default: break
            }
        }
        connection.start(queue: queue)
        receive()
    }

    func close() {
        finish()
    }

    private func receive() {
        restartTimeout()
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if let data, !data.isEmpty { self.buffer.append(data) }
            if error != nil {
                self.finish()
                return
            }
            self.process(peerClosed: isComplete)
        }
    }

    private func process(peerClosed: Bool) {
        if request == nil {
            guard let end = GuestUploadServer.headEnd(in: buffer) else {
                if buffer.count > GuestUploadServer.maxHeaderBytes {
                    send(.json(431, #"{"error":"header"}"#))
                } else if peerClosed {
                    finish()
                } else {
                    receive()
                }
                return
            }
            if end.head.count > GuestUploadServer.maxHeaderBytes {
                send(.json(431, #"{"error":"header"}"#))
                return
            }
            guard let server else {
                finish()
                return
            }
            do {
                let parsed = try GuestUploadServer.parseHead(buffer.subdata(in: end.head))
                expectedBody = try server.expectedBodyLength(for: parsed)
                request = parsed
                bodyStart = end.bodyStart
            } catch let error as GuestHTTPError {
                send(.json(error.status, #"{"error":"\#(error.code)"}"#))
                return
            } catch {
                send(.json(400, #"{"error":"request"}"#))
                return
            }
        }
        guard let request, let server else {
            finish()
            return
        }
        let received = buffer.endIndex - bodyStart
        if received >= expectedBody {
            let body = expectedBody > 0 ? buffer.subdata(in: bodyStart..<(bodyStart + expectedBody)) : Data()
            send(server.respond(to: request, body: body))
        } else if peerClosed {
            finish()
        } else {
            receive()
        }
    }

    private func send(_ response: GuestHTTPResponse) {
        timeout?.cancel()
        connection.send(content: response.serialized, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.finish()
        })
    }

    private func restartTimeout() {
        timeout?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.finish() }
        timeout = item
        queue?.asyncAfter(deadline: .now() + readTimeout, execute: item)
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        timeout?.cancel()
        connection.cancel()
        onClose?()
        onClose = nil
    }
}
