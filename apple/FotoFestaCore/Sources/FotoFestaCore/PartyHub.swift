import Foundation

/// Attività della festa che arrivano dalla pagina dei telefoni oltre alle foto e ai giochi: reazioni
/// alla foto sullo schermo e dediche. La TV legge da qui le reazioni da animare, le dediche da
/// mostrare o approvare, quante reazioni ha avuto ogni foto (premio "Foto della serata") e chi ha
/// partecipato (estrazione a premi). È la stessa logica dell'app Android (`PartyHub.kt`).
///
/// Thread-safe: il server lo usa dalla sua coda, l'interfaccia dal thread principale.
public final class PartyHub: @unchecked Sendable {
    /// Cosa i telefoni possono fare; con `moderateMessages` le dediche aspettano l'approvazione.
    public struct Features: Equatable, Sendable {
        public var reactions: Bool
        public var messages: Bool
        public var moderateMessages: Bool

        public init(reactions: Bool = true, messages: Bool = true, moderateMessages: Bool = false) {
            self.reactions = reactions
            self.messages = messages
            self.moderateMessages = moderateMessages
        }
    }

    /// Reazioni possibili, nello stesso ordine dei pulsanti della pagina.
    public enum Reaction: Int, CaseIterable, Sendable {
        case heart, laugh, party, wow, clap, fire

        public var emoji: String {
            switch self {
            case .heart: "❤️"
            case .laugh: "😂"
            case .party: "🎉"
            case .wow: "😮"
            case .clap: "👏"
            case .fire: "🔥"
            }
        }
    }

    /// Reazione arrivata: `serial` cresce sempre, così la TV sa quali ha già animato.
    public struct ReactionEvent: Equatable, Sendable {
        public let serial: Int
        public let reaction: Reaction
    }

    public struct Message: Equatable, Sendable, Identifiable {
        public let id: String
        public let text: String
        public let name: String?
        public let date: Date
        public var approved: Bool
    }

    public struct Participant: Equatable, Sendable {
        public let player: String
        public let name: String

        public init(player: String, name: String) {
            self.player = player
            self.name = name
        }
    }

    /// Annuncio sullo schermo e sui telefoni; `serial` distingue due annunci uguali.
    public struct Announcement: Equatable, Sendable {
        public let serial: Int
        public let text: String
        public let date: Date
    }

    /// Invitato che dirige lo slideshow dal suo telefono fino a `until`.
    public struct Director: Equatable, Sendable {
        public let player: String
        public let name: String
        public let until: Date
    }

    public enum Result: Equatable, Sendable {
        case ok, pending, rateLimited, disabled, invalid, full
    }

    public static let maxMessageLength = 160
    public static let maxAnnouncementLength = 120
    /// L'annuncio resta sui telefoni per un minuto (sullo schermo lo decide la TV).
    public static let announcementPhoneTime: TimeInterval = 60
    public static let directorTime: TimeInterval = 120
    public static let maxMessages = 500
    public static let maxRecentReactions = 64
    /// Cinque reazioni ogni tre secondi: abbastanza per divertirsi, troppo poche per intasare lo schermo.
    public static let reactionBurst = 5
    public static let reactionWindow: TimeInterval = 3
    /// Tre dediche al minuto per telefono.
    public static let messageBurst = 3
    public static let messageWindow: TimeInterval = 60
    static let maxTrackedPlayers = 1_000

    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var features = Features()
    private var showing: String?
    private var reactionCounts: [String: Int] = [:]
    private var recentReactions: [ReactionEvent] = []
    private var serial = 0
    private var reactionTimes: [String: [Date]] = [:]
    private var messageTimes: [String: [Date]] = [:]
    private var messages: [Message] = []
    private var names: [String: String] = [:]
    private var nameOrder: [String] = []
    /// Cresce a ogni cambio delle dediche o delle impostazioni: l'interfaccia lo confronta per aggiornarsi.
    private var revision = 0
    private var announcement: Announcement?
    private var announcementSerial = 0
    private var director: Director?
    private var directorHandler: (@Sendable (PartyCommand) -> Void)?

    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
    }

    public func setFeatures(_ value: Features) {
        lock.withLock {
            features = value
            // Senza moderazione le dediche in attesa non avrebbero più modo di comparire.
            if !value.moderateMessages { approvePendingLocked() }
            revision += 1
        }
    }

    public func currentFeatures() -> Features { lock.withLock { features } }

    /// Foto sullo schermo in questo momento: le reazioni dei telefoni contano per lei.
    public func showing(_ photoID: String?) {
        lock.withLock { showing = photoID }
    }

    /// Reazione di un telefono alla foto sullo schermo; `index` è la posizione in `Reaction`.
    public func react(player: String, index: Int, name: String?) -> Result {
        lock.withLock {
            guard features.reactions else { return .disabled }
            guard let reaction = Reaction(rawValue: index), PhoneGame.isValidPlayer(player) else { return .invalid }
            guard allow(&reactionTimes, player: player, burst: Self.reactionBurst, window: Self.reactionWindow) else { return .rateLimited }
            _ = remember(player: player, name: name)
            if let showing { reactionCounts[showing, default: 0] += 1 }
            serial += 1
            recentReactions.append(ReactionEvent(serial: serial, reaction: reaction))
            if recentReactions.count > Self.maxRecentReactions { recentReactions.removeFirst(recentReactions.count - Self.maxRecentReactions) }
            return .ok
        }
    }

    /// Reazioni arrivate dopo `serial`, dalle più vecchie: le ultime `maxRecentReactions`.
    public func reactions(after serial: Int) -> [ReactionEvent] {
        lock.withLock { recentReactions.filter { $0.serial > serial } }
    }

    /// Numero dell'ultima reazione arrivata (0 se nessuna).
    public func lastReactionSerial() -> Int { lock.withLock { serial } }

    public func reactionCount(_ photoID: String) -> Int { lock.withLock { reactionCounts[photoID] ?? 0 } }

    /// Reazioni ricevute da ogni foto, per il premio "Foto della serata".
    public func allReactionCounts() -> [String: Int] { lock.withLock { reactionCounts } }

    /// Dedica di un telefono: testo ripulito, al massimo `maxMessageLength` caratteri.
    public func message(player: String, text: String?, name: String?) -> Result {
        lock.withLock {
            guard features.messages else { return .disabled }
            guard let cleaned = GuestSender.clean(text, maxLength: Self.maxMessageLength), PhoneGame.isValidPlayer(player) else { return .invalid }
            guard messages.count < Self.maxMessages else { return .full }
            guard allow(&messageTimes, player: player, burst: Self.messageBurst, window: Self.messageWindow) else { return .rateLimited }
            let signature = remember(player: player, name: name)
            let approved = !features.moderateMessages
            messages.append(Message(id: UUID().uuidString, text: cleaned, name: signature, date: now(), approved: approved))
            revision += 1
            return approved ? .ok : .pending
        }
    }

    /// Dediche approvate, dalla più vecchia: quelle da mostrare e il libro degli ospiti.
    public func approvedMessages() -> [Message] { lock.withLock { messages.filter(\.approved) } }

    public func pendingMessages() -> [Message] { lock.withLock { messages.filter { !$0.approved } } }

    /// Cambia a ogni dedica arrivata, approvata o tolta.
    public func currentRevision() -> Int { lock.withLock { revision } }

    public func approveMessage(_ id: String) {
        lock.withLock {
            if let index = messages.firstIndex(where: { $0.id == id }) { messages[index].approved = true }
            revision += 1
        }
    }

    public func approveAllMessages() {
        lock.withLock {
            approvePendingLocked()
            revision += 1
        }
    }

    public func removeMessage(_ id: String) {
        lock.withLock {
            messages.removeAll { $0.id == id }
            revision += 1
        }
    }

    /// Chi ha partecipato con un nome (reazioni o dediche), nell'ordine in cui è arrivato.
    public func participants() -> [Participant] {
        lock.withLock { nameOrder.compactMap { player in names[player].map { Participant(player: player, name: $0) } } }
    }

    /// Comandi del regista di turno (avanti, indietro, pausa), dalla coda del server.
    public func setOnDirectorCommand(_ handler: (@Sendable (PartyCommand) -> Void)?) {
        lock.withLock { directorHandler = handler }
    }

    /// Annuncio della regia ("Il buffet è aperto!"): compare sullo schermo e sui telefoni.
    @discardableResult
    public func announce(_ text: String?) -> Bool {
        guard let cleaned = GuestSender.clean(text, maxLength: Self.maxAnnouncementLength) else { return false }
        lock.withLock {
            announcementSerial += 1
            announcement = Announcement(serial: announcementSerial, text: cleaned, date: now())
            revision += 1
        }
        return true
    }

    /// Ultimo annuncio, se è di meno di `announcementPhoneTime` secondi fa.
    public func currentAnnouncement() -> Announcement? {
        lock.withLock {
            guard let announcement, now().timeIntervalSince(announcement.date) < Self.announcementPhoneTime else { return nil }
            return announcement
        }
    }

    /// Regista per due minuti: un invitato estratto tra chi ha partecipato con un nome dirige lo
    /// slideshow dal suo telefono. Nil se nessuno ha ancora scritto il proprio nome.
    @discardableResult
    public func startDirector(duration: TimeInterval = PartyHub.directorTime) -> Director? {
        lock.withLock {
            guard let player = nameOrder.filter({ names[$0] != nil }).randomElement(), let name = names[player] else { return nil }
            let chosen = Director(player: player, name: name, until: now().addingTimeInterval(duration))
            director = chosen
            revision += 1
            return chosen
        }
    }

    public func stopDirector() {
        lock.withLock {
            director = nil
            revision += 1
        }
    }

    /// Regista di turno, se il suo tempo non è finito.
    public func currentDirector() -> Director? {
        lock.withLock { directorLocked() }
    }

    func director() -> Director? { currentDirector() }

    /// Annuncio e regista di turno per la pagina del telefono `player`, in JSON.
    public func partyJSON(player: String?) -> String {
        let current = currentAnnouncement()
        let (chosen, remaining): (Director?, Int) = lock.withLock {
            let chosen = directorLocked()
            return (chosen, chosen.map { max(Int($0.until.timeIntervalSince(now())), 0) } ?? 0)
        }
        var parts: [String] = []
        if let current {
            parts.append(#""announcement":{"id":\#(current.serial),"text":\#(Self.json(current.text))}"#)
        } else {
            parts.append(#""announcement":null"#)
        }
        if let chosen {
            let you = player != nil && player == chosen.player
            parts.append(#""director":{"name":\#(Self.json(chosen.name)),"you":\#(you),"seconds":\#(remaining)}"#)
        } else {
            parts.append(#""director":null"#)
        }
        return "{" + parts.joined(separator: ",") + "}"
    }

    /// Fine della festa: si cancella tutto.
    public func clear() {
        lock.withLock {
            announcement = nil
            director = nil
            showing = nil
            reactionCounts = [:]
            recentReactions = []
            reactionTimes = [:]
            messageTimes = [:]
            messages = []
            names = [:]
            nameOrder = []
            revision += 1
        }
    }

    /// Cosa può fare la pagina dei telefoni, in JSON.
    public func featuresJSON() -> String {
        lock.withLock {
            #"{"reactions":\#(features.reactions),"messages":\#(features.messages),"moderated":\#(features.moderateMessages)}"#
        }
    }

    /// Richiesta di un telefono (modulo "type=...&player=..."): stato HTTP e risposta JSON.
    public func handle(_ form: [String: String]) -> (status: Int, json: String) {
        let player = form["player"] ?? ""
        let name = GuestSender.clean(form["name"])
        let result: Result
        switch form["type"] {
        case "react": result = react(player: player, index: form["reaction"].flatMap { Int($0) } ?? -1, name: name)
        case "message": result = message(player: player, text: form["text"], name: name)
        case "director": result = direct(player: player, command: form["command"])
        default: result = .invalid
        }
        switch result {
        case .ok: return (200, #"{"status":"ok"}"#)
        case .pending: return (200, #"{"status":"pending"}"#)
        case .rateLimited: return (429, #"{"error":"rate"}"#)
        case .disabled: return (403, #"{"error":"disabled"}"#)
        case .full: return (507, #"{"error":"full"}"#)
        case .invalid: return (400, #"{"error":"invalid"}"#)
        }
    }

    /// Comando del regista di turno: solo dal suo telefono e solo finché dura il suo turno.
    private func direct(player: String, command: String?) -> Result {
        let (allowed, handler) = lock.withLock { (directorLocked()?.player == player, directorHandler) }
        guard allowed else { return .disabled }
        let action: PartyCommand
        switch command {
        case "next": action = .next
        case "previous": action = .previous
        case "play": action = .play
        case "pause": action = .pause
        default: return .invalid
        }
        handler?(action)
        return .ok
    }

    /// Stringa JSON tra virgolette, con i caratteri speciali protetti.
    static func json(_ text: String) -> String {
        guard let data = try? JSONEncoder().encode(text) else { return "\"\"" }
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: - Da chiamare con il lock preso

    private func directorLocked() -> Director? {
        guard let director, now() < director.until else { return nil }
        return director
    }

    private func approvePendingLocked() {
        for index in messages.indices where !messages[index].approved { messages[index].approved = true }
    }

    /// Ricorda il nome del telefono e restituisce quello da mostrare (l'ultimo scritto).
    private func remember(player: String, name: String?) -> String? {
        if let name, names[player] != nil || names.count < Self.maxTrackedPlayers {
            if names[player] == nil { nameOrder.append(player) }
            names[player] = name
        }
        return names[player] ?? name
    }

    /// Al massimo `burst` azioni in `window` secondi dallo stesso telefono.
    private func allow(_ times: inout [String: [Date]], player: String, burst: Int, window: TimeInterval) -> Bool {
        let current = now()
        var recent = (times[player] ?? []).filter { current.timeIntervalSince($0) < window }
        guard recent.count < burst else {
            times[player] = recent
            return false
        }
        recent.append(current)
        times[player] = recent
        // I telefoni passati di qui sono al massimo qualche centinaio: la mappa non cresce senza limite.
        if times.count > Self.maxTrackedPlayers { times = times.filter { !$0.value.isEmpty } }
        return true
    }
}

/// Estrazione a premi tra gli invitati che hanno partecipato con un nome: foto firmate, giochi,
/// reazioni e dediche. Lo stesso nome scritto in modi diversi (maiuscole, spazi) conta una volta, e
/// chi ha già vinto non viene estratto di nuovo finché si ricomincia.
public enum PrizeDraw {
    /// Nomi diversi, nell'ordine in cui compaiono la prima volta, scritti come la prima volta.
    public static func candidates(_ groups: [String?]...) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for name in groups.joined() {
            guard let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { continue }
            if seen.insert(key(trimmed)).inserted { result.append(trimmed) }
        }
        return result
    }

    /// Un vincitore tra `candidates` escluso chi è in `winners`; nil se non resta nessuno.
    public static func draw<G: RandomNumberGenerator>(_ candidates: [String], winners: [String], using generator: inout G) -> String? {
        let excluded = Set(winners.map(key))
        return candidates.filter { !excluded.contains(key($0)) }.randomElement(using: &generator)
    }

    public static func draw(_ candidates: [String], winners: [String]) -> String? {
        var generator = SystemRandomNumberGenerator()
        return draw(candidates, winners: winners, using: &generator)
    }

    static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}
