import XCTest
@testable import FotoFestaCore

final class PartyGamesTests: XCTestCase {
    func testWhoIsMissingIgnoresCaseAccentsAndSurnames() {
        let roll = PartyGames.roll(expected: ["Niccolò", "Marco Rossi", "Anna", "Sara"], seen: ["niccolo", "Marco", "ANNA!"])
        XCTAssertEqual(roll.present, ["Niccolò", "Marco Rossi", "Anna"])
        XCTAssertEqual(roll.missing, ["Sara"])
    }

    func testQuizLinesNeedAQuestionAndTwoAnswers() {
        let quiz = PartyGames.parseQuiz("Dove si sono conosciuti? | A Roma | A Milano | A Napoli\nsenza risposte\nColore preferito? | Blu | Rosso")
        XCTAssertEqual(quiz.count, 2)
        XCTAssertEqual(quiz[0].right, "A Roma")
        XCTAssertEqual(quiz[1].wrong, ["Rosso"])
        var generator = SeededGenerator(seed: 3)
        let shuffled = PartyGames.shuffle(quiz[0], using: &generator)
        XCTAssertEqual(shuffled.choices[shuffled.right], "A Roma")
        XCTAssertEqual(shuffled.choices.count, 3)
    }

    func testNameChoicesIncludeTheRightOneAndThreeOthers() {
        var generator = SeededGenerator(seed: 1)
        let result = PartyGames.nameChoices(correct: "Giulia", pool: ["Anna", "giulia", "Luca", "Marco", "Sara"], using: &generator)
        XCTAssertEqual(result.choices.count, 4)
        XCTAssertEqual(result.choices[result.right], "Giulia")
        XCTAssertEqual(result.choices.filter { $0.lowercased() == "giulia" }.count, 1)
    }

    func testSongTitlesComeFromFileNames() {
        XCTAssertEqual(PartyGames.songTitle("01 - Gloria (Remastered 2011).mp3"), "Gloria")
        XCTAssertEqual(PartyGames.songTitle("/musica/Volare.flac"), "Volare")
        XCTAssertEqual(PartyGames.songTitle("Hey_Jude.m4a"), "Hey Jude")
    }

    func testBrighterPhotosPlayHigherNotes() {
        let notes = PartyGames.notes([0x101010, 0xF0F0F0])
        XCTAssertGreaterThan(notes[1].frequency, notes[0].frequency)
        XCTAssertEqual(notes[0].frequency, 220, accuracy: 0.01)
    }

    func testDifferencesAreSpreadOutAndRepeatable() {
        let first = PartyGames.differences(seed: 42)
        XCTAssertEqual(first.count, 5)
        XCTAssertEqual(first, PartyGames.differences(seed: 42))
        for (i, a) in first.enumerated() {
            for (j, b) in first.enumerated() where i != j {
                XCTAssertGreaterThanOrEqual((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y), 0.04)
            }
        }
    }

    func testArtworkCoversTheWholeCanvas() {
        let blocks = PartyGames.artwork([1, 2, 3], seed: 7)
        let area = blocks.reduce(0) { $0 + ($1.right - $1.left) * ($1.bottom - $1.top) }
        XCTAssertEqual(area, 1, accuracy: 0.0001)
    }

    func testOnThisDay() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome")!
        let now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 12))!
        let past = calendar.date(from: DateComponents(year: 2019, month: 10, day: 25, hour: 20))!
        let other = calendar.date(from: DateComponents(year: 2019, month: 10, day: 24, hour: 20))!
        XCTAssertTrue(PartyGames.sameDayPastYear(past, now: now, calendar: calendar))
        XCTAssertFalse(PartyGames.sameDayPastYear(other, now: now, calendar: calendar))
        XCTAssertFalse(PartyGames.sameDayPastYear(now, now: now, calendar: calendar))
        XCTAssertEqual(PartyGames.yearsAgo(past, now: now, calendar: calendar), 7)
    }
}
