import FotoFestaCore
import XCTest

final class MosaicTests: XCTestCase {
    private let photos = (0..<5).map { Photo(id: "p\($0)", title: "Foto \($0)", source: .bundled(fileName: "p\($0).jpg")) }

    private func ids(_ list: [Photo]) -> [String] { list.map(\.id) }

    func testRecentlyShownPhotosComeFirst() {
        XCTAssertEqual(ids(Mosaic.side(of: photos, currentIndex: 2, recentIDs: ["p1", "p0", "p4"])), ["p1", "p0"])
    }

    func testFollowingPhotosFillTheGaps() {
        XCTAssertEqual(ids(Mosaic.side(of: photos, currentIndex: 2, recentIDs: [])), ["p3", "p4"])
        XCTAssertEqual(ids(Mosaic.side(of: photos, currentIndex: 4, recentIDs: ["p1"])), ["p1", "p0"])
    }

    func testTheLargePhotoAndMissingPhotosAreSkipped() {
        XCTAssertEqual(ids(Mosaic.side(of: photos, currentIndex: 0, recentIDs: ["p0", "gone", "p3", "p3", "p1"])), ["p3", "p1"])
    }

    func testSmallCollectionsShowFewerSidePhotos() {
        XCTAssertEqual(ids(Mosaic.side(of: Array(photos.prefix(2)), currentIndex: 0, recentIDs: [])), ["p1"])
        XCTAssertEqual(ids(Mosaic.side(of: Array(photos.prefix(1)), currentIndex: 0, recentIDs: ["p0"])), [])
        XCTAssertEqual(ids(Mosaic.side(of: [], currentIndex: 0, recentIDs: [])), [])
    }

    func testLayoutIsDecodedAndDefaultsToSingle() throws {
        XCTAssertEqual(SlideshowSettings().layout, .single)
        let old = try JSONDecoder().decode(SlideshowSettings.self, from: Data(#"{"fitMode":"crop"}"#.utf8))
        XCTAssertEqual(old.layout, .single)
        var settings = SlideshowSettings()
        settings.layout = .mosaic
        let decoded = try JSONDecoder().decode(SlideshowSettings.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(decoded.layout, .mosaic)
    }
}
