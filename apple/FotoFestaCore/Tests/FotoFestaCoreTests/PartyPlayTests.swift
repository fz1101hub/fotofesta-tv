import FotoFestaCore
import XCTest

final class PartyPlayTests: XCTestCase {
    private let clock = TestClock()
    private let giulia = "player-giulia"
    private let marco = "player-marco"
    private let sara = "player-sara"
    private var hub: PartyHub!
    private var play: PartyPlay!

    override func setUp() {
        super.setUp()
        let clock = clock
        hub = PartyHub(now: { clock.now })
        play = PartyPlay(hub: hub, now: { clock.now }, random: { $0 - 1 })
    }

    private func join() {
        hub.join(player: giulia, name: "Giulia")
        hub.join(player: marco, name: "Marco")
        hub.join(player: sara, name: "Sara")
    }

    @discardableResult
    private func send(_ player: String, _ fields: [String: String]) -> (status: Int, json: String) {
        clock.advance(3)
        var form = ["type": "play", "player": player, "round": play.current()?.round ?? ""]
        form.merge(fields) { _, new in new }
        return play.handle(form)
    }

    private func phone(_ player: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(play.phoneJSON(player: player).utf8))) as? [String: Any] ?? [:]
    }

    private func game(_ player: String) -> [String: Any] { phone(player)["game"] as? [String: Any] ?? [:] }

    func testGamesThatNeedAGuestWaitForNames() {
        XCTAssertNil(play.start(.drawGuess, prompt: "Disegna", pool: ["gatto"]))
        XCTAssertNil(play.start(.mole, prompt: "Talpa", pool: ["ananas"]))
        hub.join(player: giulia, name: "Giulia")
        XCTAssertNil(play.start(.compliments, prompt: "Complimenti"))
        XCTAssertNotNil(play.start(.drawGuess, prompt: "Disegna", pool: ["gatto"]))
    }

    func testDrawAndGuess() throws {
        join()
        let drawer = try XCTUnwrap(play.start(.drawGuess, prompt: "Indovina il disegno", pool: ["gatto"])?.special?.player)
        let guesser = try XCTUnwrap([giulia, marco, sara].first { $0 != drawer })
        // Solo chi disegna vede la parola.
        XCTAssertEqual(game(drawer)["secret"] as? String, "gatto")
        XCTAssertEqual(game(drawer)["input"] as? String, "draw")
        XCTAssertNil(game(guesser)["secret"])
        XCTAssertEqual(game(guesser)["input"] as? String, "text")

        XCTAssertEqual(send(drawer, ["drawing": "0,100,100,500,500;2,10,10,20,20"]).status, 200)
        XCTAssertEqual(send(drawer, ["drawing": "0,100,1200"]).status, 400)
        XCTAssertEqual(play.current()?.entries.filter { $0.drawing != nil }.count, 1)
        XCTAssertEqual(send(guesser, ["text": "cane"]).json, #"{"status":"wrong"}"#)
        XCTAssertEqual(send(guesser, ["text": "È un GATTO!"]).json, #"{"status":"correct"}"#)
        let done = try XCTUnwrap(play.current())
        XCTAssertTrue(done.revealed)
        XCTAssertEqual(done.winner?.player, guesser)
        XCTAssertEqual(play.coins(of: guesser), 3)
        XCTAssertEqual(play.coins(of: drawer), 2)
        XCTAssertEqual(send(guesser, ["text": "gatto"]).status, 409)
        XCTAssertEqual(game(guesser)["answer"] as? String, "gatto")
    }

    func testGuessesIgnoreCaseAccentsAndPunctuation() {
        XCTAssertTrue(PartyPlay.guessMatches("  il CAFFÈ!! ", word: "caffe"))
        XCTAssertTrue(PartyPlay.guessMatches("torta", word: "Torta"))
        XCTAssertFalse(PartyPlay.guessMatches("tortellini", word: "torta"))
        XCTAssertFalse(PartyPlay.guessMatches("qualcosa", word: ""))
    }

    func testDrawingsAreCheckedAndParsed() {
        XCTAssertTrue(PartyPlay.isValidDrawing("1,0,0,1000,1000"))
        XCTAssertFalse(PartyPlay.isValidDrawing(""))
        XCTAssertFalse(PartyPlay.isValidDrawing("9,0,0"))
        XCTAssertFalse(PartyPlay.isValidDrawing("1,0"))
        XCTAssertFalse(PartyPlay.isValidDrawing("1,0,0,<b>"))
        XCTAssertFalse(PartyPlay.isValidDrawing("1," + String(repeating: "5,", count: 11_000) + "5"))
        let strokes = PartyPlay.strokes("3,0,500,1000,250")
        XCTAssertEqual(strokes.first?.color, 3)
        XCTAssertEqual(strokes.first?.points.first?.y ?? 0, 0.5, accuracy: 0.001)
    }

    func testTheStoryShowsOnlyTheLastSentenceAndNobodyWritesTwiceInARow() {
        join()
        play.start(.story, prompt: "Romanzo", pool: ["C'era una volta una festa."])
        XCTAssertEqual(game(giulia)["previous"] as? String, "C'era una volta una festa.")
        XCTAssertEqual(send(giulia, ["text": "Arrivò un drago."]).status, 200)
        XCTAssertEqual(send(giulia, ["text": "E ballò."]).status, 409)
        XCTAssertEqual(game(marco)["previous"] as? String, "Arrivò un drago.")
        XCTAssertEqual(send(marco, ["text": "Il drago aveva fame."]).status, 200)
        play.reveal()
        XCTAssertEqual(play.current()?.entries.map(\.text), ["Arrivò un drago.", "Il drago aveva fame."])
    }

    func testWhisperPassesTheTurnOnePhoneAtATime() {
        join()
        play.start(.whisper, prompt: "Telefono senza fili", pool: ["Un elefante in bicicletta"])
        let first = game(giulia)
        XCTAssertEqual(first["turn"] as? Bool, true)
        XCTAssertEqual(first["input"] as? String, "draw")
        XCTAssertEqual(first["previous"] as? String, "Un elefante in bicicletta")
        XCTAssertEqual(game(marco)["turn"] as? Bool, false)
        XCTAssertEqual(send(giulia, ["drawing": "0,1,1,2,2"]).status, 200)
        let second = game(marco)
        XCTAssertEqual(second["turn"] as? Bool, true)
        XCTAssertEqual(second["input"] as? String, "text")
        XCTAssertEqual(second["previousDrawing"] as? String, "0,1,1,2,2")
        XCTAssertEqual(send(sara, ["text": "Non è il mio turno"]).status, 409)
        // Chi non risponde in tempo perde il turno.
        clock.advance(PartyPlay.whisperTurn + 1)
        XCTAssertEqual(game(sara)["turn"] as? Bool, true)
    }

    func testSecretComplimentsBecomeAnonymousMessages() {
        join()
        play.start(.compliments, prompt: "Complimenti segreti")
        XCTAssertEqual(game(giulia)["choices"] as? [String], ["Marco", "Sara"])
        XCTAssertEqual(send(giulia, ["target": "Giulia", "text": "Sono bravissima"]).status, 400)
        XCTAssertEqual(send(giulia, ["target": "Marco", "text": "Balli benissimo"]).status, 200)
        XCTAssertEqual(hub.approvedMessages().map(\.text), ["💌 Marco: Balli benissimo"])
        XCTAssertNil(hub.approvedMessages().first?.name)
        XCTAssertEqual(play.coins(of: giulia), 1)
    }

    func testMissionsTagThePhotosThatCompleteThem() throws {
        join()
        play.start(.missions, prompt: "Missioni", pool: ["Una foto con gli occhiali", "Un brindisi"])
        let missionGiulia = try XCTUnwrap(game(giulia)["secret"] as? String)
        let missionMarco = try XCTUnwrap(game(marco)["secret"] as? String)
        XCTAssertNotEqual(missionGiulia, missionMarco)
        XCTAssertEqual(play.photoArrived(player: giulia, photoID: "foto-1")?.text, missionGiulia)
        XCTAssertEqual(play.tag("foto-1")?.text, missionGiulia)
        XCTAssertNil(play.photoArrived(player: nil, photoID: "foto-2"))
        XCTAssertEqual(play.coins(of: giulia), 2)
        play.photoArrived(player: giulia, photoID: "foto-3")
        XCTAssertEqual(play.coins(of: giulia), 2)
        XCTAssertEqual(play.taggedPhotos(round: try XCTUnwrap(play.current()?.round)), ["foto-1", "foto-3"])
    }

    func testPaparazziRewardTheFirstThreePhotographersButNotTheStar() throws {
        join()
        hub.join(player: "player-luca", name: "Luca")
        hub.join(player: "player-anna", name: "Anna")
        let star = try XCTUnwrap(play.start(.paparazzi, prompt: "Paparazzi")?.special)
        let others = [giulia, marco, sara, "player-luca", "player-anna"].filter { $0 != star.player }
        XCTAssertNil(play.photoArrived(player: star.player, photoID: "selfie"))
        for (index, player) in others.enumerated() { play.photoArrived(player: player, photoID: "foto-\(index)") }
        XCTAssertEqual(others.map { play.coins(of: $0) }, [3, 2, 1, 0])
        XCTAssertEqual(play.tag("foto-0")?.text, star.name)
    }

    func testTheSameMomentCountsOnlyPhotosInTheMinuteAfterTheSignal() throws {
        join()
        let momentAt = try XCTUnwrap(play.start(.sameMoment, prompt: "Lo stesso istante")?.momentAt)
        XCTAssertEqual((game(giulia)["moment"] as? NSNumber)?.int64Value, Int64(momentAt.timeIntervalSince1970 * 1000))
        XCTAssertNil(play.photoArrived(player: giulia, photoID: "presto"))
        clock.advance(PartyPlay.momentDelay + 5)
        XCTAssertNotNil(play.photoArrived(player: giulia, photoID: "giusto"))
        clock.advance(PartyPlay.momentWindow)
        XCTAssertNil(play.photoArrived(player: marco, photoID: "tardi"))
    }

    func testTheMoleWinsUnlessMostGuestsVoteForThem() throws {
        join()
        let mole = try XCTUnwrap(play.start(.mole, prompt: "Chi è la talpa?", pool: ["Fai dire «ananas» a tre persone"])?.special)
        XCTAssertEqual(game(mole.player)["secret"] as? String, "Fai dire «ananas» a tre persone")
        let others = [giulia, marco, sara].filter { $0 != mole.player }
        XCTAssertNil(game(others[0])["secret"])
        XCTAssertEqual(send(others[0], ["command": "vote", "target": mole.name]).status, 409)
        play.startVoting()
        XCTAssertEqual(game(others[0])["input"] as? String, "choice")
        for player in others { XCTAssertEqual(send(player, ["command": "vote", "target": mole.name]).status, 200) }
        play.reveal()
        XCTAssertTrue(play.moleCaught())
        XCTAssertEqual(game(others[0])["caught"] as? Bool, true)
        XCTAssertEqual(others.map { play.coins(of: $0) }, [2, 2])
        XCTAssertEqual(play.coins(of: mole.player), 0)
        XCTAssertEqual(play.current()?.votes.first?.name, mole.name)
    }

    func testTheTimeCapsuleStaysHiddenUntilItIsOpened() {
        join()
        play.start(.capsule, prompt: "Capsula del tempo")
        XCTAssertEqual(send(giulia, ["text": "Imparerò a ballare"]).status, 200)
        XCTAssertNil(game(marco)["entries"])
        play.reveal()
        XCTAssertEqual(game(marco)["entries"] as? [String], ["Giulia: Imparerò a ballare"])
    }

    func testTheQRTreasureGoesToTheFirstThreePhones() {
        join()
        hub.join(player: "player-luca", name: "Luca")
        let token = play.startTreasure()
        XCTAssertEqual(send(giulia, ["command": "treasure", "token": "sbagliato"]).status, 400)
        XCTAssertEqual(send(giulia, ["command": "treasure", "token": token]).status, 200)
        XCTAssertEqual(send(giulia, ["command": "treasure", "token": token]).status, 409)
        XCTAssertEqual(send(marco, ["command": "treasure", "token": token]).status, 200)
        XCTAssertEqual(send(sara, ["command": "treasure", "token": token]).status, 200)
        XCTAssertNil(play.currentTreasure())
        XCTAssertEqual(send("player-luca", ["command": "treasure", "token": token]).status, 410)
        XCTAssertEqual([giulia, marco, sara].map { play.coins(of: $0) }, [3, 2, 1])
        XCTAssertEqual(play.treasureFinders(), ["Giulia", "Marco", "Sara"])
    }

    func testCoinsMakeALeaderboardAndLightsReachEveryPhone() {
        join()
        play.award(marco, amount: 5)
        play.award(giulia, amount: 5)
        play.award(sara, amount: 1)
        XCTAssertEqual(play.leaderboard().map(\.name), ["Marco", "Giulia", "Sara"])
        XCTAssertEqual(phone(marco)["coins"] as? Int, 5)
        play.setLights(.wave)
        XCTAssertEqual((phone(sara)["lights"] as? [String: Any])?["pattern"] as? String, "wave")
        play.clear()
        XCTAssertTrue(play.leaderboard().isEmpty)
        XCTAssertNil(phone(sara)["lights"])
    }

    func testTheServerRoutesGamesAndWaitsDuringTheQuietMinutes() async throws {
        join()
        let play = self.play!
        let hub = self.hub!
        play.start(.capsule, prompt: "Capsula")
        let round = try XCTUnwrap(play.current()?.round)
        let server = GuestUploadServer(key: "test-key", page: { "<html></html>" }, onPhoto: { _, _, _ in .shown }, hub: hub, play: play)
        let port = try await server.start(host: "127.0.0.1", preferredPort: 0)
        defer { server.stop() }
        let session = URLSession(configuration: .ephemeral)
        func post(_ body: String) async throws -> Int {
            var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/party/action")!)
            request.httpMethod = "POST"
            request.setValue("test-key", forHTTPHeaderField: "X-FotoFesta-Key")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            let (_, response) = try await session.upload(for: request, from: Data(body.utf8))
            return (response as? HTTPURLResponse)?.statusCode ?? 0
        }
        let sent = try await post("type=play&player=player-giulia&round=\(round)&text=Ciao")
        XCTAssertEqual(sent, 200)
        hub.startQuiet()
        let waiting = try await post("type=play&player=player-marco&round=\(round)&text=Ciao")
        XCTAssertEqual(waiting, 423)
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/party?player=player-giulia")!)
        request.setValue("test-key", forHTTPHeaderField: "X-FotoFesta-Key")
        let (data, _) = try await session.data(for: request)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let playJSON = try XCTUnwrap(json["play"] as? [String: Any])
        XCTAssertEqual((playJSON["game"] as? [String: Any])?["kind"] as? String, "capsule")
    }
}
