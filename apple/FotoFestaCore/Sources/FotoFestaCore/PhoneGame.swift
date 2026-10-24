import Foundation

/// Gioco con i telefoni: la TV pubblica una domanda con le risposte possibili, gli invitati rispondono
/// dalla pagina della festa e la TV, quando vuole, mostra la risposta giusta e assegna un punto a chi
/// ha indovinato. Vale una risposta per telefono e per domanda: la prima. Un sondaggio (`askPoll`)
/// funziona allo stesso modo ma non ha una risposta giusta: alla fine i telefoni vedono i voti.
///
/// Thread-safe: il server lo usa dalla sua coda, l'interfaccia dal thread principale.
public final class PhoneGame: @unchecked Sendable {
    /// Domanda come la vedono i telefoni; `answer` è noto solo dopo `reveal()`. Per un sondaggio
    /// `answer` resta nil e dopo `reveal()` `tally` ha i voti di ogni risposta.
    public struct Snapshot: Equatable, Sendable {
        public let round: String
        public let question: String
        public let choices: [String]
        public let open: Bool
        public let answer: Int?
        public let poll: Bool
        public let tally: [Int]?

        init(round: String, question: String, choices: [String], open: Bool, answer: Int?, poll: Bool = false, tally: [Int]? = nil) {
            self.round = round
            self.question = question
            self.choices = choices
            self.open = open
            self.answer = answer
            self.poll = poll
            self.tally = tally
        }
    }

    public struct Score: Equatable, Sendable {
        public let name: String
        public let points: Int

        public init(name: String, points: Int) {
            self.name = name
            self.points = points
        }
    }

    public enum AnswerResult: Equatable, Sendable {
        case accepted, closed, invalid
    }

    /// Nome di chi non l'ha scritto; la pagina manda già quello tradotto, questo è di riserva.
    public static let defaultName = "?"

    private struct Player {
        var name: String
        let order: Int
        var points = 0
    }

    private let lock = NSLock()
    private var snapshot: Snapshot?
    private var correct = -1
    private var answers: [String: Int] = [:]
    private var players: [String: Player] = [:]

    public init() {}

    /// Nuova domanda; `correct` è l'indice della risposta giusta, che i telefoni vedono solo dopo `reveal()`.
    public func ask(question: String, choices: [String], correct: Int) {
        precondition(choices.indices.contains(correct))
        let round = String(UUID().uuidString.prefix(12))
        lock.withLock {
            snapshot = Snapshot(round: round, question: question, choices: choices, open: true, answer: nil)
            self.correct = correct
            answers = [:]
        }
    }

    /// Sondaggio: nessuna risposta giusta e nessun punto; `reveal()` mostra quanti voti ha ogni risposta.
    public func askPoll(question: String, choices: [String]) {
        precondition(choices.count >= 2)
        let round = String(UUID().uuidString.prefix(12))
        lock.withLock {
            snapshot = Snapshot(round: round, question: question, choices: choices, open: true, answer: nil, poll: true)
            correct = -1
            answers = [:]
        }
    }

    /// Chiude le risposte. In un gioco mostra quella giusta ai telefoni e dà un punto a chi l'ha
    /// scelta; in un sondaggio mostra i voti.
    public func reveal() {
        lock.withLock {
            guard let current = snapshot, current.open else { return }
            if current.poll {
                snapshot = Snapshot(round: current.round, question: current.question, choices: current.choices, open: false,
                                    answer: nil, poll: true, tally: votes(current.choices.count))
                return
            }
            snapshot = Snapshot(round: current.round, question: current.question, choices: current.choices, open: false, answer: correct)
            for (player, choice) in answers where choice == correct {
                players[player]?.points += 1
            }
        }
    }

    /// Voti di ogni risposta alla domanda in corso, anche mentre è aperta (per la TV).
    public func tally() -> [Int] {
        lock.withLock { votes(snapshot?.choices.count ?? 0) }
    }

    private func votes(_ count: Int) -> [Int] {
        var result = Array(repeating: 0, count: count)
        for choice in answers.values where result.indices.contains(choice) { result[choice] += 1 }
        return result
    }

    /// Nomi scritti da chi ha giocato, nell'ordine in cui ha cominciato (per l'estrazione a premi).
    public func names() -> [String] {
        lock.withLock {
            players.values.sorted { $0.order < $1.order }.map(\.name).filter { $0 != Self.defaultName }
        }
    }

    /// Nessuna domanda in corso (il gioco è stato chiuso); i punti restano.
    public func stop() {
        lock.withLock {
            snapshot = nil
            answers = [:]
        }
    }

    /// Nuova partita: si azzerano i punti di tutti.
    public func resetScores() {
        lock.withLock { players = [:] }
    }

    public func current() -> Snapshot? { lock.withLock { snapshot } }

    public func answerCount() -> Int { lock.withLock { answers.count } }

    /// Telefoni che hanno scelto `choice` nella domanda in corso: chi ha vinto una scommessa.
    public func voters(_ choice: Int) -> [String] { lock.withLock { answers.filter { $0.value == choice }.map(\.key) } }

    public func points(of player: String) -> Int { lock.withLock { players[player]?.points ?? 0 } }

    /// Classifica: più punti prima; a parità, chi ha cominciato a giocare prima.
    public func leaderboard(limit: Int = 5) -> [Score] {
        lock.withLock {
            players.values
                .filter { $0.points > 0 }
                .sorted { $0.points != $1.points ? $0.points > $1.points : $0.order < $1.order }
                .prefix(limit)
                .map { Score(name: $0.name, points: $0.points) }
        }
    }

    /// Risposta di un telefono: `player` è un identificativo casuale scelto dalla pagina.
    public func answer(round: String, choice: Int, player: String, name: String?) -> AnswerResult {
        lock.withLock {
            guard let current = snapshot, current.round == round, current.open else { return .closed }
            guard current.choices.indices.contains(choice), Self.isValidPlayer(player) else { return .invalid }
            let displayName = name.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
            if var known = players[player] {
                if let displayName { known.name = displayName }
                players[player] = known
            } else {
                players[player] = Player(name: displayName ?? Self.defaultName, order: players.count)
            }
            // Vale la prima risposta: un secondo tocco sullo stesso telefono non cambia nulla.
            if answers[player] == nil { answers[player] = choice }
            return .accepted
        }
    }

    public static func isValidPlayer(_ player: String) -> Bool {
        (8...64).contains(player.count) && player.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }
}
