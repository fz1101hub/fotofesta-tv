import FotoFestaCore
import XCTest

final class PartyRulesTests: XCTestCase {
    private let clock = TestClock()
    private let giulia = "player-giulia"
    private let marco = "player-marco"

    private func makeHub() -> PartyHub {
        let clock = clock
        return PartyHub(now: { clock.now })
    }

    private func object(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
    }

    func testWithoutALimitEveryPhotoIsAllowed() {
        let hub = makeHub()
        for _ in 0..<50 { XCTAssertEqual(hub.takePhoto(player: nil), .allowed) }
        XCTAssertNil(hub.photosLeft(player: giulia))
        XCTAssertTrue(object(hub.partyJSON(player: giulia))["shots"] is NSNull)
    }

    func testOnePhotoEach() {
        let hub = makeHub()
        hub.setPhotoRules(PartyHub.PhotoRules(perGuest: 1))
        XCTAssertEqual(hub.photosLeft(player: giulia), 1)
        XCTAssertEqual(hub.takePhoto(player: giulia), .allowed)
        XCTAssertEqual(hub.takePhoto(player: giulia), .limit)
        XCTAssertEqual(hub.takePhoto(player: marco), .allowed)
        // Senza identificativo il limite non si potrebbe contare.
        XCTAssertEqual(hub.takePhoto(player: nil), .noPlayer)
        XCTAssertEqual(hub.photosLeft(player: giulia), 0)
        // Una foto rifiutata dalla TV (memoria piena) restituisce lo scatto.
        hub.returnPhoto(player: giulia)
        XCTAssertEqual(hub.takePhoto(player: giulia), .allowed)
    }

    func testTheDisposableCameraShowsPhotosOnlyAfterDeveloping() {
        let hub = makeHub()
        XCTAssertTrue(hub.isDeveloped())
        hub.setPhotoRules(PartyHub.PhotoRules(perGuest: PartyHub.disposableShots, disposable: true))
        XCTAssertFalse(hub.isDeveloped())
        for _ in 0..<3 { _ = hub.takePhoto(player: giulia) }
        XCTAssertEqual(hub.photosTaken(), 3)
        let shots = object(hub.partyJSON(player: giulia))["shots"] as? [String: Any]
        XCTAssertEqual(shots?["left"] as? Int, PartyHub.disposableShots - 3)
        XCTAssertEqual(shots?["disposable"] as? Bool, true)
        XCTAssertEqual(shots?["developed"] as? Bool, false)
        hub.develop()
        XCTAssertTrue(hub.isDeveloped())
        hub.clear()
        XCTAssertFalse(hub.isDeveloped())
        XCTAssertEqual(hub.photosTaken(), 0)
    }

    func testThreeMinutesWithoutPhonesRefusePhotosReactionsAndMessages() {
        let hub = makeHub()
        hub.startQuiet()
        XCTAssertEqual(hub.quietTimeLeft(), PartyHub.quietTime)
        XCTAssertEqual(hub.takePhoto(player: giulia), .quiet)
        XCTAssertEqual(hub.handle(["type": "react", "player": giulia, "reaction": "0"]).status, 423)
        XCTAssertEqual(object(hub.partyJSON(player: giulia))["quiet"] as? Int, 180)
        clock.advance(PartyHub.quietTime)
        XCTAssertEqual(hub.quietTimeLeft(), 0)
        XCTAssertEqual(hub.handle(["type": "react", "player": giulia, "reaction": "0"]).status, 200)
        hub.startQuiet()
        hub.stopQuiet()
        XCTAssertEqual(hub.takePhoto(player: giulia), .allowed)
    }

    func testASnapshotBringsBackMessagesNamesReactionsAndShots() {
        let hub = makeHub()
        hub.setPhotoRules(PartyHub.PhotoRules(perGuest: 27, disposable: true))
        hub.showing("foto-1")
        XCTAssertEqual(hub.react(player: giulia, index: 0, name: "Giulia"), .ok)
        XCTAssertEqual(hub.message(player: marco, text: "Auguri \"a tutti\"", name: "Marco"), .ok)
        XCTAssertEqual(hub.takePhoto(player: giulia), .allowed)
        hub.develop()
        let restored = makeHub()
        restored.setPhotoRules(PartyHub.PhotoRules(perGuest: 27, disposable: true))
        XCTAssertTrue(restored.restore(hub.snapshot()))
        XCTAssertEqual(restored.approvedMessages().map(\.text), ["Auguri \"a tutti\""])
        XCTAssertEqual(restored.participants().map(\.name), ["Giulia", "Marco"])
        XCTAssertEqual(restored.reactionCount("foto-1"), 1)
        XCTAssertEqual(restored.photosLeft(player: giulia), 26)
        XCTAssertTrue(restored.isDeveloped())
        // Un salvataggio rovinato non cancella nulla.
        XCTAssertFalse(restored.restore(Data("{non è json".utf8)))
        XCTAssertEqual(restored.approvedMessages().count, 1)
    }

    func testHostCommandsStartTheQuietMomentAndDevelopTheRoll() {
        let clock = clock
        let host = HostRemote(now: { clock.now })
        let received = Received()
        host.setOnCommand { received.add($0) }
        guard case let .accepted(token) = host.login(host.currentPIN(), client: "192.168.1.20") else {
            return XCTFail("PIN rifiutato")
        }
        XCTAssertEqual(host.handle(token: token, form: ["command": "quiet"]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "develop"]).status, 200)
        XCTAssertEqual(received.all, [.startQuiet, .develop])
    }

    func testDurablePhotosGoToDiskStraightAway() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("rules-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = GuestPhotoStore(directory: directory, memoryBudget: 10_000_000, maxDiskBytes: 10_000_000, freeSpace: { 1_000_000_000 })
        store.setDurable(true)
        guard case .added = store.add(Data(repeating: 7, count: 2_000), sender: nil, approved: true) else {
            return XCTFail("foto rifiutata")
        }
        XCTAssertEqual(store.memoryUsedBytes, 0)
        XCTAssertEqual(store.diskUsedBytes, 2_000)
    }
}

private final class Received: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [PartyCommand] = []
    func add(_ command: PartyCommand) { lock.withLock { items.append(command) } }
    var all: [PartyCommand] { lock.withLock { items } }
}
