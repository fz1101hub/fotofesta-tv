import Foundation

/// Comandi che la TV esegue per la regia dal telefono e, in parte, per il regista di turno.
public enum PartyCommand: Equatable, Sendable {
    case next, previous, play, pause
    case approvePhoto(String), rejectPhoto(String)
    case approveMessage(String), rejectMessage(String)
    case approveAll
    case announce(String)
    case startDirector
}

/// Regia dal telefono: chi organizza apre la stessa pagina della festa, scrive il PIN mostrato sulla
/// TV e da lì manda avanti le foto, mette in pausa, approva foto e dediche, fa annunci e passa la
/// regia a un invitato per due minuti. Più telefoni possono entrare con lo stesso PIN. È la stessa
/// logica dell'app Android (`HostRemote.kt`).
///
/// Il PIN cambia a ogni festa (`reset()`); i tentativi sbagliati sono limitati per indirizzo e, dopo
/// troppi errori in tutto, la regia si blocca per qualche minuto. Thread-safe.
public final class HostRemote: @unchecked Sendable {
    /// Foto in attesa di approvazione, come la vede la pagina di regia.
    public struct PendingPhoto: Equatable, Sendable {
        public let id: String
        public let sender: String?

        public init(id: String, sender: String?) {
            self.id = id
            self.sender = sender
        }
    }

    /// Stato della TV per la pagina di regia, aggiornato dalla TV a ogni cambio.
    public struct Status: Equatable, Sendable {
        public var slideshow: Bool
        public var playing: Bool
        public var title: String?
        public var photos: Int
        public var pendingPhotos: [PendingPhoto]

        public init(slideshow: Bool = false, playing: Bool = false, title: String? = nil, photos: Int = 0, pendingPhotos: [PendingPhoto] = []) {
            self.slideshow = slideshow
            self.playing = playing
            self.title = title
            self.photos = photos
            self.pendingPhotos = pendingPhotos
        }
    }

    public enum Login: Equatable, Sendable {
        case accepted(token: String)
        case wrong, rateLimited, locked, disabled
    }

    public static let pinLength = 6
    /// Cinque tentativi al minuto per telefono.
    public static let maxAttemptsPerClient = 5
    public static let attemptWindow: TimeInterval = 60
    /// Dopo venti errori in tutto la regia si blocca per cinque minuti.
    public static let maxFailures = 20
    public static let lockTime: TimeInterval = 5 * 60
    /// Telefoni che possono fare regia insieme.
    public static let maxTokens = 8
    static let maxListed = 40
    static let maxTrackedClients = 500

    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var pin: String
    private var enabled = true
    private var tokens: [String] = []
    private var attempts: [String: [Date]] = [:]
    private var failures = 0
    private var lockedUntil = Date.distantPast
    private var status = Status()
    private var commandHandler: (@Sendable (PartyCommand) -> Void)?

    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
        pin = Self.newPin()
    }

    /// Chiamato con ogni comando accettato, dalla coda del server: la TV lo esegue sul thread principale.
    public func setOnCommand(_ handler: (@Sendable (PartyCommand) -> Void)?) {
        lock.withLock { commandHandler = handler }
    }

    /// PIN da mostrare sulla TV; nil se la regia dal telefono è spenta.
    public func currentPIN() -> String? { lock.withLock { enabled ? pin : nil } }

    public func setEnabled(_ value: Bool) {
        lock.withLock {
            enabled = value
            if !value { tokens = [] }
        }
    }

    /// Nuova festa: nuovo PIN, e chi era entrato con il vecchio deve rientrare.
    public func reset() {
        lock.withLock {
            pin = Self.newPin()
            tokens = []
            attempts = [:]
            failures = 0
            lockedUntil = .distantPast
        }
    }

    public func updateStatus(_ value: Status) {
        lock.withLock { status = value }
    }

    /// Accesso con il PIN da `client` (l'indirizzo del telefono).
    public func login(_ candidate: String?, client: String) -> Login {
        lock.withLock {
            let current = now()
            guard enabled else { return .disabled }
            guard current >= lockedUntil else { return .locked }
            var recent = (attempts[client] ?? []).filter { current.timeIntervalSince($0) < Self.attemptWindow }
            guard recent.count < Self.maxAttemptsPerClient else {
                attempts[client] = recent
                return .rateLimited
            }
            recent.append(current)
            attempts[client] = recent
            if attempts.count > Self.maxTrackedClients { attempts = attempts.filter { !$0.value.isEmpty } }
            let typed = String((candidate ?? "").filter(\.isASCII).filter(\.isNumber))
            guard Self.constantTimeEquals(typed, pin) else {
                failures += 1
                if failures >= Self.maxFailures {
                    // Troppi errori in tutto: qualcuno prova tutti i PIN. Il PIN resta quello mostrato
                    // sulla TV, ma per qualche minuto non entra nessuno.
                    lockedUntil = current.addingTimeInterval(Self.lockTime)
                    failures = 0
                }
                return .wrong
            }
            let token = Self.newToken()
            tokens.append(token)
            if tokens.count > Self.maxTokens { tokens.removeFirst(tokens.count - Self.maxTokens) }
            return .accepted(token: token)
        }
    }

    public func isHost(_ token: String?) -> Bool {
        lock.withLock {
            guard enabled, let token else { return false }
            return tokens.contains { Self.constantTimeEquals($0, token) }
        }
    }

    /// Comando dalla pagina di regia (modulo "command=...&id=...&text=..."): stato HTTP e JSON.
    public func handle(token: String?, form: [String: String]) -> (status: Int, json: String) {
        guard isHost(token) else { return (401, #"{"error":"host"}"#) }
        let id = form["id"].flatMap { value -> String? in
            (1...80).contains(value.count) && value.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) ? value : nil
        }
        let command: PartyCommand?
        switch form["command"] {
        case "next": command = .next
        case "previous": command = .previous
        case "play": command = .play
        case "pause": command = .pause
        case "approve_photo": command = id.map(PartyCommand.approvePhoto)
        case "reject_photo": command = id.map(PartyCommand.rejectPhoto)
        case "approve_message": command = id.map(PartyCommand.approveMessage)
        case "reject_message": command = id.map(PartyCommand.rejectMessage)
        case "approve_all": command = .approveAll
        case "announce": command = GuestSender.clean(form["text"], maxLength: PartyHub.maxAnnouncementLength).map(PartyCommand.announce)
        case "director": command = .startDirector
        default: command = nil
        }
        guard let command else { return (400, #"{"error":"invalid"}"#) }
        let handler = lock.withLock { commandHandler }
        handler?(command)
        return (200, #"{"status":"ok"}"#)
    }

    /// Stato per la pagina di regia, con le dediche da approvare prese da `hub`.
    public func statusJSON(hub: PartyHub) -> String {
        let current = lock.withLock { status }
        let state = HostState(
            slideshow: current.slideshow,
            playing: current.playing,
            title: current.title,
            photos: current.photos,
            pendingPhotos: current.pendingPhotos.prefix(Self.maxListed).map { HostState.Photo(id: $0.id, sender: $0.sender) },
            pendingMessages: hub.pendingMessages().prefix(Self.maxListed).map { HostState.Message(id: $0.id, text: $0.text, name: $0.name) },
            director: hub.currentDirector()?.name
        )
        guard let data = try? JSONEncoder().encode(state) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Foto che la pagina di regia può chiedere: solo quelle in attesa di approvazione.
    public func canShow(_ id: String) -> Bool {
        lock.withLock { status.pendingPhotos.contains { $0.id == id } }
    }

    private struct HostState: Encodable {
        struct Photo: Encodable {
            let id: String
            let sender: String?
        }

        struct Message: Encodable {
            let id: String
            let text: String
            let name: String?
        }

        let slideshow: Bool
        let playing: Bool
        let title: String?
        let photos: Int
        let pendingPhotos: [Photo]
        let pendingMessages: [Message]
        let director: String?
    }

    private static func newPin() -> String {
        var generator = SystemRandomNumberGenerator()
        return String((0..<pinLength).map { _ in Character(String(Int.random(in: 0...9, using: &generator))) })
    }

    private static func newToken() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<24).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }

    /// Confronto a tempo costante: la durata non rivela quanti caratteri sono giusti.
    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let left = Array(a.utf8)
        let right = Array(b.utf8)
        guard left.count == right.count else { return false }
        var difference: UInt8 = 0
        for index in left.indices { difference |= left[index] ^ right[index] }
        return difference == 0
    }
}
