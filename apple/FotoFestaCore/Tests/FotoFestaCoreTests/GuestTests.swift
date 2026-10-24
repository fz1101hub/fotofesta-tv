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

    private func makeStore(memory: Int = 300, disk: Int = 1_000, maxPhotos: Int = 1_000, maxPending: Int = 100) -> GuestPhotoStore {
        let box = TestBox(now: clock, free: free)
        return GuestPhotoStore(
            directory: directory,
            memoryBudget: memory,
            maxDiskBytes: disk,
            freeSpace: { box.free },
            maxPhotos: maxPhotos,
            maxPending: maxPending,
            now: { box.tick() }
        )
    }

    private func photo(_ size: Int, _ fill: UInt8 = 1) -> Data { Data(repeating: fill, count: size) }

    @discardableResult
    private func put(_ store: GuestPhotoStore, _ data: Data, sender: String? = nil, approved: Bool = true) -> GuestPhoto {
        guard let photo = store.add(data, sender: sender, approved: approved).photo else {
            XCTFail("Foto non aggiunta")
            return GuestPhoto(id: "", sender: nil, receivedAt: Date(), sizeBytes: 0, approved: false)
        }
        return photo
    }

    private func files() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
    }

    func testPhotosStayInMemoryWhileBudgetAllows() {
        let store = makeStore()
        let first = put(store, photo(100), sender: "Anna")
        put(store, photo(100))
        XCTAssertEqual(store.isOnDisk(first.id), false)
        XCTAssertEqual(store.memoryUsedBytes, 200)
        XCTAssertEqual(store.diskUsedBytes, 0)
        XCTAssertTrue(files().isEmpty)
    }

    func testOldestPhotosMoveToDiskWhenMemoryRunsOut() {
        let store = makeStore(memory: 250)
        let first = put(store, photo(100, 1))
        let second = put(store, photo(100, 2))
        put(store, photo(100, 3))
        XCTAssertEqual(store.isOnDisk(first.id), true)
        XCTAssertEqual(store.read(first.id), photo(100, 1))
        XCTAssertEqual(store.isOnDisk(second.id), false)
        XCTAssertEqual(store.memoryUsedBytes, 200)
        XCTAssertEqual(store.diskUsedBytes, 100)
    }

    func testFallbackFolderWorksWithRealFreeSpaceBeforeItExists() {
        let missing = directory.appendingPathComponent("not/yet/created")
        let store = GuestPhotoStore(directory: missing, memoryBudget: 150, maxDiskBytes: 10_000)
        let first = store.add(photo(100), sender: nil, approved: true).photo
        store.add(photo(100), sender: nil, approved: true)
        XCTAssertEqual(first.flatMap { store.isOnDisk($0.id) }, true)
    }

    func testWhenDiskIsFullOldestPhotosMakeRoom() {
        let store = makeStore(memory: 100, disk: 200)
        let ids = (1...5).map { put(store, photo(100, UInt8($0))).id }
        XCTAssertEqual(store.photos.map(\.id), Array(ids.dropFirst(2)))
        XCTAssertNil(store.read(ids[0]))
        XCTAssertEqual(files().count, 2)
        XCTAssertEqual(store.diskUsedBytes, 200)
    }

    func testPendingPhotosAreSacrificedBeforeApprovedOnes() {
        let store = makeStore(memory: 100, disk: 200)
        let approved = put(store, photo(100, 1))
        let pending = put(store, photo(100, 2), approved: false)
        put(store, photo(100, 3))
        put(store, photo(100, 4))
        XCTAssertNil(store.read(pending.id))
        XCTAssertEqual(store.read(approved.id), photo(100, 1))
    }

    func testPendingPhotoNeverPushesOutApprovedOnes() {
        let store = makeStore(memory: 100, disk: 200)
        let approved = (1...3).map { put(store, photo(100, UInt8($0))).id }
        XCTAssertEqual(store.add(photo(100, 9), sender: nil, approved: false), .full)
        XCTAssertEqual(store.photos.map(\.id), approved)
    }

    func testPendingPhotosAreCapped() {
        let store = makeStore(memory: 10_000, maxPending: 2)
        put(store, photo(10), approved: false)
        put(store, photo(10), approved: false)
        XCTAssertEqual(store.add(photo(10), sender: nil, approved: false), .tooManyPending)
        XCTAssertNotNil(store.add(photo(10), sender: nil, approved: true).photo)
    }

    func testPhotoThatFitsNowhereIsRefused() {
        free = 0
        let store = makeStore(memory: 50)
        XCTAssertEqual(store.add(photo(100), sender: nil, approved: true), .full)
        XCTAssertTrue(store.photos.isEmpty)
        XCTAssertEqual(store.memoryUsedBytes, 0)
    }

    func testSpillAllAndRestoreKeepSenderAndApproval() {
        let store = makeStore(memory: 1_000)
        let first = put(store, photo(100, 7), sender: "Zia_Maria 🎉", approved: false)
        put(store, photo(50, 8))
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
        XCTAssertEqual(restored.read(first.id), photo(100, 7))
        XCTAssertEqual(restored.diskUsedBytes, 150)
    }

    func testApprovingPhotoOnDiskSurvivesRestartAndStaysReadable() {
        let store = makeStore()
        let added = put(store, photo(100, 5), sender: "Anna", approved: false)
        store.spillAll()
        XCTAssertTrue(store.approve(added.id))
        XCTAssertEqual(store.read(added.id), photo(100, 5))
        let restored = makeStore()
        restored.restore(maxAge: 60)
        XCTAssertEqual(restored.photos.first?.approved, true)
    }

    func testRestoreAfterLongPauseDeletesEverything() {
        let store = makeStore()
        put(store, photo(100))
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
        let first = put(store, photo(100))
        let second = put(store, photo(100))
        store.remove(first.id)
        XCTAssertNil(store.read(first.id))
        XCTAssertEqual(store.diskUsedBytes, 0)
        store.clear()
        XCTAssertNil(store.read(second.id))
        XCTAssertTrue(store.photos.isEmpty)
        XCTAssertTrue(files().isEmpty)
    }

    func testChangesAreReported() {
        let store = makeStore()
        let reported = expectation(description: "notifica")
        store.setOnChange { photos in
            if photos.count == 1 { reported.fulfill() }
        }
        put(store, photo(10))
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

    func testAfterFreshPhotosPlaybackSkipsTheOnesJustShown() {
        let photos = [photo("g1"), photo("g2"), photo("g3")]
        let order = PlaybackOrder()
        order.reset(count: 3, shuffle: false)
        let fresh = FreshPhotos()
        fresh.add(["g2", "g3"])
        XCTAssertEqual(fresh.take(photos, currentIndex: 0), 1)
        XCTAssertEqual(fresh.take(photos, currentIndex: 1), 2)
        XCTAssertNil(fresh.take(photos, currentIndex: 2))
        XCTAssertEqual(fresh.resume(photos, next: { order.next(after: $0) }), 0)
        XCTAssertNil(fresh.resume(photos, next: { order.next(after: $0) }))
    }

    func testRemapKeepsShuffledCycle() {
        // Mescolamento e inserimenti deterministici: ordine invertito, inserimento in fondo.
        let order = PlaybackOrder(shuffler: { Array($0.reversed()) }, randomIndex: { $0.upperBound })
        order.reset(count: 4, shuffle: true, current: 0)   // 0, 3, 2, 1
        XCTAssertEqual(order.next(after: 0), 3)
        order.remap(count: 5, current: 3) { $0 }          // la foto 4 è nuova: 0, 3, 2, 1, 4
        XCTAssertEqual(order.next(after: 3), 2)
        XCTAssertEqual(order.next(after: 2), 1)
        XCTAssertEqual(order.next(after: 1), 4)
    }

    func testRemapDropsRemovedPhotos() {
        let order = PlaybackOrder(shuffler: { $0 }, randomIndex: { $0.upperBound })
        order.reset(count: 4, shuffle: true, current: 1)   // 1, 0, 2, 3
        order.remap(count: 3, current: 0) { $0 == 1 ? nil : ($0 > 1 ? $0 - 1 : $0) } // tolta la 1
        XCTAssertEqual(order.next(after: 0), 1)
        XCTAssertEqual(order.next(after: 1), 2)
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

    func testHostHeaderAndUploadID() throws {
        XCTAssertEqual(GuestUploadServer.hostHeader(host: "192.168.1.5", port: 8765), "192.168.1.5:8765")
        XCTAssertEqual(GuestUploadServer.hostHeader(host: "fe80::1", port: 80), "[fe80::1]:80")
        let valid = try GuestUploadServer.parseHead(Data("POST /photos HTTP/1.1\r\nX-FotoFesta-Upload: abc123def456".utf8))
        XCTAssertEqual(GuestUploadServer.uploadID(valid), "abc123def456")
        let invalid = try GuestUploadServer.parseHead(Data("POST /photos HTTP/1.1\r\nX-FotoFesta-Upload: <script>".utf8))
        XCTAssertNil(GuestUploadServer.uploadID(invalid))
    }

    func testServesPageAndReceivesPhotos() async throws {
        let received = ReceivedPhotos()
        let server = GuestUploadServer(
            key: "test-key",
            maxUploadBytes: 1_000,
            maxUploadsPerMinute: 4,
            page: { "<html>pagina</html>" },
            onPhoto: { data, sender, _ in
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
        upload.setValue("upload-000001", forHTTPHeaderField: "X-FotoFesta-Upload")
        let (body, uploaded) = try await session.upload(for: upload, from: jpeg)
        XCTAssertEqual((uploaded as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(String(data: body, encoding: .utf8), #"{"status":"shown"}"#)
        XCTAssertEqual(received.items.first?.0, jpeg)
        XCTAssertEqual(received.items.first?.1, "Zia Maria")

        // Lo stesso invio ripetuto non salva una seconda foto.
        let (_, repeated) = try await session.upload(for: upload, from: jpeg)
        XCTAssertEqual((repeated as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(received.items.count, 1)

        var noKey = upload
        noKey.setValue(nil, forHTTPHeaderField: "X-FotoFesta-Key")
        let (_, refused) = try await session.upload(for: noKey, from: jpeg)
        XCTAssertEqual((refused as? HTTPURLResponse)?.statusCode, 401)

        var other = upload
        other.setValue("upload-000002", forHTTPHeaderField: "X-FotoFesta-Upload")
        let (_, notImage) = try await session.upload(for: other, from: Data("<script>".utf8))
        XCTAssertEqual((notImage as? HTTPURLResponse)?.statusCode, 415)
        XCTAssertEqual(received.items.count, 1)

        // Quattro invii al minuto: il quinto da questo telefono viene rimandato.
        for number in 3...4 {
            other.setValue("upload-00000\(number)", forHTTPHeaderField: "X-FotoFesta-Upload")
            _ = try await session.upload(for: other, from: jpeg)
        }
        other.setValue("upload-000005", forHTTPHeaderField: "X-FotoFesta-Upload")
        let (_, limited) = try await session.upload(for: other, from: jpeg)
        XCTAssertEqual((limited as? HTTPURLResponse)?.statusCode, 429)
        XCTAssertEqual((limited as? HTTPURLResponse)?.value(forHTTPHeaderField: "Retry-After"), "10")
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
