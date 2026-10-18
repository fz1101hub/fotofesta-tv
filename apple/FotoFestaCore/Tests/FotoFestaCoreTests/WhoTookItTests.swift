import FotoFestaCore
import XCTest

/// Generatore ripetibile per i test (SplitMix64).
private struct Seeded: RandomNumberGenerator {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }
}

final class WhoTookItTests: XCTestCase {
    private func guest(_ id: String, _ sender: String?) -> Photo {
        Photo(id: "guest:\(id)", title: "Foto", source: .guest(id: id), sender: sender)
    }

    private let demo = Photo(id: "demo:1", title: "Picnic", source: .bundled(fileName: "demo_picnic.jpg"))

    func testNeedsPhotosFromAtLeastTwoNamedGuests() {
        XCTAssertFalse(WhoTookIt.canPlay([demo, guest("1", "Giulia"), guest("2", "Giulia"), guest("3", nil), guest("4", "  ")]))
        XCTAssertFalse(WhoTookIt.canPlay([guest("1", "Marco"), guest("2", "marco ")]))
        XCTAssertTrue(WhoTookIt.canPlay([guest("1", "Giulia"), guest("2", "Marco")]))
        XCTAssertNil(WhoTookIt.round([guest("1", "Giulia")], previousPhotoID: nil))
    }

    func testRoundAsksAboutANamedGuestPhoto() {
        let photos = [demo, guest("1", "Giulia"), guest("2", "Marco"), guest("3", "Sara"), guest("4", nil)]
        for seed in 0..<100 {
            var generator = Seeded(state: UInt64(seed))
            let round = WhoTookIt.round(photos, previousPhotoID: nil, using: &generator)!
            XCTAssertEqual(round.photo.sender, round.answer)
            XCTAssertTrue(round.choices.contains(round.answer))
            XCTAssertEqual(Set(round.choices).count, round.choices.count)
            XCTAssertEqual(round.choices.count, 3)
            XCTAssertTrue(WhoTookIt.isAnswer(round, round.answer))
        }
    }

    func testChoicesAreAtMostFourNames() {
        let photos = (1...8).map { guest("\($0)", "Invitato \($0)") }
        for seed in 0..<50 {
            var generator = Seeded(state: UInt64(seed))
            let round = WhoTookIt.round(photos, previousPhotoID: nil, using: &generator)!
            XCTAssertEqual(round.choices.count, WhoTookIt.maxChoices)
            XCTAssertTrue(round.choices.contains(round.answer))
        }
    }

    func testTheSamePhotoIsNotAskedTwiceInARow() {
        let photos = [guest("1", "Giulia"), guest("2", "Marco")]
        var previous: String?
        for seed in 0..<50 {
            var generator = Seeded(state: UInt64(seed))
            let round = WhoTookIt.round(photos, previousPhotoID: previous, using: &generator)!
            XCTAssertNotEqual(round.photo.id, previous)
            previous = round.photo.id
        }
    }

    func testNamesDifferingOnlyInCaseAreTheSameGuest() {
        let photos = [guest("1", "Giulia"), guest("2", "giulia"), guest("3", "Marco")]
        XCTAssertEqual(WhoTookIt.senders(photos), ["Giulia", "Marco"])
        for seed in 0..<30 {
            var generator = Seeded(state: UInt64(seed))
            let round = WhoTookIt.round(photos, previousPhotoID: nil, using: &generator)!
            XCTAssertEqual(round.choices.count, 2)
            if round.photo.id == "guest:2" { XCTAssertEqual(round.answer, "Giulia") }
            XCTAssertTrue(WhoTookIt.isAnswer(round, round.answer.uppercased()))
        }
    }
}
