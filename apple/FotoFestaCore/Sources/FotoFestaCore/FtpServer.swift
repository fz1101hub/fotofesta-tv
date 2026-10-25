import Foundation
import Network

/// Server FTP minimo per le fotocamere con il Wi‑Fi (Canon, Nikon, Sony, Fujifilm sanno mandare
/// ogni scatto a un server FTP): ogni file ricevuto va a `onFile`, che lo mette nella festa. Si
/// accettano solo caricamenti, in una cartella virtuale vuota; nessun file del dispositivo è
/// raggiungibile. Solo modalità passiva (PASV ed EPSV), che usano tutte le fotocamere recenti.
public final class FtpServer: @unchecked Sendable {
    public static let defaultPort: UInt16 = 2121
    public static let maxFileBytes = 25 * 1024 * 1024
    static let maxSessions = 3
    static let maxFailures = 3
    static let maxLine = 1_024

    let user: String
    let password: @Sendable () -> String
    let onFile: @Sendable (String, Data) -> Void
    let maxFileBytes: Int
    private let port: UInt16
    private let allowLoopback: Bool
    let queue = DispatchQueue(label: "it.fotofesta.ftp")
    private var listener: NWListener?
    private var sessions: [ObjectIdentifier: FtpSession] = [:]

    public init(
        user: String,
        password: @escaping @Sendable () -> String,
        port: UInt16 = FtpServer.defaultPort,
        maxFileBytes: Int = FtpServer.maxFileBytes,
        allowLoopback: Bool = false,
        onFile: @escaping @Sendable (String, Data) -> Void
    ) {
        self.user = user
        self.password = password
        self.port = port
        self.maxFileBytes = maxFileBytes
        self.allowLoopback = allowLoopback
        self.onFile = onFile
    }

    /// Avvia il server e restituisce la porta in ascolto.
    public func start() async throws -> UInt16 {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: port == 0 ? .any : NWEndpoint.Port(rawValue: port) ?? .any)
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            let once = PortOnce(continuation)
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: once.resume(listener.port?.rawValue ?? 0)
                case let .failed(error): once.fail(error)
                case .cancelled: once.fail(CancellationError())
                default: break
                }
            }
            queue.sync { self.listener = listener }
            listener.start(queue: queue)
        }
    }

    public func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
            self.sessions.values.forEach { $0.close() }
            self.sessions = [:]
        }
    }

    func host(of endpoint: NWEndpoint) -> String? {
        if case let .hostPort(host, _) = endpoint {
            switch host {
            case let .ipv4(address): return "\(address)"
            case let .ipv6(address): return "\(address)"
            case let .name(name, _): return name
            @unknown default: return nil
            }
        }
        return nil
    }

    private func accept(_ connection: NWConnection) {
        let host = host(of: connection.endpoint) ?? ""
        let loopback = host == "127.0.0.1" || host == "::1"
        guard LANAddress.isLocal(host) || (allowLoopback && loopback), sessions.count < Self.maxSessions else {
            connection.cancel()
            return
        }
        let session = FtpSession(connection: connection, peer: host, server: self)
        let key = ObjectIdentifier(session)
        sessions[key] = session
        session.onClose = { [weak self] in self?.sessions[key] = nil }
        session.start()
    }
}

/// Una connessione di controllo FTP. Tutto avviene sulla coda del server.
final class FtpSession {
    private let control: NWConnection
    private let peer: String
    private unowned let server: FtpServer
    var onClose: (() -> Void)?
    private var buffer = Data()
    private var userOK = false
    private var loggedIn = false
    private var failures = 0
    private var directory = "/"
    private var passive: NWListener?
    private var dataConnection: NWConnection?
    /// Caricamento chiesto prima che la fotocamera abbia aperto la connessione dei dati.
    private var pendingTransfer: ((NWConnection?) -> Void)?
    private var closed = false

    init(connection: NWConnection, peer: String, server: FtpServer) {
        control = connection
        self.peer = peer
        self.server = server
    }

    func start() {
        control.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        control.start(queue: server.queue)
        reply(220, "FotoFesta")
        readLine()
    }

    func close() {
        guard !closed else { return }
        closed = true
        passive?.cancel()
        dataConnection?.cancel()
        control.cancel()
        onClose?()
    }

    private func readLine() {
        control.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { [weak self] data, _, complete, error in
            guard let self, !self.closed else { return }
            if let data { self.buffer.append(data) }
            while let newline = self.buffer.firstIndex(of: 0x0A) {
                let lineData = self.buffer[self.buffer.startIndex..<newline]
                self.buffer.removeSubrange(self.buffer.startIndex...newline)
                let line = String(decoding: lineData.prefix(FtpServer.maxLine), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                if !self.handle(line) {
                    self.close()
                    return
                }
            }
            if self.buffer.count > FtpServer.maxLine * 4 || complete || error != nil {
                self.close()
                return
            }
            // Durante un trasferimento la lettura riprende alla fine (vedi `store`).
            if self.pendingTransfer == nil, !self.transferring { self.readLine() }
        }
    }

    private var transferring = false

    /// false per chiudere la connessione.
    private func handle(_ line: String) -> Bool {
        let verb = line.split(separator: " ", maxSplits: 1).first.map { $0.uppercased() } ?? ""
        let argument = line.split(separator: " ", maxSplits: 1).dropFirst().first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        switch verb {
        case "USER":
            userOK = argument == server.user
            return reply(331, "Password")
        case "PASS":
            let expected = server.password()
            if userOK, !expected.isEmpty, argument == expected {
                loggedIn = true
                return reply(230, "OK")
            }
            failures += 1
            reply(530, "Login incorrect")
            return failures < FtpServer.maxFailures
        case "QUIT":
            reply(221, "Bye")
            return false
        case "SYST": return reply(215, "UNIX Type: L8")
        case "FEAT":
            send("211-Features:\r\n PASV\r\n EPSV\r\n UTF8\r\n211 End\r\n")
            return true
        case "OPTS", "NOOP", "MODE", "STRU", "TYPE": return reply(200, "OK")
        case "AUTH", "PBSZ", "PROT": return reply(502, "Not implemented")
        default: break
        }
        guard loggedIn else { return reply(530, "Please login") }
        switch verb {
        case "PWD", "XPWD": return reply(257, "\"\(directory)\"")
        case "CWD", "XCWD":
            directory = virtualPath(argument)
            return reply(250, "OK")
        case "CDUP":
            directory = "/"
            return reply(250, "OK")
        case "MKD", "XMKD": return reply(257, "\"\(virtualPath(argument))\" created")
        case "ALLO": return reply(202, "OK")
        case "DELE", "RMD": return reply(250, "OK")
        case "SIZE", "MDTM", "RETR": return reply(550, "Not available")
        case "PORT", "EPRT": return reply(502, "Use PASV")
        case "PASV": return openPassive(extended: false)
        case "EPSV": return openPassive(extended: true)
        case "LIST", "NLST", "MLSD":
            withData { [weak self] data in
                guard let self else { return }
                guard let data else { return self.finishTransfer(425, "No data connection") }
                self.reply(150, "Listing")
                data.cancel()
                self.finishTransfer(226, "Done")
            }
            return true
        case "STOR", "APPE", "STOU":
            let last = argument.split(separator: "/").last.map(String.init) ?? ""
            let name = last.isEmpty ? "foto.jpg" : last
            withData { [weak self] data in
                guard let self else { return }
                guard let data else { return self.finishTransfer(425, "No data connection") }
                self.reply(150, "Ready")
                self.receive(data, into: Data(), tooLarge: false) { bytes in
                    guard let bytes else { return self.finishTransfer(552, "File too large") }
                    self.server.onFile(name, bytes)
                    self.finishTransfer(226, "Received")
                }
            }
            return true
        default:
            return reply(502, "Not implemented")
        }
    }

    private func virtualPath(_ argument: String) -> String {
        let path = argument.hasPrefix("/") ? argument : (directory.hasSuffix("/") ? directory : directory + "/") + argument
        return String(path.prefix(200))
    }

    private func openPassive(extended: Bool) -> Bool {
        passive?.cancel()
        dataConnection?.cancel()
        dataConnection = nil
        guard let listener = try? NWListener(using: .tcp, on: .any) else { return reply(425, "Cannot open data port") }
        passive = listener
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return connection.cancel() }
            // Solo la fotocamera che ha aperto la connessione di controllo.
            guard self.server.host(of: connection.endpoint) == self.peer, self.dataConnection == nil else { return connection.cancel() }
            connection.start(queue: self.server.queue)
            self.dataConnection = connection
            listener.cancel()
            if let pending = self.pendingTransfer {
                self.pendingTransfer = nil
                pending(connection)
            }
        }
        let localHost = control.currentPath?.localEndpoint.flatMap { server.host(of: $0) }
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let port = listener.port?.rawValue ?? 0
                let parts = localHost?.split(separator: ".") ?? []
                if extended {
                    self.reply(229, "Entering Extended Passive Mode (|||\(port)|)")
                } else if parts.count == 4 {
                    self.reply(227, "Entering Passive Mode (\(parts.joined(separator: ",")),\(port >> 8),\(port & 0xFF))")
                } else {
                    self.reply(522, "Use EPSV")
                }
            case .failed:
                self.reply(425, "Cannot open data port")
            default:
                break
            }
        }
        listener.start(queue: server.queue)
        return true
    }

    /// La connessione dei dati già aperta, o la prossima entro 30 secondi.
    private func withData(_ use: @escaping (NWConnection?) -> Void) {
        transferring = true
        if let connection = dataConnection {
            dataConnection = nil
            return use(connection)
        }
        guard passive != nil else { return use(nil) }
        pendingTransfer = { [weak self] connection in
            self?.dataConnection = nil
            use(connection)
        }
        server.queue.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self, let pending = self.pendingTransfer else { return }
            self.pendingTransfer = nil
            pending(nil)
        }
    }

    private func finishTransfer(_ code: Int, _ text: String) {
        passive?.cancel()
        passive = nil
        reply(code, text)
        transferring = false
        readLine()
    }

    /// Il file intero, o nil se supera il limite (il resto si legge e si butta).
    private func receive(_ connection: NWConnection, into data: Data, tooLarge: Bool, done: @escaping (Data?) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] chunk, _, complete, error in
            guard let self else { return }
            var data = data
            var tooLarge = tooLarge
            if let chunk, !tooLarge {
                if data.count + chunk.count > self.server.maxFileBytes {
                    tooLarge = true
                    data = Data()
                } else {
                    data.append(chunk)
                }
            }
            if complete || error != nil {
                connection.cancel()
                done(tooLarge || error != nil && !complete ? nil : data)
            } else {
                self.receive(connection, into: data, tooLarge: tooLarge, done: done)
            }
        }
    }

    @discardableResult
    private func reply(_ code: Int, _ text: String) -> Bool {
        send("\(code) \(text)\r\n")
        return true
    }

    private func send(_ text: String) {
        control.send(content: Data(text.utf8), completion: .contentProcessed { _ in })
    }
}

/// Continuazione che riprende una volta sola con la porta del server o con l'errore.
private final class PortOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<UInt16, Error>?

    init(_ continuation: CheckedContinuation<UInt16, Error>) {
        self.continuation = continuation
    }

    private func take() -> CheckedContinuation<UInt16, Error>? {
        lock.lock()
        defer { lock.unlock() }
        let pending = continuation
        continuation = nil
        return pending
    }

    func resume(_ port: UInt16) { take()?.resume(returning: port) }
    func fail(_ error: Error) { take()?.resume(throwing: error) }
}
