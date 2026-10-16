import Network
import XCTest
@testable import FotoFestaCore

final class GuestPhotoStoreTests: XCTestCase {
    private var directory: URL!
    private var clock = Date(timeIntervalSince1970: 1_000_000)
    private var free = 1_000_000_000

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("guests-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore(memory: Int = 300, disk: Int = 1_000, maxPhotos: Int = 1_000) -> GuestPhotoStore {
        let box = TestBox(now: clock, free: free)
        boxes.append(box)
        return GuestPhotoStore(
            directory: directory,
            memoryBudget: memory,
            maxDiskBytes: disk,
            freeSpace: { box.free },
            maxPhotos: maxPhotos,
            now: { box.tick() }
        )
    }

    private var boxes: [TestBox] = []

    private func photo(_ size: Int, _ fill: UInt8 = 1) -> Data { Data(repeating: fill, count: size) }

    private func files() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
    }

    func testPhotosStayInMemoryWhileBudgetAllows() {
        let store = makeStore()
        let first = store.add(photo(100), sender: "Anna", approved: true)!
        store.add(photo(100), sender: nil, approved: true)
        guard case .data = store.open(first.id) else { return XCTFail("Dovrebbe essere in RAM") }
        XCTAssertEqual(store.memoryUsedBytes, 200)
        XCTAssertEqual(store.diskUsedBytes, 0)
        XCTAssertTrue(files().isEmpty)
    }

    func testOldestPhotosMoveToDiskWhenMemoryRunsOut() throws {
        let store = makeStore(memory: 250)
        let first = store.add(photo(100, 1), sender: nil, approved: true)!
        let second = store.add(photo(100, 2), sender: nil, approved: true)!
        store.add(photo(100, 3), sender: nil, approved: true)
        guard case let .file(url) = store.open(first.id) else { return XCTFail("Dovrebbe essere su disco") }
        XCTAssertEqual(try Data(contentsOf: url), photo(100, 1))
        guard case .data = store.open(second.id) else { return XCTFail("Dovrebbe essere in RAM") }
        XCTAssertEqual(store.memoryUsedBytes, 200)
        XCTAssertEqual(store.diskUsedBytes, 100)
    }

    func testWhenDiskIsFullOldestPhotosMakeRoom() {
        let store = makeStore(memory: 100, disk: 200)
        let ids = (1...5).map { store.add(photo(100, UInt8($0)), sender: nil, approved: true)!.id }
        XCTAssertEqual(store.photos.map(\.id), Array(ids.dropFirst(2)))
        XCTAssertNil(store.open(ids[0]))
        XCTAssertEqual(files().count, 2)
        XCTAssertEqual(store.diskUsedBytes, 200)
    }

    func testPhotoThatFitsNowhereIsRefused() {
        free = 0
        let store = makeStore(memory: 50)
        XCTAssertNil(store.add(photo(100), sender: nil, approved: true))
        XCTAssertTrue(store.photos.isEmpty)
        XCTAssertEqual(store.memoryUsedBytes, 0)
    }

    func testSpillAllAndRestoreKeepSenderAndApproval() {
        let store = makeStore(memory: 1_000)
        let first = store.add(photo(100, 7), sender: "Zia_Maria 🎉", approved: false)!
        store.add(photo(50, 8), sender: nil, approved: true)
        store.spillAll()
        XCTAssertEqual(store.memoryUsedBytes, 0)

        let restored = makeStore()
        XCTAssertEqual(restored.restore(maxAge: 60), 2)
        let photos = restored.photos
        XCTAssertEqual(photos[0].id, first.id)
        XCTAssertEqual(photos[0].sender, "Zia_Maria 🎉")
        XCTAssertFalse(photos[0].approved)
        XCTAssertNil(photos[1].sender)
        XCTAssertTrue(photos[1].approved)
        XCTAssertEqual(restored.diskUsedBytes, 150)
    }

    func testApprovingPhotoOnDiskSurvivesRestart() {
        let store = makeStore()
        let added = store.add(photo(100), sender: "Anna", approved: false)!
        store.spillAll()
        XCTAssertTrue(store.approve(added.id))
        let restored = makeStore()
        restored.restore(maxAge: 60)
        XCTAssertEqual(restored.photos.first?.approved, true)
    }

    func testRestoreAfterLongPauseDeletesEverything() {
        let store = makeStore()
        store.add(photo(100), sender: nil, approved: true)
        store.spillAll()
        clock = clock.addingTimeInterval(13 * 3600)
        let restored = makeStore()
        XCTAssertEqual(restored.restore(maxAge: 12 * 3600), 0)
        XCTAssertTrue(files().isEmpty)
    }

    func testRestoreDeletesUnknownFiles() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: directory.appendingPathComponent("note.txt"))
        try Data("x".utf8).write(to: directory.appendingPathComponent("123_zzzzzzzzzzzzzzzz_a_.img"))
        let store = makeStore()
        XCTAssertEqual(store.restore(maxAge: 60), 0)
        XCTAssertTrue(files().isEmpty)
    }

    func testRemoveAndClear() {
        let store = makeStore(memory: 100)
        let first = store.add(photo(100), sender: nil, approved: true)!
        let second = store.add(photo(100), sender: nil, approved: true)!
        store.remove(first.id)
        XCTAssertNil(store.open(first.id))
        XCTAssertEqual(store.diskUsedBytes, 0)
        store.clear()
        XCTAssertNil(store.open(second.id))
        XCTAssertTrue(store.photos.isEmpty)
        XCTAssertTrue(files().isEmpty)
    }

    func testChangesAreReported() {
        let store = makeStore()
        let reported = expectation(description: "notifica")
        store.setOnChange { photos in
            if photos.count == 1 { reported.fulfill() }
        }
        store.add(photo(10), sender: nil, approved: true)
        wait(for: [reported], timeout: 1)
    }
}

/// Orologio e spazio libero finti, condivisi con le closure dell'archivio.
private final class TestBox: @unchecked Sendable {
    private var now: Date
    let free: Int

    init(now: Date, free: Int) {
        self.now = now
        self.free = free
    }

    func tick() -> Date {
        now = now.addingTimeInterval(0.001)
        return now
    }
}

final class GuestSupportTests: XCTestCase {
    func testSenderNamesAreCleaned() {
        XCTAssertNil(GuestSender.clean(nil))
        XCTAssertNil(GuestSender.clean("   \u{200B} "))
        XCTAssertEqual(GuestSender.clean(" Anna \t\u{202E}Rossi\u{0} "), "Anna Rossi")
        let cleaned = GuestSender.clean(String(repeating: "🎉", count: 50))
        XCTAssertEqual(cleaned, String(repeating: "🎉", count: GuestSender.maxLength))
    }

    func testImageFormatsAreRecognised() {
        XCTAssertEqual(GuestImageFormat.sniff(Data([0xFF, 0xD8, 0xFF, 0xE0, 1])), .jpeg)
        XCTAssertEqual(GuestImageFormat.sniff(Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0])), .png)
        XCTAssertEqual(GuestImageFormat.sniff(Data("RIFF\0\0\0\0WEBPVP8 ".utf8)), .webp)
        XCTAssertNil(GuestImageFormat.sniff(Data("GIF89a".utf8)))
        XCTAssertNil(GuestImageFormat.sniff(Data([0xFF])))
        XCTAssertFalse(GuestImageFormat.isDisplayable(Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3])))
    }

    func testWifiPayloads() {
        XCTAssertEqual(WifiNetwork(ssid: "Casa Rossi", password: "segreta123").qrPayload, "WIFI:T:WPA;S:Casa Rossi;P:segreta123;;")
        XCTAssertEqual(WifiNetwork(ssid: "Ospiti", password: "").qrPayload, "WIFI:T:nopass;S:Ospiti;;")
        XCTAssertEqual(WifiNetwork(ssid: #"a;b,c:d"#, password: #"p\w"d;12"#).qrPayload, #"WIFI:T:WPA;S:a\;b\,c\:d;P:p\\w\"d\;12;;"#)
    }

    func testWifiValidation() {
        XCTAssertNil(WifiNetwork.validate(ssid: "Casa", password: ""))
        XCTAssertNil(WifiNetwork.validate(ssid: "Casa", password: "12345678"))
        XCTAssertEqual(WifiNetwork.validate(ssid: "  ", password: "12345678"), .missingName)
        XCTAssertEqual(WifiNetwork.validate(ssid: String(repeating: "x", count: 33), password: ""), .nameTooLong)
        XCTAssertEqual(WifiNetwork.validate(ssid: "Casa", password: "1234567"), .badPassword)
    }

    private func photo(_ id: String) -> Photo { Photo(id: id, title: id, source: .bundled(fileName: "\(id).jpg")) }

    func testGuestsJoinTheCollection() {
        let base = [photo("a"), photo("b")]
        let guests = [photo("g1")]
        XCTAssertEqual(combineWithGuests(base: base, baseIsDemo: false, guests: guests, partyActive: true), base + guests)
        XCTAssertEqual(combineWithGuests(base: base, baseIsDemo: true, guests: guests, partyActive: false), guests)
        XCTAssertEqual(combineWithGuests(base: base, baseIsDemo: true, guests: [], partyActive: true), [])
        XCTAssertEqual(combineWithGuests(base: base, baseIsDemo: true, guests: [], partyActive: false), base)
    }

    func testFreshPhotosComeNextAndPlaybackResumes() {
        let photos = [photo("a"), photo("b"), photo("c"), photo("g1"), photo("g2")]
        let fresh = FreshPhotos()
        fresh.add(["g2", "g1"])
        XCTAssertEqual(fresh.peek(photos, currentIndex: 1), 4)
        XCTAssertEqual(fresh.take(photos, currentIndex: 1), 4)
        XCTAssertEqual(fresh.peekResume(photos), 1)
        XCTAssertEqual(fresh.take(photos, currentIndex: 4), 3)
        XCTAssertNil(fresh.take(photos, currentIndex: 3))
        XCTAssertEqual(fresh.takeResume(photos), 1)
        XCTAssertNil(fresh.takeResume(photos))
    }
}

final class GuestUploadServerTests: XCTestCase {
    func testParsesRequestHead() throws {
        let request = try GuestUploadServer.parseHead(Data("GET /?k=abc&x=1 HTTP/1.1\r\nHost: tv:8\r\nX-Test:  value \r".utf8))
        XCTAssertEqual(request.method, "GET")
        XCTAssertEqual(request.path, "/")
        XCTAssertEqual(request.query["k"], "abc")
        XCTAssertEqual(request.headers["x-test"], "value")
        XCTAssertThrowsError(try GuestUploadServer.parseHead(Data("POST /photos HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2".utf8)))
        XCTAssertThrowsError(try GuestUploadServer.parseHead(Data("nonsense".utf8)))
    }

    func testFindsEndOfHeaders() {
        let crlf = Data("GET / HTTP/1.1\r\nHost: x\r\n\r\nBODY".utf8)
        let end = GuestUploadServer.headEnd(in: crlf)
        XCTAssertEqual(end.map { String(data: crlf.subdata(in: $0.bodyStart..<crlf.endIndex), encoding: .utf8) }, "BODY")
        XCTAssertNotNil(GuestUploadServer.headEnd(in: Data("GET / HTTP/1.1\nHost: x\n\n".utf8)))
        XCTAssertNil(GuestUploadServer.headEnd(in: Data("GET / HTTP/1.1\r\nHost: x\r\n".utf8)))
    }

    func testOnlyHomeNetworkAddressesAreLocal() {
        XCTAssertTrue(GuestUploadServer.isLocalIPv4([192, 168, 1, 20]))
        XCTAssertTrue(GuestUploadServer.isLocalIPv4([10, 0, 0, 5]))
        XCTAssertTrue(GuestUploadServer.isLocalIPv4([172, 20, 1, 1]))
        XCTAssertFalse(GuestUploadServer.isLocalIPv4([172, 40, 1, 1]))
        XCTAssertFalse(GuestUploadServer.isLocalIPv4([8, 8, 8, 8]))
        XCTAssertEqual(GuestUploadServer.hostHeader(host: "192.168.1.5", port: 8765), "192.168.1.5:8765")
        XCTAssertEqual(GuestUploadServer.hostHeader(host: "fe80::1", port: 80), "[fe80::1]:80")
    }

    func testServesPageAndReceivesPhotos() async throws {
        let received = ReceivedPhotos()
        let server = GuestUploadServer(
            key: "test-key",
            maxUploadBytes: 1_000,
            page: { "<html>pagina</html>" },
            onPhoto: { data, sender in
                received.append(data, sender)
                return .shown
            }
        )
        let port = try await server.start(host: "127.0.0.1", preferredPort: 0)
        defer { server.stop() }
        let base = "http://127.0.0.1:\(port)"
        let session = URLSession(configuration: .ephemeral)

        let (pageData, pageResponse) = try await session.data(from: URL(string: "\(base)/?k=test-key")!)
        XCTAssertEqual((pageResponse as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(String(data: pageData, encoding: .utf8), "<html>pagina</html>")

        let (_, wrongKey) = try await session.data(from: URL(string: "\(base)/?k=wrong")!)
        XCTAssertEqual((wrongKey as? HTTPURLResponse)?.statusCode, 403)

        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4])
        var upload = URLRequest(url: URL(string: "\(base)/photos")!)
        upload.httpMethod = "POST"
        upload.setValue("test-key", forHTTPHeaderField: "X-FotoFesta-Key")
        upload.setValue("Zia%20Maria", forHTTPHeaderField: "X-FotoFesta-Sender")
        let (body, uploaded) = try await session.upload(for: upload, from: jpeg)
        XCTAssertEqual((uploaded as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(String(data: body, encoding: .utf8), #"{"status":"shown"}"#)
        XCTAssertEqual(received.items.first?.0, jpeg)
        XCTAssertEqual(received.items.first?.1, "Zia Maria")

        var noKey = upload
        noKey.setValue(nil, forHTTPHeaderField: "X-FotoFesta-Key")
        let (_, refused) = try await session.upload(for: noKey, from: jpeg)
        XCTAssertEqual((refused as? HTTPURLResponse)?.statusCode, 401)

        let (_, notImage) = try await session.upload(for: upload, from: Data("<script>".utf8))
        XCTAssertEqual((notImage as? HTTPURLResponse)?.statusCode, 415)
        XCTAssertEqual(received.items.count, 1)
    }
}

/// Foto ricevute dal server nei test, scritte dalla coda del server.
private final class ReceivedPhotos: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [(Data, String?)] = []

    func append(_ data: Data, _ sender: String?) {
        lock.lock()
        stored.append((data, sender))
        lock.unlock()
    }

    var items: [(Data, String?)] {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}
