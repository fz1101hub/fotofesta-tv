import XCTest
@testable import FotoFestaCore

final class GoogleDeviceAuthTests: XCTestCase {
    private let http = FakeHTTPClient()
    private let tokens = MemoryTokenStore()
    private let clock = TestClock()

    private func makeAuth(clientID: String = "client", clientSecret: String = "secret") -> GoogleDeviceAuth {
        let clock = self.clock
        return GoogleDeviceAuth(
            http: http, tokens: tokens, clientID: clientID, clientSecret: clientSecret,
            oauthBase: oauthBase, now: { clock.now }, sleep: { _ in }
        )
    }

    func testDeviceFlowWaitsForAuthorizationAndStoresTokens() async throws {
        http.enqueue("POST", "/device/code", .json(#"{"device_code":"dev-1","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"#))
        http.enqueue(
            "POST", "/token",
            .json(#"{"error":"authorization_pending"}"#, status: 428),
            .failure(URLError(.networkConnectionLost)),
            .json(#"{"error":"slow_down"}"#, status: 403),
            .json(#"{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3599}"#)
        )
        let auth = makeAuth()
        let code = try await auth.requestDeviceCode()
        XCTAssertEqual(code.userCode, "ABCD-EFGH")

        let token = try await auth.pollForToken(code)

        XCTAssertEqual(token, "access-1")
        XCTAssertEqual(tokens.refreshToken, "refresh-1")
        XCTAssertEqual(tokens.expiresAt, clock.now.addingTimeInterval(3599))
        let deviceBody = http.requests("POST", "/device/code").first?.bodyString ?? ""
        XCTAssertTrue(deviceBody.contains("client_id=client"))
        XCTAssertTrue(deviceBody.contains("photospicker.mediaitems.readonly"))
        XCTAssertEqual(http.requests("POST", "/token").count, 4)
    }

    func testAccessDeniedIsReported() async {
        http.enqueue("POST", "/token", .json(#"{"error":"access_denied"}"#, status: 403))
        let code = GoogleDeviceAuth.DeviceCode(deviceCode: "d", userCode: "C", verificationURL: "https://g.co", expiresIn: 600, interval: 1)
        do {
            _ = try await makeAuth().pollForToken(code)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .accessDenied)
        }
    }

    func testValidCachedTokenNeedsNoNetwork() async throws {
        tokens.accessToken = "cached"
        tokens.expiresAt = clock.now.addingTimeInterval(600)
        let token = try await makeAuth().validAccessToken()
        XCTAssertEqual(token, "cached")
        XCTAssertTrue(http.requests.isEmpty)
    }

    func testExpiredTokenIsRefreshedKeepingRefreshToken() async throws {
        tokens.accessToken = "old"
        tokens.refreshToken = "refresh-1"
        tokens.expiresAt = clock.now.addingTimeInterval(30)
        http.enqueue("POST", "/token", .json(#"{"access_token":"new","expires_in":3600}"#))

        let token = try await makeAuth().validAccessToken()

        XCTAssertEqual(token, "new")
        XCTAssertEqual(tokens.refreshToken, "refresh-1")
        XCTAssertTrue(http.requests.first?.bodyString.contains("grant_type=refresh_token") ?? false)
    }

    func testRevokedRefreshTokenClearsStorage() async throws {
        tokens.refreshToken = "revoked"
        http.enqueue("POST", "/token", .json(#"{"error":"invalid_grant"}"#, status: 400))
        let token = try await makeAuth().validAccessToken()
        XCTAssertNil(token)
        XCTAssertTrue(tokens.cleared)
    }

    func testRevokeAndDisconnect() async {
        tokens.refreshToken = "refresh-1"
        http.always("POST", "/revoke", .json("{}", status: 400))
        await makeAuth().revokeAndDisconnect()
        XCTAssertEqual(http.requests("POST", "/revoke").first?.bodyString, "token=refresh-1")
        XCTAssertTrue(tokens.cleared)
    }

    func testMissingCredentials() async {
        let auth = makeAuth(clientID: "", clientSecret: "")
        XCTAssertFalse(auth.isConfigured)
        do {
            _ = try await auth.requestDeviceCode()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .notConfigured)
        }
        XCTAssertTrue(http.requests.isEmpty)
    }
}

final class PhotosPickerTests: XCTestCase {
    private let http = FakeHTTPClient()
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makePicker(freeBytes: Int64? = nil) -> PhotosPicker {
        PhotosPicker(http: http, cacheRoot: root, apiBase: apiBase, minimumFreeBytes: 100, freeBytes: { freeBytes }, sleep: { _ in })
    }

    private func media(_ id: String, _ name: String, mime: String = "image/jpeg") -> PhotosPicker.PickedMedia {
        PhotosPicker.PickedMedia(id: id, filename: name, baseURL: "https://lh3.test/\(id)", mimeType: mime)
    }

    private let token: TokenProvider = { "tok" }

    func testSessionIsPolledUntilPhotosAreSelected() async throws {
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","pollingConfig":{"pollInterval":"1s","timeoutIn":"1800s"}}"#))
        http.enqueue(
            "GET", "/v1/sessions/s1",
            .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":false}"#),
            .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true}"#)
        )
        let picker = makePicker()
        let session = try await picker.createSession(token: "tok")
        let done = try await picker.awaitSelection(token: token, session: session)
        XCTAssertTrue(done.mediaItemsSet)
        XCTAssertEqual(http.requests("GET", "/v1/sessions/s1").count, 2)
        XCTAssertEqual(http.requests("POST", "/v1/sessions").first?.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
    }

    func testMediaListFollowsPagesEncodesTokensAndKeepsImages() async throws {
        http.enqueue(
            "GET", "/v1/mediaItems",
            .json(#"{"mediaItems":[{"id":"a","mediaFile":{"baseUrl":"https://lh3/a","mimeType":"image/jpeg","filename":"Mare.jpg"}},{"id":"v","mediaFile":{"baseUrl":"https://lh3/v","mimeType":"video/mp4","filename":"V.mp4"}},{"id":"x","mediaFile":{"mimeType":"image/png","filename":"NoUrl.png"}}],"nextPageToken":"p+2/=&x"}"#),
            .json(#"{"mediaItems":[{"id":"b","mediaFile":{"baseUrl":"https://lh3/b","mimeType":"image/png","filename":"Monti.png"}}]}"#)
        )
        let picked = try await makePicker().listPickedMedia(token: token, sessionID: "s 1")
        XCTAssertEqual(picked.map(\.id), ["a", "b"])
        let pages = http.requests("GET", "/v1/mediaItems")
        let second = URLComponents(url: pages[1].url!, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(second.queryItems?.first { $0.name == "pageToken" }?.value, "p+2/=&x")
        XCTAssertTrue(second.percentEncodedQuery?.contains("p%2B2") ?? false)
    }

    func testDownloadReplacesCacheAndSkipsUnavailablePhotos() async throws {
        let picker = makePicker()
        http.enqueue("GET", "/old=w3840-h2160", .bytes("old"))
        _ = try await picker.download(token: token, items: [media("old", "Vecchia.jpg")]) { _, _ in }

        http.enqueue("GET", "/1=w3840-h2160", .bytes("uno"))
        http.enqueue("GET", "/2=w3840-h2160", .bytes("", status: 500))
        http.enqueue("GET", "/3=w3840-h2160", .bytes("tre"))
        let photos = try await picker.download(token: token, items: [media("1", "Uno.jpg"), media("2", "Due.jpg"), media("3", "Tre.png", mime: "image/png")]) { _, total in
            XCTAssertEqual(total, 3)
        }

        XCTAssertEqual(photos.map(\.title), ["Uno.jpg", "Tre.png"])
        XCTAssertEqual(picker.cachedPhotos().map(\.title), ["Uno.jpg", "Tre.png"])
        let contents = try FileManager.default.contentsOfDirectory(atPath: root.path)
        XCTAssertEqual(contents, ["google_photos"])
    }

    func testFailedDownloadKeepsPreviousCollection() async throws {
        let picker = makePicker()
        http.enqueue("GET", "/keep=w3840-h2160", .bytes("keep"))
        _ = try await picker.download(token: token, items: [media("keep", "Tenuta.jpg")]) { _, _ in }
        http.always("GET", "/broken=w3840-h2160", .bytes("", status: 403))

        let photos = try await picker.download(token: token, items: [media("broken", "Rotta.jpg")]) { _, _ in }

        XCTAssertTrue(photos.isEmpty)
        XCTAssertEqual(picker.cachedPhotos().map(\.title), ["Tenuta.jpg"])
    }

    func testLowSpaceStopsDownloads() async throws {
        let photos = try await makePicker(freeBytes: 10).download(token: token, items: [media("1", "Uno.jpg")]) { _, _ in }
        XCTAssertTrue(photos.isEmpty)
        XCTAssertTrue(http.requests.isEmpty)
    }

    func testInterruptedCacheSwapRestoresPreviousPhotos() async throws {
        let picker = makePicker()
        http.enqueue("GET", "/old=w3840-h2160", .bytes("photo"))
        _ = try await picker.download(token: token, items: [media("old", "Old.jpg")]) { _, _ in }
        let active = root.appendingPathComponent("google_photos", isDirectory: true)
        let backup = root.appendingPathComponent("google_photos.old-interrupted", isDirectory: true)
        try FileManager.default.moveItem(at: active, to: backup)
        XCTAssertEqual(picker.cachedPhotos().map(\.title), ["Old.jpg"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: active.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: backup.path))
    }

    func testCancelledImportCleansStagingAndKeepsOldPhotos() async throws {
        let picker = makePicker()
        http.enqueue("GET", "/old=w3840-h2160", .bytes("photo"))
        _ = try await picker.download(token: token, items: [media("old", "Old.jpg")]) { _, _ in }
        let cancelledToken: TokenProvider = { throw CancellationError() }
        do {
            _ = try await picker.download(token: cancelledToken, items: [media("new", "New.jpg")]) { _, _ in }
            XCTFail("The cancellation should propagate")
        } catch is CancellationError {
            // Expected.
        }
        XCTAssertEqual(picker.cachedPhotos().map(\.title), ["Old.jpg"])
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: root.path)
        XCTAssertEqual(leftovers, ["google_photos"])
    }

    func testHTTPDownloaderRejectsOversizedPayloadBeforeWriting() async throws {
        let target = root.appendingPathComponent("too-large.jpg")
        http.enqueue("GET", "/too-large", .bytes("five!"))
        var request = URLRequest(url: URL(string: "https://lh3.test/too-large")!)
        request.httpMethod = "GET"
        do {
            _ = try await http.download(request, to: target, maxBytes: 3)
            XCTFail("Expected size error")
        } catch {
            XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
        }
    }

    func testManifestCannotPointOutsideCache() throws {
        let directory = root.appendingPathComponent("google_photos", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: root.appendingPathComponent("secret.jpg"))
        try Data(#"[{"id":"a","title":"Out","file":"../secret.jpg"}]"#.utf8).write(to: directory.appendingPathComponent("index.json"))
        XCTAssertTrue(makePicker().cachedPhotos().isEmpty)
    }
}

final class GoogleImportFlowTests: XCTestCase {
    private let http = FakeHTTPClient()
    private let tokens = MemoryTokenStore()
    private let clock = TestClock()
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func flow() -> GoogleImportFlow {
        let clock = self.clock
        let auth = GoogleDeviceAuth(http: http, tokens: tokens, clientID: "client", clientSecret: "secret", oauthBase: oauthBase, now: { clock.now }, sleep: { _ in })
        let picker = PhotosPicker(http: http, cacheRoot: root, apiBase: apiBase, minimumFreeBytes: 0, freeBytes: { nil }, sleep: { _ in })
        return GoogleImportFlow(auth: auth, picker: picker)
    }

    private func mediaItem(_ id: String) -> String {
        #"{"id":"\#(id)","mediaFile":{"baseUrl":"https://lh3.test/\#(id)","mimeType":"image/jpeg","filename":"\#(id).jpg"}}"#
    }

    func testRevokedTokenRestartsAuthorizationAndImports() async throws {
        tokens.accessToken = "revoked"
        tokens.expiresAt = clock.now.addingTimeInterval(3000)
        http.enqueue("POST", "/v1/sessions", .json(#"{"error":{"code":401,"message":"Invalid"}}"#, status: 401))
        http.enqueue("POST", "/device/code", .json(#"{"device_code":"d","user_code":"WXYZ","verification_url":"https://www.google.com/device","expires_in":600,"interval":1}"#))
        http.enqueue("POST", "/token", .json(#"{"access_token":"fresh","refresh_token":"r","expires_in":3600}"#))
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true}"#))
        http.always("DELETE", "/v1/sessions/s1", .json("{}"))
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"mediaItems":[\#(mediaItem("p1")),\#(mediaItem("p2"))]}"#))
        http.enqueue("GET", "/p1=w3840-h2160", .bytes("1"))
        http.enqueue("GET", "/p2=w3840-h2160", .bytes("2"))
        let steps = StepRecorder()

        let result = try await flow().run { steps.append($0) }

        XCTAssertEqual(result.photos.map(\.title), ["p1.jpg", "p2.jpg"])
        XCTAssertEqual(result.skipped, 0)
        XCTAssertTrue(tokens.cleared)
        XCTAssertEqual(steps.all.first, .waitingForAuthorization(verificationURL: "https://www.google.com/device", userCode: "WXYZ", expiresIn: 600))
        XCTAssertTrue(steps.all.contains(.waitingForPhotos(pickerURI: "https://photos.google.com/picker/s1")))
        XCTAssertEqual(http.requests("POST", "/v1/sessions").map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer revoked", "Bearer fresh"])
        XCTAssertEqual(http.requests("DELETE", "/v1/sessions/s1").count, 1)
    }

    func testTokenExpiringDuringSelectionIsRefreshed() async throws {
        tokens.accessToken = "old"
        tokens.refreshToken = "refresh"
        tokens.expiresAt = clock.now.addingTimeInterval(90)
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://p/s1","mediaItemsSet":false,"pollingConfig":{"pollInterval":"1s"}}"#))
        http.enqueue("GET", "/v1/sessions/s1", .json(#"{"id":"s1","pickerUri":"https://p/s1","mediaItemsSet":true}"#))
        http.always("DELETE", "/v1/sessions/s1", .json("{}"))
        http.enqueue("POST", "/token", .json(#"{"access_token":"new","expires_in":3600}"#))
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"mediaItems":[\#(mediaItem("p1"))]}"#))
        http.enqueue("GET", "/p1=w3840-h2160", .bytes("1"))
        let clock = self.clock
        // Mentre l'utente sceglie dal telefono passano dieci minuti: il token iniziale scade.
        http.onRequest = { request in
            if request.httpMethod == "GET", request.url?.path == "/v1/sessions/s1" { clock.advance(600) }
        }

        let result = try await flow().run { _ in }

        XCTAssertEqual(result.photos.count, 1)
        XCTAssertEqual(http.requests("POST", "/v1/sessions").first?.value(forHTTPHeaderField: "Authorization"), "Bearer old")
        XCTAssertEqual(http.requests("GET", "/v1/mediaItems").first?.value(forHTTPHeaderField: "Authorization"), "Bearer new")
    }

    func testEmptySelectionIsReportedAndSessionDeleted() async {
        tokens.accessToken = "ok"
        tokens.expiresAt = clock.now.addingTimeInterval(3000)
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://p/s1","mediaItemsSet":true}"#))
        http.always("DELETE", "/v1/sessions/s1", .json("{}"))
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"mediaItems":[]}"#))
        do {
            _ = try await flow().run { _ in }
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .nothingSelected)
        }
        XCTAssertEqual(http.requests("DELETE", "/v1/sessions/s1").count, 1)
    }
}

/// Raccoglie i passi notificati dal flusso (anche da più task).
final class StepRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var steps: [ImportStep] = []

    func append(_ step: ImportStep) {
        lock.lock(); defer { lock.unlock() }
        steps.append(step)
    }

    var all: [ImportStep] {
        lock.lock(); defer { lock.unlock() }
        return steps
    }
}
