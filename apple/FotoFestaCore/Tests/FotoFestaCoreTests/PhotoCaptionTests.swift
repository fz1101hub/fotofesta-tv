import FotoFestaCore
import XCTest

final class PhotoCaptionTests: XCTestCase {
    func testCameraAndPhoneNamesAreRecognised() {
        for name in ["IMG_20240512_153422", "PXL_20240512_123456789", "DSC01234", "DSCF0042", "IMG-20240101-WA0001",
                     "WhatsApp Image 2024-01-01 at 10.10.10", "Screenshot_20240101-101010", "20240512_153422", "GOPR0123", ""] {
            XCTAssertTrue(PhotoCaption.looksLikeCameraName(name), name)
        }
    }

    func testMeaningfulNamesAreKept() {
        for name in ["Festa di compleanno", "Estate 2024", "Foto 2024", "Torta della nonna", "Coast at sunset", "Image of the bay"] {
            XCTAssertFalse(PhotoCaption.looksLikeCameraName(name), name)
            XCTAssertEqual(PhotoCaption.title(name), name)
        }
        XCTAssertNil(PhotoCaption.title("IMG_20240512_153422"))
    }

    func testBlurIsTheDefaultFit() throws {
        XCTAssertEqual(SlideshowSettings().fitMode, .blur)
        let old = try JSONDecoder().decode(SlideshowSettings.self, from: Data(#"{"fitMode":"contain"}"#.utf8))
        XCTAssertEqual(old.fitMode, .contain)
    }
}
