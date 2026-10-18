import FotoFestaCore
import XCTest

final class CountdownTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    /// Le [hours]:[minutes]:[seconds] UTC del 1° gennaio 2027.
    private func at(_ hours: Int, _ minutes: Int = 0, _ seconds: Double = 0) -> Date {
        Date(timeIntervalSince1970: 1_798_761_600 + Double(hours * 3_600 + minutes * 60) + seconds)
    }

    func testMoreThanAnHourBeforeNothingIsShown() {
        XCTAssertEqual(Countdown.phase(at: at(22, 59), minutesOfDay: 0, timeZone: utc), .hidden)
    }

    func testInTheLastHourTheRemainingTimeIsShown() {
        XCTAssertEqual(Countdown.phase(at: at(23, 0), minutesOfDay: 0, timeZone: utc), .showing(3_600))
        XCTAssertEqual(Countdown.phase(at: at(23, 47, 26), minutesOfDay: 0, timeZone: utc), .showing(754))
        XCTAssertEqual(Countdown.phase(at: at(23, 59, 49), minutesOfDay: 0, timeZone: utc), .showing(11))
    }

    func testTheLastTenSecondsAreBigNumbers() {
        XCTAssertEqual(Countdown.phase(at: at(23, 59, 50), minutesOfDay: 0, timeZone: utc), .final(10))
        XCTAssertEqual(Countdown.phase(at: at(23, 59, 59), minutesOfDay: 0, timeZone: utc), .final(1))
        XCTAssertEqual(Countdown.phase(at: at(23, 59, 59.5), minutesOfDay: 0, timeZone: utc), .final(1))
    }

    func testTheMomentIsCelebratedForTwentySeconds() {
        XCTAssertEqual(Countdown.phase(at: at(0), minutesOfDay: 0, timeZone: utc), .celebrate)
        XCTAssertEqual(Countdown.phase(at: at(0, 0, 19), minutesOfDay: 0, timeZone: utc), .celebrate)
        XCTAssertEqual(Countdown.phase(at: at(0, 0, 20), minutesOfDay: 0, timeZone: utc), .hidden)
    }

    func testLocalTimeFollowsTheTimeZone() {
        // Torta alle 21:30 a Roma d'inverno (UTC+1): le 20:15 UTC sono le 21:15 locali.
        let rome = TimeZone(identifier: "Europe/Rome")!
        XCTAssertEqual(Countdown.phase(at: at(20, 15), minutesOfDay: 21 * 60 + 30, timeZone: rome), .showing(900))
    }

    func testRemainingTimeIsFormatted() {
        XCTAssertEqual(Countdown.format(754), "12:34")
        XCTAssertEqual(Countdown.format(9), "0:09")
        XCTAssertEqual(Countdown.format(3_600), "1:00:00")
    }

    func testSettingsKeepTheCountdownAndToleratesOldData() throws {
        XCTAssertFalse(SlideshowSettings().countdown)
        var settings = SlideshowSettings()
        settings.countdown = true
        settings.countdownMinutes = 21 * 60 + 30
        settings.countdownEvent = .cake
        let decoded = try JSONDecoder().decode(SlideshowSettings.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(decoded, settings)
        let odd = try JSONDecoder().decode(SlideshowSettings.self, from: Data(#"{"countdownMinutes":7}"#.utf8))
        XCTAssertEqual(odd.countdownMinutes, 0)
    }
}
