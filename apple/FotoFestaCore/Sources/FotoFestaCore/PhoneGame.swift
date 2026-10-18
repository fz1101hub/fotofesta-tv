import Foundation

/// Gioco con i telefoni: la TV pubblica una domanda con le risposte possibili, gli invitati rispondono
/// dalla pagina della festa e la TV, quando vuole, mostra la risposta giusta e assegna un punto a chi
/// ha indovinato. Vale una risposta per telefono e per domanda: la prima.
///
/// Thread-safe: il server lo usa dalla sua coda, l'interfaccia dal thread principale.
public final class PhoneGame: @unchecked Sendable {
    /// Domanda come la vedono i telefoni; `answer` è noto solo dopo `reveal()`.
    public struct Snapshot: Equatable, Sendable {
        public let round: String
        public let question: String
        public let choices: [String]
        public let open: Bool
        public let answer: Int?
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

    /// Chiude le risposte, mostra quella giusta ai telefoni e dà un punto a chi l'ha scelta.
    public func reveal() {
        lock.withLock {
            guard let current = snapshot, current.open else { return }
            snapshot = Snapshot(round: current.round, question: current.question, choices: current.choices, open: false, answer: correct)
            for (player, choice) in answers where choice == correct {
                players[player]?.points += 1
            }
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
