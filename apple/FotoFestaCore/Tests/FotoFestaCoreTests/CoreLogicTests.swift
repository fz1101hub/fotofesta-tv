import XCTest
@testable import FotoFestaCore

final class PlaybackOrderTests: XCTestCase {
    func testSequentialOrderWrapsAround() {
        let order = PlaybackOrder()
        order.reset(count: 3, shuffle: false)
        XCTAssertEqual(order.next(after: 0), 1)
        XCTAssertEqual(order.next(after: 2), 0)
        XCTAssertEqual(order.previous(before: 0), 2)
    }

    func testShuffleVisitsEveryPhotoOncePerCycle() {
        let order = PlaybackOrder()
        order.reset(count: 10, shuffle: true, current: 7)
        var seen = [7]
        var current = 7
        for _ in 0..<9 {
            current = order.next(after: current)
            seen.append(current)
        }
        XCTAssertEqual(Set(seen), Set(0..<10))
        XCTAssertEqual(seen.count, 10)
    }

    func testNewCycleNeverRepeatsLastPhoto() {
        for _ in 0..<50 {
            let order = PlaybackOrder()
            order.reset(count: 3, shuffle: true, current: 0)
            var current = 0
            for _ in 0..<30 {
                let next = order.next(after: current)
                XCTAssertNotEqual(next, current)
                current = next
            }
        }
    }

    func testPeekHasNoSideEffects() {
        let order = PlaybackOrder()
        order.reset(count: 6, shuffle: true, current: 1)
        XCTAssertNil(order.peekNext(after: 42))
        let expected = order.peekNext(after: 1)
        XCTAssertEqual(order.next(after: 1), expected)
    }

    func testSinglePhotoAndEmptyAreStable() {
        let single = PlaybackOrder()
        single.reset(count: 1, shuffle: true)
        XCTAssertEqual(single.next(after: 0), 0)
        XCTAssertNil(single.peekNext(after: 0))
        let empty = PlaybackOrder()
        empty.reset(count: 0, shuffle: false)
        XCTAssertEqual(empty.next(after: 0), 0)
    }
}

final class KenBurnsMotionTests: XCTestCase {
    func testDeterministicPerPhoto() {
        XCTAssertEqual(KenBurnsMotion.forPhoto(id: "google:abc"), KenBurnsMotion.forPhoto(id: "google:abc"))
    }

    func testPhotoEdgesNeverEnterTheFrame() {
        for index in 0..<500 {
            let motion = KenBurnsMotion.forPhoto(id: "photo-\(index)")
            for step in 0...20 {
                let progress = Double(step) / 20
                let scale = motion.scale(at: progress)
                let margin = (scale - 1) / 2
                XCTAssert((1...KenBurnsMotion.maxZoom).contains(scale))
                XCTAssertLessThanOrEqual(abs(motion.offsetX(at: progress)), margin + 1e-9)
                XCTAssertLessThanOrEqual(abs(motion.offsetY(at: progress)), margin + 1e-9)
            }
        }
    }

    func testBothZoomDirectionsAreUsed() {
        let motions = (0..<50).map { KenBurnsMotion.forPhoto(id: "p\($0)") }
        XCTAssert(motions.contains { $0.endScale > $0.startScale })
        XCTAssert(motions.contains { $0.endScale < $0.startScale })
    }
}

final class SlideshowSettingsTests: XCTestCase {
    func testMissingAndUnknownValuesKeepDefaults() throws {
        let json = #"{"intervalSeconds":999,"transition":"spin","shuffle":true,"sleepTimerMinutes":-5}"#
        let settings = try JSONDecoder().decode(SlideshowSettings.self, from: Data(json.utf8))
        XCTAssertEqual(settings.intervalSeconds, 120)
        XCTAssertEqual(settings.transition, .fade)
        XCTAssertTrue(settings.shuffle)
        XCTAssertEqual(settings.sleepTimerMinutes, 0)
        XCTAssertTrue(settings.showClock)
    }

    func testRoundTrip() throws {
        var settings = SlideshowSettings()
        settings.kenBurns = true
        settings.fitMode = .crop
        let decoded = try JSONDecoder().decode(SlideshowSettings.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(decoded, settings)
    }

    func testReducedMotionDisablesKenBurns() {
        var settings = SlideshowSettings()
        settings.kenBurns = true
        settings.reducedMotion = true
        XCTAssertFalse(settings.isKenBurnsActive)
    }

    func testProtoDurations() {
        XCTAssertEqual(parseProtoDuration("5s"), 5)
        XCTAssertEqual(parseProtoDuration("1.5s"), 1.5)
        XCTAssertNil(parseProtoDuration(""))
        XCTAssertNil(parseProtoDuration("-3s"))
        XCTAssertNil(parseProtoDuration(nil))
    }

    func testGoogleErrorBodies() {
        let oauth = HTTPStatusError.from(code: 400, body: Data(#"{"error":"invalid_grant","error_description":"Token revoked"}"#.utf8))
        XCTAssertEqual(oauth.oauthError, "invalid_grant")
        XCTAssertEqual(oauth.serverMessage, "Token revoked")
        let api = HTTPStatusError.from(code: 401, body: Data(#"{"error":{"message":"Invalid credentials"}}"#.utf8))
        XCTAssertNil(api.oauthError)
        XCTAssertEqual(api.serverMessage, "Invalid credentials")
        XCTAssertNil(HTTPStatusError.from(code: 502, body: Data("<html>".utf8)).serverMessage)
    }
}

final class PhotoSourceTests: XCTestCase {
    /// Le foto della libreria Foto si salvano e si rileggono come le altre (ultima raccolta, selezioni).
    func testLibraryPhotosSurviveEncoding() throws {
        let photos = [
            Photo(id: "library:9F1C-22/L0/001", title: "", source: .library(localIdentifier: "9F1C-22/L0/001"), isVideo: true),
            Photo(id: "demo:coast", title: "Costa", source: .bundled(fileName: "demo_coast.jpg")),
        ]
        let decoded = try JSONDecoder().decode([Photo].self, from: JSONEncoder().encode(photos))
        XCTAssertEqual(decoded, photos)
    }
}
