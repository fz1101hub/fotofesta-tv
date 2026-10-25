@testable import FotoFestaCore
import XCTest

final class PartyExtrasTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("fotofesta-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
    }

    private let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4])

    func testCRC32MatchesTheStandardCheckValue() {
        XCTAssertEqual(ZipStream.crc32(Data("123456789".utf8)), 0xCBF4_3926)
    }

    func testZipArchiveHasLocalHeadersAndCentralDirectory() throws {
        let url = folder.appendingPathComponent("album.zip")
        let written = try ZipStream.write([
            ZipStream.Entry(name: "uno.jpg") { Data("prima".utf8) },
            ZipStream.Entry(name: "sparita.jpg") { nil },
            ZipStream.Entry(name: "due.mp4") { Data("seconda".utf8) },
        ], to: url)
        XCTAssertEqual(written, 2)
        let data = try Data(contentsOf: url)
        XCTAssertEqual(Array(data.prefix(4)), [0x50, 0x4B, 0x03, 0x04])
        let end = data.suffix(22)
        XCTAssertEqual(Array(end.prefix(4)), [0x50, 0x4B, 0x05, 0x06])
        XCTAssertEqual(Int(end[end.startIndex + 10]), 2)
        XCTAssertNotNil(data.range(of: Data("seconda".utf8)))
    }

    func testPhoneWallLinesUpPhonesAndShowsAPhoto() {
        let wall = PhoneWall()
        XCTAssertEqual(wall.phoneJSON(player: "player-a"), "null")
        XCTAssertEqual(wall.handle(["player": "player-a", "command": "join"]).status, 403)
        wall.start()
        XCTAssertFalse(wall.show("foto"))
        XCTAssertEqual(wall.handle(["player": "player-a", "command": "join"]).status, 200)
        XCTAssertEqual(wall.handle(["player": "player-b", "command": "join"]).status, 200)
        XCTAssertTrue(wall.phoneJSON(player: "player-b").contains(#""slot":1"#))
        XCTAssertTrue(wall.show("foto"))
        XCTAssertEqual(wall.handle(["player": "player-c", "command": "join"]).status, 409)
        XCTAssertEqual(wall.state()?.photoID, "foto")
        XCTAssertEqual(PhoneWall.grid(5).columns, 3)
        XCTAssertEqual(PhoneWall.grid(5).rows, 2)
        XCTAssertEqual(PhoneWall.grid(12).columns, 4)
        XCTAssertEqual(PhoneWall.grid(12).rows, 3)
    }

    func testReceiptsAreSignedWithThePartyKey() {
        let receipt = GuestReceipt.make(key: "chiave", id: "abcdef1234")
        XCTAssertEqual(receipt.count, GuestReceipt.length)
        XCTAssertTrue(GuestReceipt.matches(key: "chiave", id: "abcdef1234", receipt: receipt))
        XCTAssertFalse(GuestReceipt.matches(key: "altra", id: "abcdef1234", receipt: receipt))
        XCTAssertFalse(GuestReceipt.matches(key: "chiave", id: "abcdef1234", receipt: nil))
    }

    func testQRCodeBecomesAnSVG() throws {
        let svg = try XCTUnwrap(QRSVG.encode("http://192.168.1.10:8765/?k=abc"))
        XCTAssertTrue(svg.hasPrefix("<svg"))
        XCTAssertTrue(svg.contains("<path"))
        let modules = try XCTUnwrap(QRSVG.modules("ciao"))
        XCTAssertEqual(modules.count, modules.first?.count)
        // I tre quadrati di posizionamento: angoli in alto e in basso a sinistra neri.
        XCTAssertTrue(modules[0][0])
        XCTAssertTrue(modules[modules.count - 1][0])
    }

    func testScheduleIsParsedAndSorted() {
        let slots = PartySetup.parseSchedule("23:00 Si balla\n20.00 - Aperitivo\nnota senza ora\n25:00 Impossibile\n9:30 Colazione")
        XCTAssertEqual(slots.map(\.time), ["09:30", "20:00", "23:00"])
        XCTAssertEqual(slots[1].title, "Aperitivo")
        XCTAssertTrue(PartySetup.validColor("#FF4F9A"))
        XCTAssertFalse(PartySetup.validColor("rosso"))
    }

    func testSetupSurvivesARestartAndKeepsTheGuestListPrivate() throws {
        let setup = PartySetup(directory: folder)
        setup.setName("  Festa di Giulia ")
        setup.setAccent("#3DA9FC")
        XCTAssertTrue(setup.setLogo(png))
        let sponsor = try XCTUnwrap(setup.addSponsor(png))
        setup.setGuestList("Anna, Luca\nAnna")
        setup.setQuiz("Dove? | Roma | Parigi")
        let kid = try XCTUnwrap(setup.addKid(name: "Giulia", data: png))
        setup.removeKid("../logo")
        let again = PartySetup(directory: folder)
        XCTAssertEqual(again.state.brand.name, "Festa di Giulia")
        XCTAssertEqual(again.state.brand.accent, "#3DA9FC")
        XCTAssertTrue(again.state.brand.hasLogo)
        XCTAssertEqual(again.state.sponsors, [sponsor])
        XCTAssertEqual(again.state.guestList, ["Anna", "Luca"])
        XCTAssertEqual(again.state.kids.map(\.id), [kid])
        XCTAssertNotNil(again.logo())
        XCTAssertFalse(again.publicJSON().contains("Anna"))
        XCTAssertTrue(again.json().contains("Anna"))
        let exported = try XCTUnwrap(again.exportJSON())
        let copy = PartySetup(directory: folder.appendingPathComponent("copia"))
        XCTAssertTrue(copy.importJSON(exported))
        XCTAssertEqual(copy.state.brand.name, "Festa di Giulia")
        XCTAssertEqual(copy.state.sponsors.count, 1)
        XCTAssertTrue(copy.state.kids.isEmpty)
    }

    func testVideosAreStoredOnlyWhenTheyLookLikeMP4() {
        let store = GuestVideoStore(directory: folder.appendingPathComponent("video"), maxTotalBytes: 1_000, freeSpace: { 1_000_000 })
        let mp4 = Data([0, 0, 0, 24]) + Data("ftypmp42".utf8) + Data(repeating: 7, count: 100)
        XCTAssertEqual(store.add(Data(repeating: 1, count: 100), sender: nil), .invalid)
        guard case let .added(video) = store.add(mp4, sender: "Anna") else { return XCTFail("video non salvato") }
        XCTAssertEqual(video.sender, "Anna")
        XCTAssertEqual(store.add(Data(repeating: 0, count: 4) + Data("ftyp".utf8) + Data(repeating: 1, count: 2_000), sender: nil), .full)
        let restored = GuestVideoStore(directory: folder.appendingPathComponent("video"), maxTotalBytes: 1_000, freeSpace: { 1_000_000 })
        restored.restore(maxAge: 3_600)
        XCTAssertEqual(restored.videos.map(\.id), [video.id])
        XCTAssertEqual(restored.videos.first?.sender, "Anna")
        XCTAssertTrue(restored.remove(video.id))
        XCTAssertTrue(restored.videos.isEmpty)
    }

    func testArrivalsAndSongRequests() {
        let hub = PartyHub()
        let welcomed = Welcomed()
        hub.setOnArrival { welcomed.add($0.name) }
        XCTAssertEqual(hub.handle(["type": "arrive", "player": "player-anna", "name": "Anna"]).status, 200)
        XCTAssertEqual(hub.handle(["type": "arrive", "player": "player-anna", "name": "Anna B."]).status, 200)
        XCTAssertEqual(welcomed.all, ["Anna"])
        XCTAssertEqual(hub.allArrivals().first?.name, "Anna B.")
        XCTAssertEqual(hub.handle(["type": "song", "player": "player-anna", "text": "Gloria"]).status, 200)
        XCTAssertEqual(hub.songRequests().map(\.text), ["Gloria"])
        hub.removeSong(hub.songRequests()[0].id)
        XCTAssertTrue(hub.songRequests().isEmpty)
        hub.setFeatures(PartyHub.Features(songs: false))
        XCTAssertEqual(hub.handle(["type": "song", "player": "player-anna", "text": "Gloria"]).status, 403)
        XCTAssertTrue(hub.featuresJSON().contains(#""songs":false"#))
        XCTAssertTrue(hub.featuresJSON().contains(#""delete":true"#))
        // Gli arrivi restano dopo un riavvio dell'app.
        let copy = PartyHub()
        XCTAssertTrue(copy.restore(hub.snapshot()))
        XCTAssertEqual(copy.allArrivals().map(\.name), ["Anna B."])
    }

    func testHostCommandsForSongsAndSetup() {
        let host = HostRemote()
        let received = Received()
        host.setOnCommand { received.add($0) }
        guard case let .accepted(token) = host.login(host.currentPIN(), client: "192.168.1.20") else { return XCTFail("PIN") }
        XCTAssertEqual(host.handle(token: token, form: ["command": "song_done", "id": "abc-1"]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "brand_accent", "text": "rosso"]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "chapter", "text": " La torta "]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "kid_remove", "id": "../logo"]).status, 400)
        XCTAssertEqual(received.all, [.songDone("abc-1"), .setAccent(nil), .chapter("La torta")])
        let hub = PartyHub()
        _ = hub.songRequest(player: "player-anna", text: "Gloria", name: "Anna")
        let json = host.statusJSON(hub: hub, setup: #"{"name":"Festa"}"#)
        XCTAssertTrue(json.contains("Gloria"))
        XCTAssertTrue(json.contains(#""setup":{"name":"Festa"}"#))
        XCTAssertNotNil(try? JSONSerialization.jsonObject(with: Data(json.utf8)))
    }
}

private final class Welcomed: @unchecked Sendable {
    private let lock = NSLock()
    private var names: [String] = []
    func add(_ name: String) { lock.withLock { names.append(name) } }
    var all: [String] { lock.withLock { names } }
}

private final class Received: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [PartyCommand] = []
    func add(_ command: PartyCommand) { lock.withLock { items.append(command) } }
    var all: [PartyCommand] { lock.withLock { items } }
}
