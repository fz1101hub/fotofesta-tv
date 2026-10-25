import Foundation

/// Giochi della festa con i telefoni, oltre ai quiz di `PhoneGame`: disegna e indovina, muro dei
/// disegni, romanzo a più mani, telefono senza fili, complimenti segreti, missioni fotografiche,
/// paparazzi, lo stesso istante, ricrea la foto, chi è la talpa e capsula del tempo. In più la caccia
/// al QR, i telefoni come luci e le monete della festa, che ogni gioco assegna.
///
/// La TV sceglie il gioco e i testi (già tradotti: parole da disegnare, missioni, frasi); la pagina dei
/// telefoni legge da `phoneJSON` cosa mostrare e manda i contributi con `handle`. I nomi degli
/// invitati sono quelli di `hub`. È la stessa logica dell'app Android (`PartyPlay.kt`).
///
/// Thread-safe: il server lo usa dalla sua coda, l'interfaccia dal thread principale.
public final class PartyPlay: @unchecked Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case drawGuess = "draw_guess"
        case drawingWall = "wall"
        case story
        case whisper
        case compliments
        case missions
        case paparazzi
        case sameMoment = "moment"
        case recreate
        case mole
        case capsule
    }

    /// Contributo di un telefono: una frase, un disegno o un tentativo.
    public struct Entry: Equatable, Sendable, Identifiable {
        public let id: String
        public let player: String
        public let name: String?
        public let text: String?
        public let drawing: String?
        public let date: Date
    }

    /// Gioco in corso come lo vede la TV.
    public struct State: Equatable, Sendable {
        public let kind: Kind
        public let round: String
        /// Testo per tutti, già tradotto dalla TV.
        public let prompt: String
        /// Chi disegna, la talpa o la star dei paparazzi.
        public let special: PartyHub.Participant?
        /// Parola da disegnare, missione della talpa, frase iniziale o foto da rifare.
        public let secret: String?
        public let entries: [Entry]
        public let open: Bool
        /// Chi è la talpa: si vota.
        public let voting: Bool
        public let revealed: Bool
        /// Disegna e indovina: chi ha indovinato.
        public let winner: PartyHub.Participant?
        /// Lo stesso istante: quando scattare.
        public let momentAt: Date?
        public let startedAt: Date
        /// Chi è la talpa: voti per ogni invitato sospettato, per nome, dal più votato.
        public let votes: [Vote]
    }

    public struct Vote: Equatable, Sendable {
        public let name: String
        public let votes: Int
    }

    /// Foto arrivata durante un gioco fotografico: la TV la mostra con il suo perché.
    public struct Tag: Equatable, Sendable {
        public let kind: Kind
        public let round: String
        public let text: String?
    }

    public struct Coins: Equatable, Sendable {
        public let name: String
        public let coins: Int
    }

    /// Telefoni come luci: effetto e momento d'inizio, uguale per tutti.
    public enum Lights: String, CaseIterable, Sendable {
        case rainbow, wave, pulse, candle
    }

    public enum Result: Equatable, Sendable {
        case ok, correct, wrong, closed, invalid, rateLimited, tooLate, already
    }

    public static let maxPromptLength = 160
    public static let maxTextLength = 200
    public static let maxEntries = 400
    public static let maxTags = 2_000
    public static let maxPlayers = 1_000
    public static let maxWhisperSteps = 12
    /// Un disegno: al massimo 20.000 caratteri ("c,x,y,x,y;c,x,y...").
    public static let maxDrawingLength = 20_000
    public static let maxStrokes = 400
    public static let colors = 8
    public static let entryGap: TimeInterval = 2
    /// Chi disegna manda il disegno che cresce ogni secondo circa.
    public static let drawLiveGap: TimeInterval = 0.7
    public static let whisperTurn: TimeInterval = 90
    public static let momentDelay: TimeInterval = 15
    public static let momentWindow: TimeInterval = 60
    public static let treasureTime: TimeInterval = 30

    private final class Running {
        let kind: Kind
        let round: String
        let prompt: String
        let special: PartyHub.Participant?
        let secret: String?
        let pool: [String]
        let momentAt: Date?
        let startedAt: Date
        var entries: [Entry] = []
        var open = true
        var voting = false
        var revealed = false
        var winner: PartyHub.Participant?
        /// Chi è la talpa: voto di ogni telefono, nell'ordine di arrivo.
        var votes: [(voter: String, suspect: String)] = []
        var missions: [String: String] = [:]
        var rewarded: Set<String> = []
        var rewardOrder = 0
        /// Telefono senza fili: chi ha il turno e fino a quando.
        var holder: String?
        var holderUntil = Date.distantPast

        init(kind: Kind, round: String, prompt: String, special: PartyHub.Participant?, secret: String?, pool: [String], momentAt: Date?, startedAt: Date) {
            self.kind = kind
            self.round = round
            self.prompt = prompt
            self.special = special
            self.secret = secret
            self.pool = pool
            self.momentAt = momentAt
            self.startedAt = startedAt
        }
    }

    private let hub: PartyHub
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    /// Indice a caso in 0..<n: i test ne passano uno prevedibile.
    private let random: @Sendable (Int) -> Int
    private var running: Running?
    private var tags: [String: Tag] = [:]
    private var tagOrder: [String] = []
    private var coins: [String: Int] = [:]
    private var coinOrder: [String] = []
    private var lastEntry: [String: Date] = [:]
    private var treasure: String?
    private var treasureUntil = Date.distantPast
    private var treasureFound: [String] = []
    private var lights: Lights?
    private var lightsStarted = Date.distantPast
    private var revision = 0

    public init(hub: PartyHub, now: @escaping @Sendable () -> Date = { Date() }, random: @escaping @Sendable (Int) -> Int = { Int.random(in: 0..<$0) }) {
        self.hub = hub
        self.now = now
        self.random = random
    }

    /// Nuovo gioco. `pool` sono i testi tra cui scegliere (parole da disegnare, missioni, frasi
    /// iniziali); per "Ricrea la foto" il primo è l'identificativo della foto da rifare. Restituisce
    /// nil se servono invitati con il nome e non ce ne sono abbastanza.
    @discardableResult
    public func start(_ kind: Kind, prompt: String, pool: [String] = []) -> State? {
        lock.withLock {
            let people = hub.participants()
            let needsSpecial = kind == .drawGuess || kind == .mole || kind == .paparazzi
            if needsSpecial && people.isEmpty { return nil }
            if kind == .compliments && people.count < 2 { return nil }
            let special = needsSpecial ? people[min(max(random(people.count), 0), people.count - 1)] : nil
            let secret: String?
            switch kind {
            case .drawGuess, .mole, .whisper: secret = pool.isEmpty ? nil : pool[min(max(random(pool.count), 0), pool.count - 1)]
            case .story, .recreate: secret = pool.first
            default: secret = nil
            }
            let current = now()
            running = Running(
                kind: kind,
                round: String(UUID().uuidString.prefix(12)),
                prompt: GuestSender.clean(prompt, maxLength: Self.maxPromptLength) ?? "",
                special: special,
                secret: secret,
                pool: pool,
                momentAt: kind == .sameMoment ? current.addingTimeInterval(Self.momentDelay) : nil,
                startedAt: current
            )
            revision += 1
            return stateLocked()
        }
    }

    /// Chi è la talpa: si smette di giocare e si vota.
    public func startVoting() {
        lock.withLock {
            guard let current = running, current.kind == .mole, !current.revealed else { return }
            current.voting = true
            revision += 1
        }
    }

    /// Fine del gioco: si chiudono i contributi e si mostra il risultato. Le monete si assegnano qui
    /// per i giochi che si decidono alla fine.
    public func reveal() {
        lock.withLock {
            guard let current = running, !current.revealed else { return }
            current.open = false
            current.voting = false
            current.revealed = true
            if current.kind == .mole, let mole = current.special {
                if mostVoted(current) == mole.player {
                    for vote in current.votes where vote.suspect == mole.player { addCoins(vote.voter, 2) }
                } else {
                    addCoins(mole.player, 5)
                }
            }
            revision += 1
        }
    }

    /// Nessun gioco in corso; le monete e le foto già etichettate restano.
    public func stop() {
        lock.withLock {
            running = nil
            revision += 1
        }
    }

    public func current() -> State? { lock.withLock { stateLocked() } }

    /// Cresce a ogni cambio: l'interfaccia lo confronta per aggiornarsi.
    public func currentRevision() -> Int { lock.withLock { revision } }

    /// Chi è la talpa: è stata scoperta? (dopo `reveal`)
    public func moleCaught() -> Bool {
        lock.withLock {
            guard let current = running, current.kind == .mole, current.revealed, let mole = current.special else { return false }
            return mostVoted(current) == mole.player
        }
    }

    /// Foto arrivata dal telefono `player` durante un gioco fotografico: missione, paparazzi, lo
    /// stesso istante o ricrea la foto. Restituisce l'etichetta da mostrare con la foto, se c'è.
    @discardableResult
    public func photoArrived(player: String?, photoID: String) -> Tag? {
        lock.withLock {
            guard let current = running, let player, current.open else { return nil }
            let tag: Tag
            switch current.kind {
            case .missions:
                guard let mission = missionLocked(current, player: player) else { return nil }
                if current.rewarded.insert(player).inserted { addCoins(player, 2) }
                tag = Tag(kind: .missions, round: current.round, text: mission)
            case .paparazzi:
                guard let star = current.special, player != star.player else { return nil }
                if current.rewarded.count < 3, current.rewarded.insert(player).inserted { addCoins(player, 4 - current.rewarded.count) }
                tag = Tag(kind: .paparazzi, round: current.round, text: star.name)
            case .sameMoment:
                // Valgono le foto scattate (e arrivate) nel minuto dopo il via.
                guard let momentAt = current.momentAt else { return nil }
                let time = now()
                guard time >= momentAt, time <= momentAt.addingTimeInterval(Self.momentWindow) else { return nil }
                if current.rewarded.insert(player).inserted { addCoins(player, 1) }
                tag = Tag(kind: .sameMoment, round: current.round, text: nil)
            case .recreate:
                if current.rewarded.insert(player).inserted { addCoins(player, 2) }
                tag = Tag(kind: .recreate, round: current.round, text: current.secret)
            default:
                return nil
            }
            if tags[photoID] == nil { tagOrder.append(photoID) }
            tags[photoID] = tag
            while tagOrder.count > Self.maxTags { tags[tagOrder.removeFirst()] = nil }
            revision += 1
            return tag
        }
    }

    public func tag(_ photoID: String) -> Tag? { lock.withLock { tags[photoID] } }

    /// Foto etichettate nel gioco `round`, dalla prima arrivata.
    public func taggedPhotos(round: String) -> [String] {
        lock.withLock { tagOrder.filter { tags[$0]?.round == round } }
    }

    /// Monete della festa per `player` (scommesse vinte, quiz...).
    public func award(_ player: String, amount: Int) {
        guard amount > 0, PhoneGame.isValidPlayer(player) else { return }
        lock.withLock {
            addCoins(player, amount)
            revision += 1
        }
    }

    /// Classifica delle monete: più monete prima; a parità, chi ha cominciato prima.
    public func leaderboard(limit: Int = 10) -> [Coins] {
        lock.withLock {
            let names = Dictionary(hub.participants().map { ($0.player, $0.name) }, uniquingKeysWith: { first, _ in first })
            return coinOrder.enumerated()
                .sorted { (coins[$0.element] ?? 0, -$0.offset) > (coins[$1.element] ?? 0, -$1.offset) }
                .prefix(limit)
                .map { Coins(name: names[$0.element] ?? PhoneGame.defaultName, coins: coins[$0.element] ?? 0) }
        }
    }

    public func coins(of player: String) -> Int { lock.withLock { coins[player] ?? 0 } }

    /// Caccia al QR: un codice valido per `treasureTime`; i primi tre telefoni che lo inquadrano vincono.
    @discardableResult
    public func startTreasure() -> String {
        lock.withLock {
            let token = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16))
            treasure = token
            treasureUntil = now().addingTimeInterval(Self.treasureTime)
            treasureFound = []
            revision += 1
            return token
        }
    }

    /// Codice della caccia in corso, se non è scaduto e non l'hanno già trovato in tre.
    public func currentTreasure() -> String? {
        lock.withLock {
            guard let treasure, now() < treasureUntil, treasureFound.count < 3 else { return nil }
            return treasure
        }
    }

    public func treasureFinders() -> [String] {
        lock.withLock { treasureFound.map { hub.nameOf($0) ?? PhoneGame.defaultName } }
    }

    public func setLights(_ value: Lights?) {
        lock.withLock {
            lights = value
            lightsStarted = now()
            revision += 1
        }
    }

    public func currentLights() -> Lights? { lock.withLock { lights } }

    /// Fine della festa: si cancella tutto, monete comprese.
    public func clear() {
        lock.withLock {
            running = nil
            tags = [:]
            tagOrder = []
            coins = [:]
            coinOrder = []
            lastEntry = [:]
            treasure = nil
            treasureFound = []
            lights = nil
            revision += 1
        }
    }

    /// Cosa mostra la pagina del telefono `player`, in JSON.
    public func phoneJSON(player: String?) -> String {
        let object: [String: Any] = lock.withLock {
            let time = now()
            var json: [String: Any] = ["now": Int64(time.timeIntervalSince1970 * 1000), "coins": player.flatMap { coins[$0] } ?? 0]
            if let lights {
                json["lights"] = ["pattern": lights.rawValue, "started": Int64(lightsStarted.timeIntervalSince1970 * 1000)] as [String: Any]
            }
            guard let current = running, let player, PhoneGame.isValidPlayer(player) else {
                json["game"] = NSNull()
                return json
            }
            if current.kind == .whisper { claimWhisperLocked(current, player: player, now: time) }
            json["game"] = gameJSONLocked(current, player: player, now: time)
            return json
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return #"{"game":null}"# }
        return String(decoding: data, as: UTF8.self)
    }

    /// Contributo di un telefono (modulo "type=play&command=...&round=..."): stato HTTP e JSON.
    public func handle(_ form: [String: String]) -> (status: Int, json: String) {
        let player = form["player"] ?? ""
        guard PhoneGame.isValidPlayer(player) else { return (400, #"{"error":"invalid"}"#) }
        hub.join(player: player, name: form["name"])
        let result: Result
        switch form["command"] {
        case "treasure": result = claimTreasure(player: player, token: form["token"])
        case "vote": result = vote(player: player, round: form["round"], target: form["target"])
        default: result = contribute(player: player, round: form["round"], text: form["text"], drawing: form["drawing"], target: form["target"])
        }
        switch result {
        case .ok: return (200, #"{"status":"ok"}"#)
        case .correct: return (200, #"{"status":"correct"}"#)
        case .wrong: return (200, #"{"status":"wrong"}"#)
        case .closed: return (409, #"{"error":"closed"}"#)
        case .invalid: return (400, #"{"error":"invalid"}"#)
        case .rateLimited: return (429, #"{"error":"rate"}"#)
        case .tooLate: return (410, #"{"error":"late"}"#)
        case .already: return (409, #"{"error":"already"}"#)
        }
    }

    private func claimTreasure(player: String, token: String?) -> Result {
        lock.withLock {
            guard let current = treasure else { return .tooLate }
            guard let token, HostRemote.constantTimeEquals(token, current) else { return .invalid }
            guard now() < treasureUntil, treasureFound.count < 3 else { return .tooLate }
            guard !treasureFound.contains(player) else { return .already }
            treasureFound.append(player)
            addCoins(player, 4 - treasureFound.count)
            revision += 1
            return .ok
        }
    }

    private func vote(player: String, round: String?, target: String?) -> Result {
        lock.withLock {
            guard let current = running, current.round == round, current.kind == .mole, current.voting else { return .closed }
            guard let suspect = hub.participants().first(where: { $0.name == target && $0.player != player }) else { return .invalid }
            current.votes.removeAll { $0.voter == player }
            current.votes.append((player, suspect.player))
            revision += 1
            return .ok
        }
    }

    private func contribute(player: String, round: String?, text rawText: String?, drawing rawDrawing: String?, target: String?) -> Result {
        // I complimenti diventano dediche anonime: approvazione e limiti sono quelli delle dediche.
        enum Compliment { case notCompliment, closed, invalid, send(PartyHub.Participant, String) }
        let compliment: Compliment = lock.withLock {
            guard let current = running, current.round == round, current.open else { return .closed }
            guard current.kind == .compliments else { return .notCompliment }
            guard let to = hub.participants().first(where: { $0.name == target && $0.player != player }) else { return .invalid }
            guard let text = GuestSender.clean(rawText, maxLength: PartyHub.maxMessageLength - to.name.count - 4) else { return .invalid }
            return .send(to, text)
        }
        switch compliment {
        case .closed: return .closed
        case .invalid: return .invalid
        case let .send(to, text):
            let result = hub.message(player: player, text: "💌 \(to.name): \(text)", name: nil, anonymous: true)
            switch result {
            case .ok, .pending:
                lock.withLock {
                    if let current = running, current.rewarded.insert(player).inserted { addCoins(player, 1) }
                    revision += 1
                }
                return .ok
            case .rateLimited: return .rateLimited
            case .disabled: return .closed
            default: return .invalid
            }
        case .notCompliment:
            break
        }
        return lock.withLock {
            guard let current = running, current.round == round, current.open, !current.voting else { return .closed }
            let time = now()
            let text = GuestSender.clean(rawText, maxLength: Self.maxTextLength)
            let drawing = rawDrawing.flatMap { Self.isValidDrawing($0) ? $0 : nil }
            let drawer = current.special?.player == player
            let gap = current.kind == .drawGuess && drawer ? Self.drawLiveGap : Self.entryGap
            if let last = lastEntry[player], time.timeIntervalSince(last) < gap { return .rateLimited }
            guard current.entries.count < Self.maxEntries else { return .closed }
            let name = hub.nameOf(player)
            let result: Result
            switch current.kind {
            case .drawGuess:
                if drawer {
                    guard let drawing else { return .invalid }
                    // Il disegno in corso sostituisce quello di prima: la TV lo vede crescere.
                    current.entries.removeAll { $0.player == player && $0.drawing != nil }
                    current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: nil, drawing: drawing, date: time))
                    result = .ok
                } else {
                    guard let text else { return .invalid }
                    current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: text, drawing: nil, date: time))
                    if let word = current.secret, Self.guessMatches(text, word: word) {
                        current.open = false
                        current.revealed = true
                        current.winner = PartyHub.Participant(player: player, name: name ?? PhoneGame.defaultName)
                        addCoins(player, 3)
                        if let special = current.special { addCoins(special.player, 2) }
                        result = .correct
                    } else {
                        result = .wrong
                    }
                }
            case .drawingWall:
                guard let drawing else { return .invalid }
                current.entries.removeAll { $0.player == player }
                current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: nil, drawing: drawing, date: time))
                if current.rewarded.insert(player).inserted { addCoins(player, 1) }
                result = .ok
            case .story:
                guard let text else { return .invalid }
                // Nessuno scrive due frasi di fila: il bello è continuare quella di un altro.
                if current.entries.last?.player == player { return .already }
                current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: text, drawing: nil, date: time))
                addCoins(player, 1)
                result = .ok
            case .whisper:
                guard current.holder == player, time <= current.holderUntil else { return .closed }
                let wantsDrawing = current.entries.count % 2 == 0
                if wantsDrawing {
                    guard let drawing else { return .invalid }
                    current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: nil, drawing: drawing, date: time))
                } else {
                    guard let text else { return .invalid }
                    current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: text, drawing: nil, date: time))
                }
                current.holder = nil
                addCoins(player, 1)
                if current.entries.count >= Self.maxWhisperSteps { current.open = false }
                result = .ok
            case .capsule:
                guard let text else { return .invalid }
                current.entries.removeAll { $0.player == player }
                current.entries.append(Entry(id: Self.newID(), player: player, name: name, text: text, drawing: nil, date: time))
                if current.rewarded.insert(player).inserted { addCoins(player, 1) }
                result = .ok
            default:
                return .invalid
            }
            lastEntry[player] = time
            revision += 1
            return result
        }
    }

    // MARK: - Da chiamare con il lock preso

    private func stateLocked() -> State? {
        guard let current = running else { return nil }
        var tally: [String: Int] = [:]
        var order: [String] = []
        for vote in current.votes {
            let name = hub.nameOf(vote.suspect) ?? PhoneGame.defaultName
            if tally[name] == nil { order.append(name) }
            tally[name, default: 0] += 1
        }
        let votes = order.map { Vote(name: $0, votes: tally[$0] ?? 0) }.sorted { $0.votes > $1.votes }
        return State(
            kind: current.kind,
            round: current.round,
            prompt: current.prompt,
            special: current.special,
            secret: current.secret,
            entries: current.entries,
            open: current.open,
            voting: current.voting,
            revealed: current.revealed,
            winner: current.winner,
            momentAt: current.momentAt,
            startedAt: current.startedAt,
            votes: votes
        )
    }

    private func gameJSONLocked(_ current: Running, player: String, now time: Date) -> [String: Any] {
        var json: [String: Any] = [
            "round": current.round,
            "kind": current.kind.rawValue,
            "prompt": current.prompt,
            "open": current.open,
            "voting": current.voting,
            "revealed": current.revealed,
        ]
        let special = current.special
        let you = special?.player == player
        json["you"] = you
        if let special { json["special"] = special.name }
        var input = "none"
        switch current.kind {
        case .drawGuess:
            input = you ? "draw" : "text"
            if you, let secret = current.secret { json["secret"] = secret }
            if let winner = current.winner { json["winner"] = winner.name }
            if current.revealed, let secret = current.secret { json["answer"] = secret }
        case .drawingWall:
            input = "draw"
        case .story:
            input = "text"
            // Si vede solo l'ultima frase, come nel gioco su carta.
            if let previous = current.entries.last?.text ?? current.secret { json["previous"] = previous }
            json["count"] = current.entries.count
            json["mine"] = current.entries.last?.player == player
        case .whisper:
            let holding = current.holder == player && time <= current.holderUntil
            json["turn"] = holding
            json["count"] = current.entries.count
            if holding {
                if let previous = current.entries.last {
                    if let drawing = previous.drawing { json["previousDrawing"] = drawing } else if let text = previous.text { json["previous"] = text }
                } else if let secret = current.secret {
                    json["previous"] = secret
                }
                input = current.entries.count % 2 == 0 ? "draw" : "text"
                json["seconds"] = max(Int(current.holderUntil.timeIntervalSince(time)), 0)
            }
            json["done"] = current.entries.contains { $0.player == player }
        case .compliments:
            input = "text"
            json["choices"] = hub.participants().filter { $0.player != player }.map(\.name)
        case .missions:
            if let mission = missionLocked(current, player: player) { json["secret"] = mission }
        case .paparazzi, .recreate:
            break
        case .sameMoment:
            if let momentAt = current.momentAt { json["moment"] = Int64(momentAt.timeIntervalSince1970 * 1000) }
        case .mole:
            if you, let secret = current.secret { json["secret"] = secret }
            if current.voting {
                input = "choice"
                json["choices"] = hub.participants().filter { $0.player != player }.map(\.name)
                if let voted = current.votes.last(where: { $0.voter == player }), let name = hub.nameOf(voted.suspect) { json["voted"] = name }
            }
            if current.revealed { json["caught"] = special != nil && mostVoted(current) == special?.player }
        case .capsule:
            input = "text"
            if current.revealed {
                json["entries"] = current.entries.map { entry in (entry.name.map { name in "\(name): " } ?? "") + (entry.text ?? "") }
            }
        }
        if !current.open { input = "none" }
        json["input"] = input
        json["sent"] = current.entries.contains { $0.player == player } || current.rewarded.contains(player)
        return json
    }

    /// Telefono senza fili: il primo telefono libero che non ha ancora giocato prende il turno.
    private func claimWhisperLocked(_ current: Running, player: String, now time: Date) {
        guard current.open else { return }
        if current.holder != nil, time <= current.holderUntil { return }
        if current.entries.contains(where: { $0.player == player }) { return }
        current.holder = player
        current.holderUntil = time.addingTimeInterval(Self.whisperTurn)
        revision += 1
    }

    private func missionLocked(_ current: Running, player: String) -> String? {
        guard !current.pool.isEmpty else { return nil }
        if let mission = current.missions[player] { return mission }
        let mission = current.pool[current.missions.count % current.pool.count]
        current.missions[player] = mission
        return mission
    }

    private func mostVoted(_ current: Running) -> String? {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for vote in current.votes {
            if counts[vote.suspect] == nil { order.append(vote.suspect) }
            counts[vote.suspect, default: 0] += 1
        }
        // A parità vince il primo votato, come in Android.
        var best: String?
        var bestCount = 0
        for suspect in order where (counts[suspect] ?? 0) > bestCount {
            best = suspect
            bestCount = counts[suspect] ?? 0
        }
        return best
    }

    private func addCoins(_ player: String, _ amount: Int) {
        if coins[player] == nil {
            guard coins.count < Self.maxPlayers else { return }
            coinOrder.append(player)
        }
        coins[player, default: 0] += amount
    }

    private static func newID() -> String { String(UUID().uuidString.prefix(12)) }

    // MARK: - Disegni e parole

    /// Disegno valido: tratti separati da ";", ognuno "colore,x,y,x,y..." con colore 0–7 e coordinate
    /// intere 0–1000 (la pagina le riduce alla tela quadrata).
    public static func isValidDrawing(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= maxDrawingLength else { return false }
        guard value.utf8.allSatisfy({ ($0 >= 48 && $0 <= 57) || $0 == 44 || $0 == 59 }) else { return false }
        let strokes = value.split(separator: ";", omittingEmptySubsequences: false)
        guard strokes.count <= maxStrokes else { return false }
        return strokes.allSatisfy { stroke in
            let numbers = stroke.split(separator: ",", omittingEmptySubsequences: false)
            guard numbers.count >= 3, numbers.count % 2 == 1 else { return false }
            guard numbers.allSatisfy({ !$0.isEmpty && $0.count <= 4 }) else { return false }
            guard let color = Int(numbers[0]), color < colors else { return false }
            return numbers.dropFirst().allSatisfy { (Int($0) ?? 1001) <= 1000 }
        }
    }

    /// Tratti di un disegno valido: colore e punti nelle coordinate 0–1.
    public static func strokes(_ value: String) -> [(color: Int, points: [CGPoint])] {
        guard isValidDrawing(value) else { return [] }
        return value.split(separator: ";").map { stroke in
            let numbers = stroke.split(separator: ",").compactMap { Int($0) }
            var points: [CGPoint] = []
            var index = 1
            while index + 1 < numbers.count {
                points.append(CGPoint(x: Double(numbers[index]) / 1000, y: Double(numbers[index + 1]) / 1000))
                index += 2
            }
            return (numbers[0], points)
        }
    }

    /// "Gatto", "il gatto!", "GATTÒ" valgono tutti "gatto".
    public static func guessMatches(_ guess: String, word: String) -> Bool {
        let target = normalize(word)
        guard !target.isEmpty else { return false }
        let typed = normalize(guess)
        return typed == target || typed.split(separator: " ").contains { String($0) == target }
    }

    private static func normalize(_ value: String) -> String {
        let folded = value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        let kept = String(folded.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == " " }.map(Character.init))
        return kept.split(separator: " ").joined(separator: " ")
    }
}
