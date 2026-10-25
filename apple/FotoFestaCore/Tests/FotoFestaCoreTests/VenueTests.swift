import XCTest
@testable import FotoFestaCore

final class VenueTests: XCTestCase {
    private func freshDefaults() -> UserDefaults {
        let name = "venue-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    func testKioskPinIsSavedAsSaltedHash() {
        let defaults = freshDefaults()
        let lock = KioskLock(defaults: defaults)
        XCTAssertFalse(lock.hasPin)
        XCTAssertFalse(lock.save("12a4"))
        XCTAssertFalse(lock.save("123"))
        XCTAssertTrue(lock.save("4321"))
        XCTAssertTrue(lock.hasPin)
        XCTAssertTrue(lock.matches("4321"))
        XCTAssertFalse(lock.matches("1234"))
        XCTAssertNotEqual(defaults.string(forKey: "kiosk.pin"), "4321")
        XCTAssertTrue(KioskLock(defaults: defaults).matches("4321"))
        lock.save(nil)
        XCTAssertFalse(lock.hasPin)
        XCTAssertFalse(lock.matches("4321"))
    }

    func testKioskLocksUntilUnlocked() {
        let lock = KioskLock(defaults: freshDefaults())
        let now = Date()
        XCTAssertFalse(lock.locked(enabled: true, now: now))
        lock.save("0000")
        XCTAssertFalse(lock.locked(enabled: false, now: now))
        XCTAssertTrue(lock.locked(enabled: true, now: now))
        lock.unlock(now: now)
        XCTAssertFalse(lock.locked(enabled: true, now: now.addingTimeInterval(60)))
        XCTAssertTrue(lock.locked(enabled: true, now: now.addingTimeInterval(KioskLock.unlockTime + 1)))
    }

    func testVenueSettingsDecodeAndRejectUnknownOptions() throws {
        let json = #"{"frameStyle":"band","sponsorEvery":10,"screenMargin":3,"kiosk":true,"nightlyReset":4,"alwaysParty":true}"#
        let settings = try JSONDecoder().decode(SlideshowSettings.self, from: Data(json.utf8))
        XCTAssertEqual(settings.frameStyle, .band)
        XCTAssertEqual(settings.sponsorEvery, 10)
        XCTAssertEqual(settings.screenMargin, 0)
        XCTAssertTrue(settings.kiosk)
        XCTAssertEqual(settings.nightlyReset, 4)
        XCTAssertTrue(settings.alwaysParty)
        let old = try JSONDecoder().decode(SlideshowSettings.self, from: Data(#"{"intervalSeconds":5}"#.utf8))
        XCTAssertEqual(old.frameStyle, .none)
        XCTAssertEqual(old.nightlyReset, -1)
        XCTAssertTrue(old.livePhotos)
    }
}

final class VenueConfigTests: XCTestCase {
    func testConfigRoundTrip() throws {
        var settings = SlideshowSettings()
        settings.frameStyle = .border
        settings.sponsorEvery = 5
        settings.intervalSeconds = 12
        let setup = Data(#"{"version":1,"name":"Bar Centrale"}"#.utf8)
        let wifi = WifiNetwork(ssid: "Festa", password: "password123")
        let data = try XCTUnwrap(VenueConfig.make(settings: settings, setup: setup, wifi: wifi))
        let contents = try XCTUnwrap(VenueConfig.parse(data))
        XCTAssertEqual(contents.settings, settings)
        XCTAssertEqual(contents.wifi, wifi)
        let parsedSetup = try XCTUnwrap(contents.setup)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: parsedSetup) as? [String: Any])
        XCTAssertEqual(object["name"] as? String, "Bar Centrale")
    }

    func testConfigRejectsOtherFiles() {
        XCTAssertNil(VenueConfig.parse(Data("{}".utf8)))
        XCTAssertNil(VenueConfig.parse(Data(#"{"app":"FotoFesta","version":2,"settings":{}}"#.utf8)))
        XCTAssertNil(VenueConfig.parse(Data("not json".utf8)))
        let noWifi = VenueConfig.parse(Data(#"{"app":"FotoFesta","version":1,"settings":{},"wifi":{"ssid":"","password":"x"}}"#.utf8))
        XCTAssertNotNil(noWifi)
        XCTAssertNil(noWifi?.wifi)
    }
}
