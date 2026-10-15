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

    private func makePicker() -> PhotosPicker {
        PhotosPicker(http: http, apiBase: apiBase, sleep: { _ in })
    }

    private let token: TokenProvider = { "tok" }

    func testSessionIsPolledUntilPhotosAreSelected() async throws {
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","pollingConfig":{"pollInterval":"1s","timeoutIn":"1800s"}}"#))
        http.enqueue(
            "GET", "/v1/sessions/s1",
            .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":false}"#),
            .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true,"expireTime":"2026-10-22T18:30:00.123456Z"}"#)
        )
        let picker = makePicker()
        let session = try await picker.createSession(token: "tok")
        XCTAssertNil(session.expiresAt)
        let done = try await picker.awaitSelection(token: token, session: session)
        XCTAssertTrue(done.mediaItemsSet)
        let expected = ISO8601DateFormatter().date(from: "2026-10-22T18:30:00Z")!.addingTimeInterval(0.123456)
        XCTAssertEqual(done.expiresAt!.timeIntervalSince1970, expected.timeIntervalSince1970, accuracy: 0.001)
        XCTAssertEqual(http.requests("GET", "/v1/sessions/s1").count, 2)
        XCTAssertEqual(http.requests("POST", "/v1/sessions").first?.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
    }

    func testMediaListFollowsPagesEncodesTokensAndMarksPhotos() async throws {
        http.enqueue(
            "GET", "/v1/mediaItems",
            .json(#"{"mediaItems":[{"id":"a","mediaFile":{"baseUrl":"https://lh3/a","mimeType":"image/jpeg","filename":"Mare.jpg"}},{"id":"v","mediaFile":{"baseUrl":"https://lh3/v","mimeType":"video/mp4","filename":"V.mp4"}},{"id":"x","mediaFile":{"mimeType":"image/png","filename":"NoUrl.png"}}],"nextPageToken":"p+2/=&x"}"#),
            .json(#"{"mediaItems":[{"id":"b","mediaFile":{"baseUrl":"https://lh3/b","mimeType":"image/png","filename":"Monti.png"}}]}"#)
        )
        let picked = try await makePicker().listPickedMedia(token: token, sessionID: "s 1")
        XCTAssertEqual(picked.map(\.id), ["a", "v", "b"])
        XCTAssertEqual(picked.map(\.isPhoto), [true, false, true])
        let pages = http.requests("GET", "/v1/mediaItems")
        let second = URLComponents(url: pages[1].url!, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(second.queryItems?.first { $0.name == "pageToken" }?.value, "p+2/=&x")
        XCTAssertTrue(second.percentEncodedQuery?.contains("p%2B2") ?? false)
    }

    func testSizedURLAsksGoogleToFitOrFill() {
        XCTAssertEqual(PhotosPicker.sizedURL(base: "https://lh3/a", width: 1920, height: 1080, crop: false)?.absoluteString, "https://lh3/a=w1920-h1080")
        XCTAssertEqual(PhotosPicker.sizedURL(base: "https://lh3/a", width: 400, height: 400, crop: true)?.absoluteString, "https://lh3/a=w400-h400-c")
    }

    func testPhotosDownloadedByOlderVersionsAreDeleted() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        for name in ["google_photos", "google_photos.staging-1", "google_photos.old-2", "other"] {
            try FileManager.default.createDirectory(at: root.appendingPathComponent(name), withIntermediateDirectories: true)
        }
        PhotosPicker.deleteLegacyDownloads(in: root)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), ["other"])
    }

    func testTimestampsFromGoogleAreParsed() {
        XCTAssertEqual(parseTimestamp("1970-01-01T00:00:00Z"), Date(timeIntervalSince1970: 0))
        XCTAssertEqual(parseTimestamp("1970-01-01T01:00:01+01:00"), Date(timeIntervalSince1970: 1))
        XCTAssertNil(parseTimestamp(""))
        XCTAssertNil(parseTimestamp("domani"))
    }
}

final class GoogleImportFlowTests: XCTestCase {
    private let http = FakeHTTPClient()
    private let tokens = MemoryTokenStore()
    private let clock = TestClock()

    private func flow() -> GoogleImportFlow {
        let clock = self.clock
        let auth = GoogleDeviceAuth(http: http, tokens: tokens, clientID: "client", clientSecret: "secret", oauthBase: oauthBase, now: { clock.now }, sleep: { _ in })
        let picker = PhotosPicker(http: http, apiBase: apiBase, sleep: { _ in })
        return GoogleImportFlow(auth: auth, picker: picker)
    }

    private func mediaItem(_ id: String, mime: String = "image/jpeg") -> String {
        #"{"id":"\#(id)","mediaFile":{"baseUrl":"https://lh3.test/\#(id)","mimeType":"\#(mime)","filename":"\#(id).jpg"}}"#
    }

    func testRevokedTokenRestartsAuthorizationAndKeepsTheSession() async throws {
        tokens.accessToken = "revoked"
        tokens.expiresAt = clock.now.addingTimeInterval(3000)
        http.enqueue("POST", "/v1/sessions", .json(#"{"error":{"code":401,"message":"Invalid"}}"#, status: 401))
        http.enqueue("POST", "/device/code", .json(#"{"device_code":"d","user_code":"WXYZ","verification_url":"https://www.google.com/device","expires_in":600,"interval":1}"#))
        http.enqueue("POST", "/token", .json(#"{"access_token":"fresh","refresh_token":"r","expires_in":3600}"#))
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true,"expireTime":"2027-01-15T08:00:00Z"}"#))
        http.always("DELETE", "/v1/sessions/s1", .json("{}"))
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"mediaItems":[\#(mediaItem("p1")),\#(mediaItem("p2"))]}"#))
        let steps = StepRecorder()

        let result = try await flow().run { steps.append($0) }

        XCTAssertEqual(result.selection.photos.map(\.title), ["p1.jpg", "p2.jpg"])
        XCTAssertEqual(result.selection.photos.first?.source, .google(mediaID: "p1"))
        XCTAssertEqual(result.selection.sessionID, "s1")
        XCTAssertEqual(result.selection.expiresAt, ISO8601DateFormatter().date(from: "2027-01-15T08:00:00Z"))
        XCTAssertEqual(result.skipped, 0)
        XCTAssertTrue(tokens.cleared)
        XCTAssertEqual(steps.all.first, .waitingForAuthorization(verificationURL: "https://www.google.com/device", userCode: "WXYZ", expiresIn: 600))
        XCTAssertEqual(steps.all.last, .waitingForPhotos(pickerURI: "https://photos.google.com/picker/s1"))
        XCTAssertEqual(http.requests("POST", "/v1/sessions").map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer revoked", "Bearer fresh"])
        // La sessione resta aperta per lo streaming e nessuna foto viene scaricata.
        XCTAssertTrue(http.requests("DELETE", "/v1/sessions/s1").isEmpty)
        XCTAssertTrue(http.requests("GET", "/p1").isEmpty)
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
        let clock = self.clock
        // Mentre l'utente sceglie dal telefono passano dieci minuti: il token iniziale scade.
        http.onRequest = { request in
            if request.httpMethod == "GET", request.url?.path == "/v1/sessions/s1" { clock.advance(600) }
        }

        let result = try await flow().run { _ in }

        XCTAssertEqual(result.selection.items.count, 1)
        XCTAssertEqual(http.requests("POST", "/v1/sessions").first?.value(forHTTPHeaderField: "Authorization"), "Bearer old")
        XCTAssertEqual(http.requests("GET", "/v1/mediaItems").first?.value(forHTTPHeaderField: "Authorization"), "Bearer new")
    }

    func testVideosAreSkippedAndCounted() async throws {
        tokens.accessToken = "ok"
        tokens.expiresAt = clock.now.addingTimeInterval(3000)
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://p/s1","mediaItemsSet":true}"#))
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"mediaItems":[\#(mediaItem("ok")),\#(mediaItem("film", mime: "video/mp4"))]}"#))

        let result = try await flow().run { _ in }

        XCTAssertEqual(result.selection.items.map(\.id), ["ok"])
        XCTAssertEqual(result.skipped, 1)
    }

    func testEmptySelectionIsReportedAndSessionDeleted() async {
        tokens.accessToken = "ok"
        tokens.expiresAt = clock.now.addingTimeInterval(3000)
        http.enqueue("POST", "/v1/sessions", .json(#"{"id":"s1","pickerUri":"https://p/s1","mediaItemsSet":true}"#))
        http.always("DELETE", "/v1/sessions/s1", .json("{}"))
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"mediaItems":[\#(mediaItem("film", mime: "video/mp4"))]}"#))
        do {
            _ = try await flow().run { _ in }
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .nothingSelected)
        }
        XCTAssertEqual(http.requests("DELETE", "/v1/sessions/s1").count, 1)
    }
}

final class GooglePhotosStreamTests: XCTestCase {
    private let http = FakeHTTPClient()
    private let tokens = MemoryTokenStore()
    private let clock = TestClock()
    private var defaults: UserDefaults!
    private var store: GoogleSelectionStore!

    override func setUp() {
        defaults = UserDefaults(suiteName: "stream-\(UUID().uuidString)")
        store = GoogleSelectionStore(defaults: defaults)
        tokens.accessToken = "tok"
        tokens.refreshToken = "refresh"
    }

    private func makeStream() -> GooglePhotosStream {
        let clock = self.clock
        let auth = GoogleDeviceAuth(http: http, tokens: tokens, clientID: "client", clientSecret: "secret", oauthBase: oauthBase, now: { clock.now }, sleep: { _ in })
        let picker = PhotosPicker(http: http, apiBase: apiBase, sleep: { _ in })
        return GooglePhotosStream(picker: picker, auth: auth, selections: store, now: { clock.now })
    }

    private func select(_ ids: [String], expiresIn: TimeInterval? = 7 * 24 * 3600) {
        tokens.expiresAt = clock.now.addingTimeInterval(3600)
        store.save(GoogleSelection(sessionID: "s1", expiresAt: expiresIn.map { clock.now.addingTimeInterval($0) }, items: ids.map { .init(id: $0, filename: "\($0).jpg") }))
    }

    private func listing(_ suffix: String, _ ids: [String]) -> FakeHTTPClient.Reply {
        let items = ids.map { #"{"id":"\#($0)","mediaFile":{"baseUrl":"https://lh3.test/\#($0)-\#(suffix)","mimeType":"image/jpeg","filename":"\#($0).jpg"}}"# }
        return .json(#"{"mediaItems":[\#(items.joined(separator: ","))]}"#)
    }

    func testAddressesComeFromTheSessionAndAreReusedForFiftyMinutes() async throws {
        select(["a", "b"])
        http.enqueue("GET", "/v1/mediaItems", listing("1", ["a", "b"]), listing("2", ["a", "b"]))
        let stream = makeStream()

        let first = try await stream.access(mediaID: "a")
        let second = try await stream.access(mediaID: "b")
        clock.advance(49 * 60)
        tokens.expiresAt = clock.now.addingTimeInterval(3600)
        let stillCached = try await stream.access(mediaID: "a")
        clock.advance(2 * 60)
        tokens.expiresAt = clock.now.addingTimeInterval(3600)
        let renewed = try await stream.access(mediaID: "a")

        XCTAssertEqual(first, GooglePhotosStream.Access(baseURL: "https://lh3.test/a-1", accessToken: "tok"))
        XCTAssertEqual(second.baseURL, "https://lh3.test/b-1")
        XCTAssertEqual(stillCached.baseURL, "https://lh3.test/a-1")
        XCTAssertEqual(renewed.baseURL, "https://lh3.test/a-2")
        XCTAssertEqual(http.requests("GET", "/v1/mediaItems").count, 2)
    }

    func testRejectedAddressesShareOneRenewal() async throws {
        select(["a", "b"])
        http.enqueue("GET", "/v1/mediaItems", listing("1", ["a", "b"]), listing("2", ["a", "b"]))
        http.enqueue("POST", "/token", .json(#"{"access_token":"tok2","expires_in":3600}"#))
        let stream = makeStream()

        let firstA = try await stream.access(mediaID: "a")
        let firstB = try await stream.access(mediaID: "b")
        let renewedA = try await stream.access(mediaID: "a", failed: firstA)
        let renewedB = try await stream.access(mediaID: "b", failed: firstB)

        XCTAssertEqual(renewedA, GooglePhotosStream.Access(baseURL: "https://lh3.test/a-2", accessToken: "tok2"))
        XCTAssertEqual(renewedB, GooglePhotosStream.Access(baseURL: "https://lh3.test/b-2", accessToken: "tok2"))
        XCTAssertEqual(http.requests("POST", "/token").count, 1)
        XCTAssertEqual(http.requests("GET", "/v1/mediaItems").count, 2)
    }

    func testDeletedSessionAsksToChooseAgain() async {
        select(["a"])
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"error":{"code":404,"message":"Session not found"}}"#, status: 404))
        let stream = makeStream()
        let gone = GoneRecorder()
        await stream.setOnSelectionGone { gone.append($0, $1) }

        do {
            _ = try await stream.access(mediaID: "a")
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .selectionGone)
        }
        XCTAssertEqual(gone.all.map { $0.1 }, [.selectionExpired])
        XCTAssertNil(store.current())
    }

    func test403RemovesTheSelectionOnlyIfGoogleConfirms() async {
        select(["a"])
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"error":{"code":403}}"#, status: 403), .json(#"{"error":{"code":403}}"#, status: 403))
        http.enqueue(
            "GET", "/v1/sessions/s1",
            .json(#"{"id":"s1","pickerUri":"https://p/s1","mediaItemsSet":true}"#),
            .json(#"{"error":{"code":404}}"#, status: 404)
        )
        let stream = makeStream()

        do {
            _ = try await stream.access(mediaID: "a")
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual((error as? HTTPStatusError)?.code, 403)
        }
        XCTAssertNotNil(store.current())
        do {
            _ = try await stream.access(mediaID: "a")
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .selectionGone)
        }
        XCTAssertNil(store.current())
    }

    func testRevokedAccessAsksToConnectAgain() async {
        select(["a"])
        tokens.expiresAt = clock.now.addingTimeInterval(-10)
        http.enqueue("POST", "/token", .json(#"{"error":"invalid_grant"}"#, status: 400))
        let stream = makeStream()
        let gone = GoneRecorder()
        await stream.setOnSelectionGone { gone.append($0, $1) }

        do {
            _ = try await stream.access(mediaID: "a")
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? FotoFestaError, .sessionExpired)
        }
        XCTAssertEqual(gone.all.map { $0.1 }, [.accessLost])
        XCTAssertTrue(http.requests("GET", "/v1/mediaItems").isEmpty)
    }

    func test401OnTheListingRenewsTheTokenOnce() async throws {
        select(["a"])
        http.enqueue("GET", "/v1/mediaItems", .json(#"{"error":{"code":401}}"#, status: 401), listing("1", ["a"]))
        http.enqueue("POST", "/token", .json(#"{"access_token":"tok2","expires_in":3600}"#))

        let access = try await makeStream().access(mediaID: "a")

        XCTAssertEqual(access, GooglePhotosStream.Access(baseURL: "https://lh3.test/a-1", accessToken: "tok2"))
    }

    func testDeviceClockAloneDoesNotDiscardTheSelection() async throws {
        select(["a"], expiresIn: -1)
        http.enqueue("GET", "/v1/mediaItems", listing("1", ["a"]))

        let access = try await makeStream().access(mediaID: "a")

        XCTAssertEqual(access.baseURL, "https://lh3.test/a-1")
        XCTAssertNotNil(store.current())
    }

    func testSelectionStoreRoundTripAndClear() {
        let selection = GoogleSelection(sessionID: "s1", expiresAt: Date(timeIntervalSince1970: 1_234), items: [.init(id: "a", filename: "Mare.jpg")])
        store.save(selection)
        XCTAssertEqual(GoogleSelectionStore(defaults: defaults).current(), selection)
        store.clear()
        XCTAssertNil(GoogleSelectionStore(defaults: defaults).current())
        XCTAssertNil(store.current())
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

/// Raccoglie le selezioni perse segnalate dallo stream.
final class GoneRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [(String, GooglePhotosStream.GoneReason)] = []

    func append(_ sessionID: String, _ reason: GooglePhotosStream.GoneReason) {
        lock.lock(); defer { lock.unlock() }
        events.append((sessionID, reason))
    }

    var all: [(String, GooglePhotosStream.GoneReason)] {
        lock.lock(); defer { lock.unlock() }
        return events
    }
}
