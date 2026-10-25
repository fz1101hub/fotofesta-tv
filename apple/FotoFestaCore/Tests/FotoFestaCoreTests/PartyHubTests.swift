import FotoFestaCore
import XCTest

/// Orologio finto per i limiti di frequenza.
private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 1_000_000)
    var now: Date { lock.withLock { value } }
    func advance(_ seconds: TimeInterval) { lock.withLock { value += seconds } }
}

final class PartyHubTests: XCTestCase {
    private let giulia = "player-giulia"
    private let marco = "player-marco"
    private let clock = Clock()

    private func hub() -> PartyHub {
        let clock = clock
        return PartyHub(now: { clock.now })
    }

    func testReactionsCountForThePhotoOnScreen() {
        let hub = hub()
        hub.showing("foto-1")
        XCTAssertEqual(hub.react(player: giulia, index: 0, name: "Giulia"), .ok)
        XCTAssertEqual(hub.react(player: marco, index: 2, name: nil), .ok)
        hub.showing("foto-2")
        XCTAssertEqual(hub.react(player: giulia, index: 5, name: nil), .ok)
        XCTAssertEqual(hub.reactionCount("foto-1"), 2)
        XCTAssertEqual(hub.reactionCount("foto-2"), 1)
        XCTAssertEqual(hub.reactions(after: 0).map(\.reaction), [.heart, .party, .fire])
        XCTAssertEqual(hub.reactions(after: 1).count, 2)
        XCTAssertEqual(hub.lastReactionSerial(), 3)
        XCTAssertEqual(hub.participants(), [PartyHub.Participant(player: giulia, name: "Giulia")])
    }

    func testReactionsFromOnePhoneAreLimited() {
        let hub = hub()
        for _ in 0..<PartyHub.reactionBurst { XCTAssertEqual(hub.react(player: giulia, index: 0, name: nil), .ok) }
        XCTAssertEqual(hub.react(player: giulia, index: 0, name: nil), .rateLimited)
        XCTAssertEqual(hub.react(player: marco, index: 0, name: nil), .ok)
        clock.advance(PartyHub.reactionWindow)
        XCTAssertEqual(hub.react(player: giulia, index: 0, name: nil), .ok)
    }

    func testInvalidReactionsAndDisabledFeaturesAreRefused() {
        let hub = hub()
        XCTAssertEqual(hub.react(player: giulia, index: 6, name: nil), .invalid)
        XCTAssertEqual(hub.react(player: "corto", index: 0, name: nil), .invalid)
        hub.setFeatures(PartyHub.Features(reactions: false, messages: false))
        XCTAssertEqual(hub.react(player: giulia, index: 0, name: nil), .disabled)
        XCTAssertEqual(hub.message(player: giulia, text: "Auguri!", name: nil), .disabled)
        XCTAssertEqual(hub.featuresJSON(), #"{"reactions":false,"messages":false,"moderated":false,"arrivals":true,"wall":true,"songs":true,"album":true,"video":true,"delete":true}"#)
    }

    func testMessagesAreCleanedSignedAndLimited() {
        let hub = hub()
        XCTAssertEqual(hub.message(player: giulia, text: "  Tanti\n\nauguri\u{202E}!  ", name: "Giulia"), .ok)
        XCTAssertEqual(hub.message(player: giulia, text: "   ", name: "Giulia"), .invalid)
        XCTAssertEqual(hub.approvedMessages().first?.text, "Tanti auguri!")
        XCTAssertEqual(hub.approvedMessages().first?.name, "Giulia")
        XCTAssertEqual(hub.message(player: marco, text: String(repeating: "x", count: 500), name: nil), .ok)
        XCTAssertEqual(hub.approvedMessages().last?.text.count, PartyHub.maxMessageLength)
        _ = hub.message(player: giulia, text: "due", name: nil)
        _ = hub.message(player: giulia, text: "tre", name: nil)
        XCTAssertEqual(hub.message(player: giulia, text: "quattro", name: nil), .rateLimited)
        XCTAssertEqual(hub.approvedMessages().last?.name, "Giulia")
    }

    func testModeratedMessagesWaitForApproval() {
        let hub = hub()
        hub.setFeatures(PartyHub.Features(moderateMessages: true))
        XCTAssertEqual(hub.message(player: giulia, text: "Uno", name: nil), .pending)
        XCTAssertEqual(hub.message(player: marco, text: "Due", name: nil), .pending)
        XCTAssertTrue(hub.approvedMessages().isEmpty)
        let pending = hub.pendingMessages()
        let revision = hub.currentRevision()
        hub.approveMessage(pending[0].id)
        hub.removeMessage(pending[1].id)
        XCTAssertGreaterThan(hub.currentRevision(), revision)
        XCTAssertEqual(hub.approvedMessages().map(\.text), ["Uno"])
        _ = hub.message(player: giulia, text: "Tre", name: nil)
        hub.setFeatures(PartyHub.Features(moderateMessages: false))
        XCTAssertEqual(hub.approvedMessages().map(\.text), ["Uno", "Tre"])
    }

    func testFormRequestsMapToHTTPAnswers() {
        let hub = hub()
        XCTAssertEqual(hub.handle(["type": "react", "player": giulia, "reaction": "1"]).status, 200)
        XCTAssertEqual(hub.handle(["type": "react", "player": giulia]).status, 400)
        let sent = hub.handle(["type": "message", "player": giulia, "text": "Ciao", "name": "Giulia"])
        XCTAssertEqual(sent.status, 200)
        XCTAssertEqual(sent.json, #"{"status":"ok"}"#)
        XCTAssertEqual(hub.handle(["type": "boh", "player": giulia]).status, 400)
        hub.setFeatures(PartyHub.Features(moderateMessages: true))
        XCTAssertEqual(hub.handle(["type": "message", "player": marco, "text": "Ehi"]).json, #"{"status":"pending"}"#)
    }

    func testClearForgetsEverything() {
        let hub = hub()
        hub.showing("foto")
        _ = hub.react(player: giulia, index: 0, name: "Giulia")
        _ = hub.message(player: giulia, text: "Ciao", name: nil)
        hub.clear()
        XCTAssertTrue(hub.approvedMessages().isEmpty)
        XCTAssertTrue(hub.participants().isEmpty)
        XCTAssertEqual(hub.reactionCount("foto"), 0)
        XCTAssertTrue(hub.allReactionCounts().isEmpty)
    }

    func testPrizeDrawCandidatesAndWinners() {
        XCTAssertEqual(PrizeDraw.candidates(["Giulia", nil, " "], ["marco", "GIULIA "], ["Marco", "Sara"]), ["Giulia", "marco", "Sara"])
        XCTAssertEqual(PrizeDraw.candidates(["Anna Maria", "anna   maria"]), ["Anna Maria"])
        let names = ["Giulia", "Marco", "Sara"]
        var winners: [String] = []
        for _ in 0..<3 { winners.append(PrizeDraw.draw(names, winners: winners)!) }
        XCTAssertEqual(Set(winners), Set(names))
        XCTAssertNil(PrizeDraw.draw(names, winners: winners))
        XCTAssertEqual(PrizeDraw.draw(names, winners: ["giulia", "MARCO"]), "Sara")
    }

    func testServerServesThePartyStateAndTakesActions() async throws {
        let game = PhoneGame()
        let hub = PartyHub()
        let server = GuestUploadServer(key: "test-key", page: { "<html></html>" }, onPhoto: { _, _, _ in .shown }, game: game, hub: hub)
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

        func action(_ form: String, key: String = "test-key") async throws -> Int {
            var request = URLRequest(url: URL(string: base + "/party/action")!)
            request.httpMethod = "POST"
            request.setValue(key, forHTTPHeaderField: "X-FotoFesta-Key")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            let (_, response) = try await session.upload(for: request, from: Data(form.utf8))
            return (response as? HTTPURLResponse)?.statusCode ?? 0
        }

        let (noKey, _) = try await get("/party", key: nil)
        XCTAssertEqual(noKey, 401)
        game.askPoll(question: "Dove andranno in viaggio?", choices: ["Mare", "Montagna"])
        let (_, state) = try await get("/party?player=telefono-1")
        XCTAssertEqual((state["features"] as? [String: Any])?["reactions"] as? Bool, true)
        let poll = state["game"] as? [String: Any]
        XCTAssertEqual(poll?["poll"] as? Bool, true)
        XCTAssertTrue(poll?["tally"] is NSNull)
        _ = game.answer(round: game.current()!.round, choice: 1, player: "telefono-1", name: nil)
        game.reveal()
        let (_, closed) = try await get("/party")
        XCTAssertEqual((closed["game"] as? [String: Any])?["tally"] as? [Int], [0, 1])

        hub.showing("foto-1")
        let reacted = try await action("type=react&player=telefono-1&reaction=0")
        XCTAssertEqual(reacted, 200)
        XCTAssertEqual(hub.reactionCount("foto-1"), 1)
        let sent = try await action("type=message&player=telefono-1&name=Giulia&text=Auguri%20a%20tutti")
        XCTAssertEqual(sent, 200)
        XCTAssertEqual(hub.approvedMessages().first?.text, "Auguri a tutti")
        let wrongKey = try await action("type=react&player=telefono-1&reaction=0", key: "sbagliata")
        XCTAssertEqual(wrongKey, 401)
        let invalid = try await action("type=react&player=telefono-1&reaction=9")
        XCTAssertEqual(invalid, 400)
        let tooLarge = try await action("type=message&player=telefono-1&text=" + String(repeating: "x", count: 30_000))
        XCTAssertEqual(tooLarge, 413)
    }
}
