import XCTest
@testable import FotoFestaCore

/// La pagina per autorizzare Google Foto dal telefono, servita dalla Apple TV su loopback.
final class PhonePageServerTests: XCTestCase {
    private final class Delivered: @unchecked Sendable {
        private let lock = NSLock()
        private(set) var codes: [String] = []
        private(set) var errors: [String] = []
        func code(_ value: String) { lock.lock(); codes.append(value); lock.unlock() }
        func error(_ value: String) { lock.lock(); errors.append(value); lock.unlock() }
    }

    private let session = URLSession(configuration: .ephemeral)

    /// Richiesta senza seguire i reindirizzamenti: il 303 di ritorno dal sito arriva com'è.
    private func get(_ url: String) async throws -> (Data, HTTPURLResponse) {
        let request = URLRequest(url: URL(string: url)!)
        return try await BoundedFetch.data(for: request, session: session, maxBytes: 64 * 1024, allowRedirect: { _ in false })
    }

    func testServesThePageTheWayToGoogleAndTheReturnFromTheSite() async throws {
        let delivered = Delivered()
        let pages = GoogleConsentPages(
            key: "chiave",
            page: { "<html>pagina google</html>" },
            authorizationURL: { "https://accounts.google.com/o/oauth2/v2/auth?state=" + GoogleAuth.encodeState($0) },
            onCode: { delivered.code($0) },
            onError: { delivered.error($0) }
        )
        let pageURL = try await pages.start(host: "127.0.0.1")
        defer { pages.stop() }
        let base = String(pageURL.prefix { $0 != "?" })
        XCTAssertTrue(pageURL.hasPrefix("http://127.0.0.1:"), pageURL)
        XCTAssertTrue(pageURL.hasSuffix("/?k=chiave"), pageURL)

        let (page, pageResponse) = try await get(pageURL)
        XCTAssertEqual(pageResponse.statusCode, 200)
        XCTAssertEqual(String(decoding: page, as: UTF8.self), "<html>pagina google</html>")
        XCTAssertTrue(pageResponse.value(forHTTPHeaderField: "Content-Security-Policy")?.contains("connect-src 'self'") ?? false)

        let (start, startResponse) = try await get(base + "start?k=chiave")
        XCTAssertEqual(startResponse.statusCode, 200)
        let returnURL = String(base.dropLast()) + "/google?k=chiave"
        let started = try JSONDecoder().decode([String: String].self, from: start)
        XCTAssertEqual(started["url"], "https://accounts.google.com/o/oauth2/v2/auth?state=" + GoogleAuth.encodeState(returnURL))

        let (_, wrongKey) = try await get(base + "?k=sbagliata")
        XCTAssertEqual(wrongKey.statusCode, 403)
        // URLSession non lascia cambiare l'intestazione Host: la richiesta si scrive a mano.
        let port = UInt16(pageURL.split(separator: ":")[2].prefix { $0.isNumber })!
        let wrongHost = try await RawHTTP.status(host: "127.0.0.1", port: port, request: "GET /?k=chiave HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
        XCTAssertEqual(wrongHost, 421)
        let (_, unknown) = try await get(base + "altro?k=chiave")
        XCTAssertEqual(unknown.statusCode, 404)

        let (_, back) = try await get(base + "google?k=chiave&code=4%2F0Acodice%20uno")
        XCTAssertEqual(back.statusCode, 303)
        XCTAssertEqual(back.value(forHTTPHeaderField: "Location"), "/?k=chiave#done")
        // Ricaricare la pagina di ritorno non consegna il codice due volte.
        _ = try await get(base + "google?k=chiave&code=4%2F0Acodice%20uno")
        XCTAssertEqual(delivered.codes, ["4/0Acodice uno"])
        XCTAssertTrue(delivered.errors.isEmpty)

        let (empty, _) = try await get(base + "status?k=chiave")
        XCTAssertEqual(String(decoding: empty, as: UTF8.self), "{}")
        pages.pickerURL = "https://photos.google.com/picker/s1"
        let (status, _) = try await get(base + "status?k=chiave")
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: status)["picker"], "https://photos.google.com/picker/s1")
    }

    func testARefusalOnThePhoneIsReportedAndNothingArrivesAfterStop() async throws {
        let delivered = Delivered()
        let pages = GoogleConsentPages(
            key: "chiave",
            page: { "<html></html>" },
            authorizationURL: { _ in "https://accounts.google.com/o/oauth2/v2/auth" },
            onCode: { delivered.code($0) },
            onError: { delivered.error($0) }
        )
        let pageURL = try await pages.start(host: "127.0.0.1")
        let base = String(pageURL.prefix { $0 != "?" })
        let (_, back) = try await get(base + "google?k=chiave&error=access_denied")
        XCTAssertEqual(back.statusCode, 303)
        XCTAssertEqual(back.value(forHTTPHeaderField: "Location"), "/?k=chiave#cancelled")
        XCTAssertEqual(delivered.errors, ["access_denied"])
        pages.stop()
        do {
            _ = try await get(base + "google?k=chiave&code=tardi")
        } catch {
            // Porta chiusa: va bene così.
        }
        XCTAssertTrue(delivered.codes.isEmpty)
    }
}
