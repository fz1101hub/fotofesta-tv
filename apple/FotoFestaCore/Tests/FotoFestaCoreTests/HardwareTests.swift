import XCTest
@testable import FotoFestaCore

final class EscPosTests: XCTestCase {
    func testWhiteStaysWhiteBlackStaysBlackGreyBecomesDots() throws {
        XCTAssertFalse(EscPos.dither(try XCTUnwrap(EscPos.Gray(width: 8, height: 2, pixels: [UInt8](repeating: 255, count: 16)))).contains(true))
        XCTAssertFalse(EscPos.dither(try XCTUnwrap(EscPos.Gray(width: 8, height: 2, pixels: [UInt8](repeating: 0, count: 16)))).contains(false))
        let grey = EscPos.dither(try XCTUnwrap(EscPos.Gray(width: 16, height: 16, pixels: [UInt8](repeating: 128, count: 256)))).filter { $0 }.count
        XCTAssertTrue((96...160).contains(grey))
        XCTAssertNil(EscPos.Gray(width: 2, height: 2, pixels: [0]))
    }

    func testRasterPacksEightDotsPerByteWithTheSizeInFront() {
        let black = (0..<20).map { $0 == 0 || $0 == 9 || $0 == 10 }
        let bytes = [UInt8](EscPos.raster(black, width: 10, height: 2))
        XCTAssertEqual(Array(bytes[0..<8]), [0x1D, 0x76, 0x30, 0x00, 2, 0, 2, 0])
        XCTAssertEqual(Array(bytes[8...11]), [0x80, 0x40, 0x80, 0x00])
    }

    func testTextLosesAccents() {
        XCTAssertEqual(String(decoding: EscPos.text("Perché città è caffè€"), as: UTF8.self), "Perche citta e caffe?")
    }

    func testReceiptStartsWithInitAndEndsWithTheCut() throws {
        let bytes = [UInt8](EscPos.receipt(title: "FotoFesta", photo: EscPos.Gray(width: 8, height: 1, pixels: [UInt8](repeating: 0, count: 8)), lines: ["Anna"], footer: "21:30"))
        XCTAssertEqual(Array(bytes.prefix(2)), [0x1B, 0x40])
        XCTAssertEqual(Array(bytes.suffix(4)), [0x1D, 0x56, 0x42, 0x00])
    }
}

final class IppTests: XCTestCase {
    private let printer = Ipp.Printer(host: "192.168.1.40", path: "/ipp/print")

    func testPrintJobCarriesTheOperationAttributesAndThePhoto() throws {
        let jpeg = Data([0xFF, 0xD8, 1, 2, 3])
        let bytes = Ipp.printJobRequest(printer, requestID: 7, jobName: "Festa", format: "image/jpeg", document: jpeg, copies: 2)
        XCTAssertEqual(Array(bytes.prefix(8)), [2, 0, 0, 2, 0, 0, 0, 7])
        XCTAssertEqual(bytes.suffix(jpeg.count), jpeg)
        let parsed = try XCTUnwrap(Ipp.parse(bytes.prefix(bytes.count - jpeg.count)))
        XCTAssertEqual(parsed.strings("printer-uri"), ["ipp://192.168.1.40:631/ipp/print"])
        XCTAssertEqual(parsed.strings("document-format"), ["image/jpeg"])
        XCTAssertEqual(parsed.attributes.first { $0.name == "copies" }?.values, [.integer(2)])
        XCTAssertEqual(printer.httpURL, "http://192.168.1.40:631/ipp/print")
    }

    func testMultipleValuesOfAnAttributeAreReadTogether() throws {
        func attribute(_ tag: UInt8, _ name: String, _ value: String) -> [UInt8] {
            [tag, 0, UInt8(name.utf8.count)] + Array(name.utf8) + [0, UInt8(value.utf8.count)] + Array(value.utf8)
        }
        let response: [UInt8] = [2, 0, 0, 0, 0, 0, 0, 1, 0x04]
            + attribute(0x49, "document-format-supported", "application/pdf")
            + attribute(0x49, "", "image/jpeg") + [0x03]
        let parsed = try XCTUnwrap(Ipp.parse(Data(response)))
        XCTAssertTrue(parsed.ok)
        XCTAssertEqual(parsed.strings("document-format-supported"), ["application/pdf", "image/jpeg"])
        XCTAssertEqual(Ipp.chooseFormat(parsed.strings("document-format-supported")), "image/jpeg")
        XCTAssertNil(Ipp.chooseFormat(["application/pdf"]))
    }
}

final class PartyLightsTests: XCTestCase {
    private let hue = PartyLights.Device(kind: .hue, host: "192.168.1.20", port: 80, token: "abc123")
    private let wled = PartyLights.Device(kind: .wled, host: "192.168.1.21", port: 80)
    private let nanoleaf = PartyLights.Device(kind: .nanoleaf, host: "192.168.1.22", port: 16021, token: "tok")

    private func json(_ call: PartyLights.Call?) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(try XCTUnwrap(call).body.utf8)) as? [String: Any])
    }

    func testPairingReadsTheKeyOnlyWhenTheButtonWasPressed() {
        XCTAssertEqual(PartyLights.token(kind: .hue, response: Data(#"[{"success":{"username":"abc-1"}}]"#.utf8)), "abc-1")
        XCTAssertNil(PartyLights.token(kind: .hue, response: Data(#"[{"error":{"type":101,"description":"link button not pressed"}}]"#.utf8)))
        XCTAssertEqual(PartyLights.token(kind: .nanoleaf, response: Data(#"{"auth_token":"xyz"}"#.utf8)), "xyz")
        XCTAssertNil(PartyLights.token(kind: .nanoleaf, response: Data(#"{"auth_token":"../x"}"#.utf8)))
        XCTAssertNil(PartyLights.pairCall(wled))
    }

    func testColourCallsUseTheLocalAPIOfEachLight() throws {
        let hueCall = try XCTUnwrap(PartyLights.colorCall(hue, rgb: 0xFF0000))
        XCTAssertEqual(hueCall.method, "PUT")
        XCTAssertEqual(hueCall.url, "http://192.168.1.20:80/api/abc123/groups/0/action")
        let xy = try XCTUnwrap(try json(hueCall)["xy"] as? [Double])
        XCTAssertEqual(xy[0], 0.7006, accuracy: 0.001)
        XCTAssertEqual(xy[1], 0.2993, accuracy: 0.001)

        let wledCall = PartyLights.colorCall(wled, rgb: 0x10FF20, brightness: 0.5)
        XCTAssertEqual(wledCall?.url, "http://192.168.1.21:80/json/state")
        let wledBody = try json(wledCall)
        let segment = try XCTUnwrap((wledBody["seg"] as? [[String: Any]])?.first)
        XCTAssertEqual((segment["col"] as? [[Int]])?.first, [16, 255, 32])
        XCTAssertEqual(wledBody["bri"] as? Int, 128)

        let leafCall = PartyLights.colorCall(nanoleaf, rgb: 0x0000FF)
        XCTAssertEqual(leafCall?.url, "http://192.168.1.22:16021/api/v1/tok/state")
        XCTAssertEqual((try json(leafCall)["hue"] as? [String: Any])?["value"] as? Int, 240)
        var noToken = hue
        noToken.token = nil
        XCTAssertNil(PartyLights.colorCall(noToken, rgb: 0xFF0000))
    }

    func testPhotoColoursBecomeLivelyAndGreysAWarmWhite() {
        XCTAssertEqual(PartyLights.partyColor(0x808080), 0xFFC98A)
        let (_, saturation, value) = PartyLights.hsv(PartyLights.partyColor(0x806050))
        XCTAssertGreaterThan(saturation, 0.5)
        XCTAssertGreaterThanOrEqual(value, 0.85)
        XCTAssertEqual(PartyLights.fromHSV(hue: 120, saturation: 1, value: 1), 0x00FF00)
    }

    func testDevicesSurviveARoundTripToJSON() throws {
        let data = try JSONEncoder().encode([hue, wled])
        XCTAssertEqual(try JSONDecoder().decode([PartyLights.Device].self, from: data), [hue, wled])
        XCTAssertThrowsError(try JSONDecoder().decode(PartyLights.Device.self, from: Data(#"{"kind":"LAVA","host":"x","port":1,"name":"x"}"#.utf8)))
    }
}
