import Foundation
import Network

/// Piccolo server HTTP per le pagine che la Apple TV serve ai telefoni sulla rete di casa
/// (autorizzazione di Google Foto): una richiesta per connessione, poche connessioni per volta e
/// per telefono, scadenze complessive, corpi piccoli e controllo dell'intestazione Host contro il
/// DNS rebinding. Dopo `stop()` le connessioni aperte si chiudono e le richieste ancora in corso
/// ricevono la pagina "scaduta", senza arrivare alla logica della pagina.
final class PhonePageServer: @unchecked Sendable {
    typealias Handler = @Sendable (GuestHTTPRequest, Data) -> GuestHTTPResponse

    static let maxConnections = 8
    static let maxConnectionsPerClient = 3
    static let deadline: TimeInterval = 20

    private let maxBodyBytes: Int
    private let expiredPage: String
    private let handler: Handler
    private let queue = DispatchQueue(label: "it.fotofesta.phone-page")

    // Usati solo sulla coda `queue`.
    private var listener: NWListener?
    private var stopping = true
    private var connections: [ObjectIdentifier: PageConnection] = [:]
    private var expectedHost = ""
    private var perClient: [String: Int] = [:]

    init(maxBodyBytes: Int = 4_096, expiredPage: String, handler: @escaping Handler) {
        self.maxBodyBytes = maxBodyBytes
        self.expiredPage = expiredPage
        self.handler = handler
    }

    /// Avvia il server su una porta libera. `host` è l'indirizzo scritto nel QR: le richieste con
    /// un'intestazione Host diversa vengono rifiutate. Restituisce la porta usata.
    func start(host: String) async throws -> UInt16 {
        stop()
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: .any)
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
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
                    listener?.cancel()
                    if resumed.claim() { continuation.resume(throwing: error) }
                case .cancelled:
                    if resumed.claim() { continuation.resume(throwing: CancellationError()) }
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    /// Ferma il server e chiude le connessioni aperte: dopo il ritorno nessuna richiesta arriva più
    /// alla logica della pagina.
    func stop() {
        queue.sync {
            stopping = true
            listener?.cancel()
            listener = nil
            for connection in connections.values { connection.close() }
            connections = [:]
        }
    }

    /// Chiamato sulla coda del server.
    private func accept(_ connection: NWConnection) {
        guard !stopping, listener != nil else {
            connection.cancel()
            return
        }
        let client = GuestUploadServer.clientKey(connection.endpoint)
        guard connections.count < Self.maxConnections, (perClient[client] ?? 0) < Self.maxConnectionsPerClient else {
            let busy = GuestHTTPResponse.html(503, expiredPage)
            connection.start(queue: queue)
            connection.send(content: busy.serialized, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
                connection.cancel()
            })
            return
        }
        perClient[client, default: 0] += 1
        let handler = PageConnection(connection: connection, server: self, maxBodyBytes: maxBodyBytes)
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

    /// Risposta a una richiesta letta per intero. Chiamato sulla coda del server.
    func respond(to request: GuestHTTPRequest, body: Data) -> GuestHTTPResponse {
        guard !stopping else { return .html(403, expiredPage) }
        // Contro il DNS rebinding: la pagina risponde solo all'indirizzo del QR.
        guard let host = request.headers["host"], host.lowercased() == expectedHost.lowercased() else {
            return .html(421, expiredPage)
        }
        return handler(request, body)
    }

    func errorPage(_ status: Int) -> GuestHTTPResponse {
        .html(status, expiredPage)
    }
}

/// Una connessione del server delle pagine: legge intestazioni e corpo entro un tempo totale,
/// risponde e chiude. Usata solo sulla coda del server.
private final class PageConnection {
    private static let drainLimit = 64 * 1024
    private static let drainTime: TimeInterval = 1
    private static let writeTime: TimeInterval = 10

    private let connection: NWConnection
    private weak var server: PhonePageServer?
    private let maxBodyBytes: Int
    private var buffer = Data()
    private var request: GuestHTTPRequest?
    private var bodyStart: Data.Index = 0
    private var expectedBody = 0
    private var deadline: DispatchWorkItem?
    private var queue: DispatchQueue?
    private var draining = false
    private var drained = 0
    private var finished = false
    var onClose: (() -> Void)?

    init(connection: NWConnection, server: PhonePageServer, maxBodyBytes: Int) {
        self.connection = connection
        self.server = server
        self.maxBodyBytes = maxBodyBytes
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
        // Tempo totale per l'intera richiesta, anche se i byte arrivano uno alla volta.
        setDeadline(PhonePageServer.deadline)
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
        guard let server else {
            finish()
            return
        }
        if request == nil {
            guard let end = GuestUploadServer.headEnd(in: buffer) else {
                if buffer.count > GuestUploadServer.maxHeaderBytes {
                    send(server.errorPage(431))
                } else if peerClosed {
                    finish()
                } else {
                    receive()
                }
                return
            }
            let parsed: GuestHTTPRequest
            do {
                parsed = try GuestUploadServer.parseHead(buffer.subdata(in: end.head))
            } catch {
                send(server.errorPage(400))
                return
            }
            if parsed.method == "POST" {
                guard parsed.headers["transfer-encoding"] == nil, let length = parsed.headers["content-length"].flatMap({ Int($0) }), length >= 0 else {
                    send(server.errorPage(411))
                    return
                }
                guard length <= maxBodyBytes else {
                    send(server.errorPage(413))
                    return
                }
                expectedBody = length
            }
            request = parsed
            bodyStart = end.bodyStart
        }
        guard let request else {
            finish()
            return
        }
        let received = buffer.endIndex - bodyStart
        if received >= expectedBody {
            let body = expectedBody > 0 ? buffer.subdata(in: bodyStart..<(bodyStart + expectedBody)) : Data()
            buffer = Data()
            send(server.respond(to: request, body: body))
        } else if peerClosed {
            finish()
        } else {
            receive()
        }
    }

    /// Invia la risposta, scarta per un attimo quello che il telefono stesse ancora mandando (così
    /// non riceve un reset al posto della risposta) e chiude.
    private func send(_ response: GuestHTTPResponse) {
        setDeadline(Self.writeTime)
        connection.send(content: response.serialized, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            guard let self, !self.finished else { return }
            self.draining = true
            self.setDeadline(Self.drainTime)
            self.receive()
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
        connection.cancel()
        onClose?()
        onClose = nil
    }
}
