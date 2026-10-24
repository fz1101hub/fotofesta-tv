import FotoFestaCore
import XCTest

final class PhoneGameTests: XCTestCase {
    private let giulia = "player-giulia"
    private let marco = "player-marco"
    private let sara = "player-sara1"

    func testPhonesSeeTheAnswerOnlyAfterTheReveal() {
        let game = PhoneGame()
        XCTAssertNil(game.current())
        game.ask(question: "Chi ha mandato questa foto?", choices: ["Giulia", "Marco"], correct: 1)
        let asked = game.current()
        XCTAssertEqual(asked?.open, true)
        XCTAssertNil(asked?.answer)
        game.reveal()
        XCTAssertEqual(game.current()?.round, asked?.round)
        XCTAssertEqual(game.current()?.open, false)
        XCTAssertEqual(game.current()?.answer, 1)
    }

    func testOnlyTheFirstAnswerCountsAndRightAnswersScore() {
        let game = PhoneGame()
        game.ask(question: "?", choices: ["A", "B", "C"], correct: 2)
        let round = game.current()!.round
        XCTAssertEqual(game.answer(round: round, choice: 2, player: giulia, name: "Giulia"), .accepted)
        XCTAssertEqual(game.answer(round: round, choice: 0, player: giulia, name: "Giulia"), .accepted)
        XCTAssertEqual(game.answer(round: round, choice: 1, player: marco, name: "Marco"), .accepted)
        XCTAssertEqual(game.answerCount(), 2)
        game.reveal()
        XCTAssertEqual(game.points(of: giulia), 1)
        XCTAssertEqual(game.points(of: marco), 0)
        XCTAssertEqual(game.leaderboard(), [PhoneGame.Score(name: "Giulia", points: 1)])
    }

    func testAnswersToClosedOrOldQuestionsAreRefused() {
        let game = PhoneGame()
        XCTAssertEqual(game.answer(round: "nessuna", choice: 0, player: giulia, name: nil), .closed)
        game.ask(question: "?", choices: ["A", "B"], correct: 0)
        let first = game.current()!.round
        game.reveal()
        XCTAssertEqual(game.answer(round: first, choice: 0, player: giulia, name: nil), .closed)
        game.ask(question: "?", choices: ["A", "B"], correct: 0)
        XCTAssertNotEqual(game.current()?.round, first)
        XCTAssertEqual(game.answer(round: first, choice: 0, player: giulia, name: nil), .closed)
        game.stop()
        XCTAssertNil(game.current())
    }

    func testInvalidChoicesAndPlayersAreRefused() {
        let game = PhoneGame()
        game.ask(question: "?", choices: ["A", "B"], correct: 0)
        let round = game.current()!.round
        XCTAssertEqual(game.answer(round: round, choice: 5, player: giulia, name: nil), .invalid)
        XCTAssertEqual(game.answer(round: round, choice: -1, player: giulia, name: nil), .invalid)
        XCTAssertEqual(game.answer(round: round, choice: 0, player: "corto", name: nil), .invalid)
        XCTAssertEqual(game.answer(round: round, choice: 0, player: "id con spazi!", name: nil), .invalid)
        XCTAssertEqual(game.answerCount(), 0)
    }

    func testLeaderboardOrdersByPointsThenByWhoStartedFirst() {
        let game = PhoneGame()
        for index in 0..<3 {
            game.ask(question: "?", choices: ["A", "B"], correct: 0)
            let round = game.current()!.round
            _ = game.answer(round: round, choice: index == 2 ? 1 : 0, player: sara, name: "Sara")
            _ = game.answer(round: round, choice: 0, player: marco, name: nil)
            _ = game.answer(round: round, choice: 0, player: giulia, name: "Giulia")
            game.reveal()
        }
        XCTAssertEqual(game.leaderboard(), [
            PhoneGame.Score(name: PhoneGame.defaultName, points: 3),
            PhoneGame.Score(name: "Giulia", points: 3),
            PhoneGame.Score(name: "Sara", points: 2),
        ])
        game.resetScores()
        XCTAssertEqual(game.leaderboard(), [])
    }

    func testPollsShowTheVotesAtTheEndAndGiveNoPoints() {
        let game = PhoneGame()
        game.askPoll(question: "Chi piangerà per primo?", choices: ["Giulia", "Marco", "Sara"])
        let round = game.current()!.round
        XCTAssertEqual(game.current()?.poll, true)
        _ = game.answer(round: round, choice: 1, player: giulia, name: "Giulia")
        _ = game.answer(round: round, choice: 1, player: marco, name: "Marco")
        _ = game.answer(round: round, choice: 2, player: sara, name: "Sara")
        XCTAssertEqual(game.tally(), [0, 2, 1])
        XCTAssertNil(game.current()?.tally)
        game.reveal()
        XCTAssertEqual(game.current()?.tally, [0, 2, 1])
        XCTAssertNil(game.current()?.answer)
        XCTAssertEqual(game.points(of: marco), 0)
        XCTAssertEqual(game.names(), ["Giulia", "Marco", "Sara"])
    }

    func testServerServesTheQuestionAndTakesAnswers() async throws {
        let game = PhoneGame()
        let server = GuestUploadServer(key: "test-key", page: { "<html></html>" }, onPhoto: { _, _, _ in .shown }, game: game)
        let port = try await server.start(host: "127.0.0.1", preferredPort: 0)
        defer { server.stop() }
        let base = "http://127.0.0.1:\(port)"
        let session = URLSession(configuration: .ephemeral)

        func get(_ path: String, key: String? = "test-key") async throws -> (Int, [String: Any]) {
            var request = URLRequest(url: URL(string: base + path)!)
            request.setValue(key, forHTTPHeaderField: "X-FotoFesta-Key")
            let (data, response) = try await session.data(for: request)
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, json)
        }

        func answer(_ form: String, key: String = "test-key") async throws -> Int {
            var request = URLRequest(url: URL(string: base + "/game/answer")!)
            request.httpMethod = "POST"
            request.setValue(key, forHTTPHeaderField: "X-FotoFesta-Key")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            let (_, response) = try await session.upload(for: request, from: Data(form.utf8))
            return (response as? HTTPURLResponse)?.statusCode ?? 0
        }

        let (noKey, _) = try await get("/game", key: nil)
        XCTAssertEqual(noKey, 401)
        let (empty, none) = try await get("/game")
        XCTAssertEqual(empty, 200)
        XCTAssertNil(none["round"] as? String)

        game.ask(question: "Chi l'ha \"scattata\"?", choices: ["Giulia", "Marco"], correct: 1)
        let round = game.current()!.round
        let (_, question) = try await get("/game")
        XCTAssertEqual(question["round"] as? String, round)
        XCTAssertEqual(question["question"] as? String, "Chi l'ha \"scattata\"?")
        XCTAssertEqual(question["choices"] as? [String], ["Giulia", "Marco"])
        XCTAssertEqual(question["open"] as? Bool, true)

        let accepted = try await answer("round=\(round)&choice=1&player=telefono-1&name=Giulia%20B")
        XCTAssertEqual(accepted, 200)
        let badChoice = try await answer("round=\(round)&choice=9&player=telefono-1")
        XCTAssertEqual(badChoice, 400)
        let wrongKey = try await answer("round=\(round)&choice=0&player=telefono-2", key: "wrong")
        XCTAssertEqual(wrongKey, 401)
        let tooLarge = try await answer("round=\(round)&choice=0&player=telefono-2&name=" + String(repeating: "x", count: 2_000))
        XCTAssertEqual(tooLarge, 413)

        game.reveal()
        let (_, result) = try await get("/game?player=telefono-1")
        XCTAssertEqual(result["answer"] as? Int, 1)
        XCTAssertEqual(result["points"] as? Int, 1)
        XCTAssertEqual(game.leaderboard().first?.name, "Giulia B")
        let closed = try await answer("round=\(round)&choice=0&player=telefono-2")
        XCTAssertEqual(closed, 409)
    }
}
