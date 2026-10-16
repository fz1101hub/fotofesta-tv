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
    public var retryAfter: Int?

    public init(status: Int, code: String, retryAfter: Int? = nil) {
        self.status = status
        self.code = code
        self.retryAfter = retryAfter
    }
}

/// Piccolo server HTTP sulla rete di casa: dà agli invitati la pagina per mandare le foto e le
/// riceve. Le foto non passano da internet: vanno dal telefono alla TV attraverso il router.
///
/// Solo chi ha inquadrato il QR conosce `key`, richiesta sia per aprire la pagina sia per inviare.
/// Ogni richiesta usa una connessione propria, con limiti su intestazioni, dimensione e tempi totali
/// (non solo tra un pacchetto e l'altro: una connessione lentissima non resta aperta), un massimo di
/// connessioni e di invii al minuto per telefono e un tetto ai byte in arrivo contemporaneamente.
/// Tutto avviene su una sola coda seriale. Se il server si ferma da solo (ad esempio quando la rete
/// cade) viene chiamato `onFailure`.
public final class GuestUploadServer: @unchecked Sendable {
    public enum UploadResult: Sendable {
        case shown, pending, full, tooManyPending, unsupported
    }

    public static let maxUploadBytes = 12 * 1024 * 1024
    static let maxHeaderBytes = 16 * 1024
    static let maxConnections = 16
    static let maxConnectionsPerClient = 3
    static let maxInFlightBytes = 24 * 1024 * 1024

    private let key: String
    private let maxUploadBytes: Int
    private let headerDeadline: TimeInterval
    private let bodyDeadline: TimeInterval
    private let maxUploadsPerMinute: Int
    private let page: @Sendable () -> String
    private let onPhoto: @Sendable (Data, String?) -> UploadResult
    /// Chiamato sulla coda del server: non deve chiamare `start()` o `stop()` direttamente.
    private let onFailure: @Sendable () -> Void
    private let queue = DispatchQueue(label: "it.fotofesta.guest-upload")

    // Usati solo sulla coda `queue`.
    private var listener: NWListener?
    private var stopping = false
    private var connections: [ObjectIdentifier: GuestConnection] = [:]
    private var expectedHost = ""
    private var perClient: [String: Int] = [:]
    private var recentUploads: [String: [Date]] = [:]
    private var inFlightBytes = 0
    /// Invii già ricevuti, per non salvare due volte una foto se il telefono ripete l'invio.
    private var handledUploads: [String: GuestHTTPResponse] = [:]
    private var handledOrder: [String] = []

    public init(
        key: String,
        maxUploadBytes: Int = GuestUploadServer.maxUploadBytes,
        headerDeadline: TimeInterval = 15,
        bodyDeadline: TimeInterval = 120,
        maxUploadsPerMinute: Int = 60,
        page: @escaping @Sendable () -> String,
        onPhoto: @escaping @Sendable (Data, String?) -> UploadResult,
        onFailure: @escaping @Sendable () -> Void = {}
    ) {
        self.key = key
        self.maxUploadBytes = maxUploadBytes
        self.headerDeadline = headerDeadline
        self.bodyDeadline = bodyDeadline
        self.maxUploadsPerMinute = maxUploadsPerMinute
        self.page = page
        self.onPhoto = onPhoto
        self.onFailure = onFailure
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

    /// Ferma il server. Tutto avviene sulla coda del server, quindi dopo il ritorno nessuna foto viene
    /// più consegnata a `onPhoto`.
    public func stop() {
        queue.sync {
            stopping = true
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
        // Lo stato cambia sempre sulla coda del server: basta un indicatore senza lock, e il
        // listener attivo si registra qui, prima che possa arrivare qualunque connessione.
        let resumed = OnceFlag()
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                switch state {
                case .ready:
                    guard resumed.claim() else { return }
                    let port = listener?.port?.rawValue ?? 0
                    if let self, let listener {
                        self.stopping = false
                        self.listener = listener
                        self.expectedHost = GuestUploadServer.hostHeader(host: host, port: port)
                    }
                    continuation.resume(returning: port)
                case let .failed(error), let .waiting(error):
                    // Senza rete il listener aspetterebbe all'infinito: meglio un errore subito.
                    listener?.cancel()
                    if resumed.claim() {
                        continuation.resume(throwing: error)
                    } else {
                        // Fermato dopo l'avvio (ad esempio la rete è caduta): chi l'ha avviato lo riavvia.
                        self?.listenerStopped(listener)
                    }
                case .cancelled:
                    if resumed.claim() {
                        continuation.resume(throwing: CancellationError())
                    } else {
                        self?.listenerStopped(listener)
                    }
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    /// Chiamato sulla coda del server quando un listener si ferma da solo dopo l'avvio. Conta solo
    /// quello attivo: un tentativo precedente sulla porta preferita non deve fermare quello nuovo.
    private func listenerStopped(_ stopped: NWListener?) {
        guard !stopping, let stopped, stopped === listener else { return }
        listener = nil
        onFailure()
    }

    /// Chiamato sulla coda del server.
    private func accept(_ connection: NWConnection) {
        // Connessione arrivata mentre il server si fermava: dopo stop() nessuna foto deve arrivare.
        guard !stopping, listener != nil else {
            connection.cancel()
            return
        }
        let client = Self.clientKey(connection.endpoint)
        guard connections.count < Self.maxConnections, (perClient[client] ?? 0) < Self.maxConnectionsPerClient else {
            // Troppe richieste insieme: risposta breve senza leggere la richiesta, il telefono riproverà.
            let busy = GuestHTTPResponse.json(503, #"{"error":"busy"}"#, retryAfter: 5)
            connection.start(queue: queue)
            connection.send(content: busy.serialized, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
                connection.cancel()
            })
            return
        }
        perClient[client, default: 0] += 1
        let handler = GuestConnection(connection: connection, server: self, client: client, headerDeadline: headerDeadline, bodyDeadline: bodyDeadline)
        let id = ObjectIdentifier(handler)
        connections[id] = handler
        handler.onClose = { [weak self] in
            guard let self else { return }
            self.connections[id] = nil
            let remaining = (self.perClient[client] ?? 1) - 1
            self.perClient[client] = remaining > 0 ? remaining : nil
        }
        handler.start(on: queue)
    }

    /// Controlli da fare appena arrivano le intestazioni: un upload rifiutato non viene letto.
    /// Restituisce quanti byte di corpo attendere, oppure una risposta già pronta (invio ripetuto).
    /// Chiamato sulla coda del server.
    func prepare(_ request: GuestHTTPRequest, client: String) throws -> (bodyLength: Int, cached: GuestHTTPResponse?) {
        try checkHost(request)
        guard request.path == "/photos" else { return (0, nil) }
        guard request.method == "POST" else { throw GuestHTTPError(status: 405, code: "method") }
        guard keyMatches(request.headers["x-fotofesta-key"]) else { throw GuestHTTPError(status: 401, code: "key") }
        if request.headers["transfer-encoding"] != nil { throw GuestHTTPError(status: 411, code: "length") }
        guard let lengthText = request.headers["content-length"], let length = Int(lengthText.trimmingCharacters(in: .whitespaces)) else {
            throw GuestHTTPError(status: 411, code: "length")
        }
        guard length > 0 else { throw GuestHTTPError(status: 400, code: "empty") }
        guard length <= maxUploadBytes else { throw GuestHTTPError(status: 413, code: "too_large") }
        if let id = Self.uploadID(request), let cached = handledUploads[id] { return (length, cached) }
        guard allowUpload(client) else { throw GuestHTTPError(status: 429, code: "rate", retryAfter: 10) }
        guard inFlightBytes + length <= Self.maxInFlightBytes else { throw GuestHTTPError(status: 503, code: "busy", retryAfter: 5) }
        inFlightBytes += length
        return (length, nil)
    }

    /// Libera i byte riservati da `prepare` per un corpo che non arriverà (connessione chiusa).
    func releaseReserved(_ length: Int) {
        inFlightBytes = max(inFlightBytes - length, 0)
    }

    /// Risposta a una richiesta completa. `body` contiene il corpo già letto per intero; i byte
    /// riservati da `prepare` vengono liberati. Chiamato sulla coda del server.
    func respond(to request: GuestHTTPRequest, body: Data) -> GuestHTTPResponse {
        if request.path == "/photos" { releaseReserved(body.count) }
        do {
            try checkHost(request)
            switch request.path {
            case "/":
                guard request.method == "GET" || request.method == "HEAD" else { throw GuestHTTPError(status: 405, code: "method") }
                guard keyMatches(request.query["k"]) else { return .html(403, Self.expiredPage) }
                return .html(200, page(), head: request.method == "HEAD")
            case "/photos":
                let response = try receivePhoto(request, body: body)
                if let id = Self.uploadID(request) { remember(id, response) }
                return response
            default:
                return .json(404, #"{"error":"not_found"}"#)
            }
        } catch let error as GuestHTTPError {
            return .error(error)
        } catch {
            return .json(400, #"{"error":"request"}"#)
        }
    }

    private func receivePhoto(_ request: GuestHTTPRequest, body: Data) throws -> GuestHTTPResponse {
        guard GuestImageFormat.sniff(body) != nil else { throw GuestHTTPError(status: 415, code: "unsupported") }
        let sender = request.headers["x-fotofesta-sender"].flatMap { $0.removingPercentEncoding }.flatMap(GuestSender.clean)
        switch onPhoto(body, sender) {
        case .shown: return .json(200, #"{"status":"shown"}"#)
        case .pending: return .json(200, #"{"status":"pending"}"#)
        case .full: throw GuestHTTPError(status: 507, code: "full")
        case .tooManyPending: throw GuestHTTPError(status: 507, code: "pending_full")
        case .unsupported: throw GuestHTTPError(status: 415, code: "unsupported")
        }
    }

    private func remember(_ id: String, _ response: GuestHTTPResponse) {
        handledUploads[id] = response
        handledOrder.append(id)
        if handledOrder.count > 200 { handledUploads[handledOrder.removeFirst()] = nil }
    }

    /// Al massimo `maxUploadsPerMinute` invii al minuto dallo stesso telefono.
    private func allowUpload(_ client: String) -> Bool {
        let now = Date()
        var times = (recentUploads[client] ?? []).filter { now.timeIntervalSince($0) <= 60 }
        guard times.count < maxUploadsPerMinute else {
            recentUploads[client] = times
            return false
        }
        times.append(now)
        recentUploads[client] = times
        return true
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

    /// Identificativo dell'invio mandato dalla pagina, se valido.
    static func uploadID(_ request: GuestHTTPRequest) -> String? {
        guard let id = request.headers["x-fotofesta-upload"], (8...64).contains(id.count),
              id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        return id
    }

    /// Telefono che si collega, per i limiti per telefono: l'indirizzo senza la porta.
    static func clientKey(_ endpoint: NWEndpoint) -> String {
        if case let .hostPort(host, _) = endpoint { return "\(host)" }
        return "\(endpoint)"
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

/// Indicatore che vale una volta sola. Usato solo sulla coda del server, quindi senza lock.
private final class OnceFlag: @unchecked Sendable {
    private var used = false

    func claim() -> Bool {
        if used { return false }
        used = true
        return true
    }
}

/// Risposta HTTP pronta da inviare.
struct GuestHTTPResponse {
    let status: Int
    let contentType: String
    let body: Data
    var extraHeaders: [(String, String)] = []
    var head = false

    static func json(_ status: Int, _ body: String, retryAfter: Int? = nil) -> GuestHTTPResponse {
        GuestHTTPResponse(
            status: status,
            contentType: "application/json; charset=utf-8",
            body: Data(body.utf8),
            extraHeaders: retryAfter.map { [("Retry-After", String($0))] } ?? []
        )
    }

    static func error(_ error: GuestHTTPError) -> GuestHTTPResponse {
        json(error.status, #"{"error":"\#(error.code)"}"#, retryAfter: error.retryAfter)
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
        case 429: return "Too Many Requests"
        case 431: return "Request Header Fields Too Large"
        case 503: return "Service Unavailable"
        case 507: return "Insufficient Storage"
        default: return "Error"
        }
    }
}

/// Una connessione del server: legge intestazioni e corpo entro tempi totali, risponde e chiude.
/// Usata solo sulla coda del server.
final class GuestConnection {
    private static let drainLimit = 256 * 1024
    private static let drainTime: TimeInterval = 1
    private static let writeTime: TimeInterval = 10

    private let connection: NWConnection
    private weak var server: GuestUploadServer?
    private let client: String
    private let headerDeadline: TimeInterval
    private let bodyDeadline: TimeInterval
    private var buffer = Data()
    private var request: GuestHTTPRequest?
    private var bodyStart: Data.Index = 0
    private var expectedBody = 0
    private var reserved = 0
    private var deadline: DispatchWorkItem?
    private var queue: DispatchQueue?
    private var draining = false
    private var drained = 0
    private var finished = false
    var onClose: (() -> Void)?

    init(connection: NWConnection, server: GuestUploadServer, client: String, headerDeadline: TimeInterval, bodyDeadline: TimeInterval) {
        self.connection = connection
        self.server = server
        self.client = client
        self.headerDeadline = headerDeadline
        self.bodyDeadline = bodyDeadline
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
        // Tempo totale per le intestazioni, anche se i byte arrivano uno alla volta.
        setDeadline(headerDeadline)
        receive()
    }

    func close() {
        finish()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if self.draining {
                self.drained += data?.count ?? 0
                if isComplete || error != nil || self.drained >= Self.drainLimit {
                    self.finish()
                } else {
                    self.receive()
                }
                return
            }
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
                    send(.json(431, #"{"error":"header"}"#), early: true)
                } else if peerClosed {
                    finish()
                } else {
                    receive()
                }
                return
            }
            if end.head.count > GuestUploadServer.maxHeaderBytes {
                send(.json(431, #"{"error":"header"}"#), early: true)
                return
            }
            guard let server else {
                finish()
                return
            }
            do {
                let parsed = try GuestUploadServer.parseHead(buffer.subdata(in: end.head))
                let prepared = try server.prepare(parsed, client: client)
                if let cached = prepared.cached {
                    // Invio già ricevuto: si risponde come la prima volta, senza leggere di nuovo la foto.
                    send(cached, early: true)
                    return
                }
                expectedBody = prepared.bodyLength
                reserved = parsed.path == "/photos" ? prepared.bodyLength : 0
                request = parsed
                bodyStart = end.bodyStart
                if expectedBody > 0 { setDeadline(bodyDeadline) }
            } catch let error as GuestHTTPError {
                send(.error(error), early: true)
                return
            } catch {
                send(.json(400, #"{"error":"request"}"#), early: true)
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
            buffer = Data()
            reserved = 0
            send(server.respond(to: request, body: body), early: false)
        } else if peerClosed {
            finish()
        } else {
            receive()
        }
    }

    /// Invia la risposta e chiude. `early`: il telefono potrebbe star ancora mandando la foto; chiudere
    /// subito farebbe arrivare un reset al posto della risposta, quindi si scarta il resto per un po'.
    private func send(_ response: GuestHTTPResponse, early: Bool) {
        setDeadline(Self.writeTime)
        connection.send(content: response.serialized, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            guard let self, !self.finished else { return }
            if early {
                self.draining = true
                self.setDeadline(Self.drainTime)
                self.receive()
            } else {
                self.finish()
            }
        })
    }

    private func setDeadline(_ seconds: TimeInterval) {
        deadline?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.finish() }
        deadline = item
        queue?.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        deadline?.cancel()
        if reserved > 0 {
            server?.releaseReserved(reserved)
            reserved = 0
        }
        connection.cancel()
        onClose?()
        onClose = nil
    }
}
