import FotoFestaCore
import XCTest

final class PartyAwardsTests: XCTestCase {
    private func guest(_ id: String, _ sender: String?, _ seconds: TimeInterval) -> Photo {
        Photo(id: "guest:\(id)", title: "Foto", source: .guest(id: id), receivedAt: Date(timeIntervalSince1970: seconds), sender: sender)
    }

    func testNoGuestPhotosMeansNoAwards() {
        let awards = PartyAwards([Photo(id: "demo:1", title: "Picnic", source: .bundled(fileName: "demo_picnic.jpg"))])
        XCTAssertEqual(awards.photos, 0)
        XCTAssertEqual(awards.guests, 0)
        XCTAssertNil(awards.topPhotographer)
        XCTAssertNil(awards.firstPhoto)
        XCTAssertNil(awards.lastPhoto)
    }

    func testGoldenLensGoesToWhoeverSentTheMostPhotos() {
        let awards = PartyAwards([
            guest("1", "Giulia", 100), guest("2", "Marco", 200), guest("3", "marco ", 300),
            guest("4", nil, 400), guest("5", "Marco", 500), guest("6", "Giulia", 600),
        ])
        XCTAssertEqual(awards.photos, 6)
        XCTAssertEqual(awards.guests, 2)
        XCTAssertEqual(awards.topPhotographer?.name, "Marco")
        XCTAssertEqual(awards.topPhotographer?.count, 3)
        XCTAssertEqual(awards.topPhotographer?.photo.id, "guest:5")
    }

    func testOnATieTheGuestWhoStartedFirstWins() {
        let awards = PartyAwards([guest("1", "Sara", 300), guest("2", "Luca", 100), guest("3", "Sara", 400), guest("4", "Luca", 500)])
        XCTAssertEqual(awards.topPhotographer?.name, "Luca")
    }

    func testFirstAndLastPhotoFollowArrivalTime() {
        let awards = PartyAwards([guest("b", "Marco", 200), guest("a", nil, 100), guest("c", "Sara", 300)])
        XCTAssertEqual(awards.firstPhoto?.id, "guest:a")
        XCTAssertEqual(awards.lastPhoto?.id, "guest:c")
    }

    func testASinglePhotoIsFirstButNotAlsoLast() {
        let awards = PartyAwards([guest("1", "Giulia", 100)])
        XCTAssertEqual(awards.firstPhoto?.id, "guest:1")
        XCTAssertNil(awards.lastPhoto)
        XCTAssertEqual(awards.topPhotographer?.count, 1)
    }
}
