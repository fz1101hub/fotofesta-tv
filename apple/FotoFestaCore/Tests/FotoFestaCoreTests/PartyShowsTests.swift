import FotoFestaCore
import XCTest

final class PartyShowsTests: XCTestCase {
    private var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    private func guest(_ id: String, _ sender: String?, hour: Int) -> Photo {
        Photo(id: id, title: id, source: .guest(id: id), receivedAt: Date(timeIntervalSince1970: TimeInterval(hour * 3600)), sender: sender)
    }

    func testThePartyInNumbers() {
        let photos = [guest("a", "Giulia", hour: 21), guest("b", "giulia ", hour: 21), guest("c", "Marco", hour: 22), guest("d", nil, hour: 22), guest("e", "Sara", hour: 22),
                      Photo(id: "x", title: "x", source: .bundled(fileName: "demo_coast.jpg"))]
        let stats = PartyShows.stats(photos: photos, messages: 4, reactionsByPhoto: ["c": 5, "a": 2, "altrove": 9], reactionsByKind: [1, 0, 6, 0, 0, 0], coins: 12, calendar: utc)
        XCTAssertEqual(stats.photos, 6)
        XCTAssertEqual(stats.guestPhotos, 5)
        // "Giulia" e "giulia " sono la stessa persona; chi non scrive il nome non conta.
        XCTAssertEqual(stats.photographers, 3)
        XCTAssertEqual(stats.busiestHour, 22)
        XCTAssertEqual(stats.hourly[21], 2)
        XCTAssertEqual(stats.reactions, 7)
        XCTAssertEqual(stats.topReaction, 2)
        // Una foto non più in raccolta non può essere la più amata.
        XCTAssertEqual(stats.favorite, "c")
    }

    func testNumbersWithoutPhotosOrReactions() {
        let stats = PartyShows.stats(photos: [], messages: 0, reactionsByPhoto: [:], reactionsByKind: [0, 0, 0, 0, 0, 0], coins: 0, calendar: utc)
        XCTAssertNil(stats.busiestHour)
        XCTAssertNil(stats.topReaction)
        XCTAssertNil(stats.favorite)
    }

    func testTheMosaicPicksTheClosestColourButNotAlwaysTheSamePhoto() {
        let red: UInt32 = 0xFF0000, darkRed: UInt32 = 0xCC0000, blue: UInt32 = 0x0000FF
        XCTAssertEqual(PartyShows.mosaic(cells: [red, blue], tiles: [red, blue]), [0, 1])
        let choice = PartyShows.mosaic(cells: Array(repeating: red, count: 40), tiles: [red, darkRed, blue])
        XCTAssertGreaterThan(choice.filter { $0 == 0 }.count, choice.filter { $0 == 1 }.count)
        XCTAssertTrue(choice.contains(1))
        XCTAssertTrue(PartyShows.mosaic(cells: [red], tiles: []).isEmpty)
    }

    func testAverageColourOfPixels() {
        XCTAssertEqual(PartyShows.average(rgba: [255, 0, 0, 255, 0, 0, 255, 255]), 0x7F007F)
        XCTAssertEqual(PartyShows.average(rgba: []), 0)
    }

    func testCreditsListPhotographersByNumberOfPhotosAndNobodyTwice() {
        let sections = PartyShows.credits(
            photoSenders: ["Marco", "Giulia", "giulia", nil, "Giulia", "Marco ", "Sara"],
            messageNames: ["Anna", nil, "anna", "Luca"],
            champions: ["Luca"],
            participants: ["Giulia", "Paolo", "Anna", "Elena"]
        )
        XCTAssertEqual(sections.first { $0.kind == .photographers }?.names, ["Giulia", "Marco", "Sara"])
        XCTAssertEqual(sections.first { $0.kind == .messages }?.names, ["Anna", "Luca"])
        XCTAssertEqual(sections.first { $0.kind == .guests }?.names, ["Paolo", "Elena"])
        XCTAssertTrue(PartyShows.credits(photoSenders: [], messageNames: [], champions: [], participants: []).isEmpty)
    }

    func testPartyTemplatesChangeOnlyTheirSettings() throws {
        var start = SlideshowSettings()
        start.intervalSeconds = 12
        start.shuffle = true
        let newYear = PartyTemplate.newYear.apply(to: start)
        XCTAssertEqual(newYear.template, .newYear)
        XCTAssertTrue(newYear.countdown)
        XCTAssertEqual(newYear.countdownEvent, .midnight)
        XCTAssertEqual(newYear.intervalSeconds, 12)
        XCTAssertTrue(newYear.shuffle)
        XCTAssertEqual(PartyTemplate.birthday.apply(to: start).layout, .stack)
        // Le scelte nuove si salvano e si rileggono.
        let decoded = try JSONDecoder().decode(SlideshowSettings.self, from: JSONEncoder().encode(newYear))
        XCTAssertEqual(decoded, newYear)
    }
}
