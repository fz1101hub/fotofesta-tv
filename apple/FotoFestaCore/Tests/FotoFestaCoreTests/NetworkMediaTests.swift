import Foundation
import XCTest
@testable import FotoFestaCore

final class NetworkMediaTests: XCTestCase {
    private let mimeTypes = NetworkMedia.defaultImageMimeTypes
    private let base = URL(string: "http://192.168.1.10:8200/ctl")!

    private func description(controlURL: String, udn: String = "uuid:nas-1", name: String = "NAS di casa") -> String {
        """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>\(name)</friendlyName>
            <UDN>\(udn)</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/cm</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <controlURL>\(controlURL)</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
        """
    }

    private func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }

    private func browseResponse(_ didl: String, returned: Int, total: Int) -> Data {
        Data("""
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
        <Result>\(escape(didl))</Result><NumberReturned>\(returned)</NumberReturned><TotalMatches>\(total)</TotalMatches><UpdateID>1</UpdateID>
        </u:BrowseResponse></s:Body></s:Envelope>
        """.utf8)
    }

    private func didl(_ content: String) -> String {
        #"<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">"#
            + content + "</DIDL-Lite>"
    }

    private func container(_ id: String, _ title: String, upnpClass: String = "object.container.storageFolder") -> String {
        #"<container id="\#(id)" parentID="0" restricted="1"><dc:title>\#(title)</dc:title><upnp:class>\#(upnpClass)</upnp:class></container>"#
    }

    private func photo(_ id: String, _ title: String, _ url: String) -> String {
        #"<item id="\#(id)" parentID="x" restricted="1"><dc:title>\#(title)</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>"#
            + #"<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="160x120">\#(url)?tn</res>"#
            + #"<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="4000x3000">\#(url)</res>"#
            + #"<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED" resolution="1024x768">\#(url)?med</res></item>"#
    }

    func testParsesTheDeviceAndResolvesTheControlAddress() throws {
        let location = URL(string: "http://192.168.1.10:8200/rootDesc.xml")!
        let server = try XCTUnwrap(UpnpXML.parseDevice(Data(description(controlURL: "ctl/content").utf8), location: location))
        XCTAssertEqual(server.udn, "uuid:nas-1")
        XCTAssertEqual(server.name, "NAS di casa")
        XCTAssertEqual(server.controlURL.absoluteString, "http://192.168.1.10:8200/ctl/content")
        // Un router senza ContentDirectory non è un server multimediale.
        let router = description(controlURL: "/x").replacingOccurrences(of: "ContentDirectory", with: "WANIPConnection")
        XCTAssertNil(try UpnpXML.parseDevice(Data(router.utf8), location: location))
    }

    func testChoosesTheOriginalPhotoAndSkipsOtherFiles() throws {
        let content = didl(
            container("10", "Vacanze &amp; mare") + photo("p1", "IMG_0001.jpg", "/media/1.jpg")
                + #"<item id="a1"><dc:title>Canzone</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>"#
                + #"<res protocolInfo="http-get:*:audio/mpeg:*">/media/a.mp3</res><res protocolInfo="http-get:*:image/jpeg:*">/media/cover.jpg</res></item>"#
                + #"<item id="r1"><dc:title>Raw</dc:title><res protocolInfo="http-get:*:image/x-canon-cr2:*">/media/r.cr2</res></item>"#
                + #"<item id="p2"><dc:title>Senza risoluzione</dc:title><res protocolInfo="http-get:*:image/png:*">http://192.168.1.10:8200/media/2.png</res></item>"#
        )
        let page = try UpnpXML.parseBrowseResponse(browseResponse(content, returned: 5, total: 5), baseURL: base, accepts: mimeTypes.contains)
        XCTAssertEqual(page.containers, [MediaContainer(id: "10", title: "Vacanze & mare")])
        XCTAssertEqual(page.files, [
            MediaFile(id: "p1", title: "IMG_0001.jpg", url: URL(string: "http://192.168.1.10:8200/media/1.jpg")!, mimeType: "image/jpeg"),
            MediaFile(id: "p2", title: "Senza risoluzione", url: URL(string: "http://192.168.1.10:8200/media/2.png")!, mimeType: "image/png"),
        ])
        XCTAssertEqual(page.total, 5)
    }

    func testPhotosUseTheLightestVersionThatCoversTheScreen() throws {
        let xml = browseResponse(didl(photo("p1", "IMG.jpg", "/media/1.jpg")), returned: 1, total: 1)
        let fullHD = ScreenSize(longEdge: 1920, shortEdge: 1080)
        XCTAssertEqual(try UpnpXML.parseBrowseResponse(xml, baseURL: base, accepts: mimeTypes.contains, screen: fullHD).files.first?.url.absoluteString,
                       "http://192.168.1.10:8200/media/1.jpg")
        XCTAssertEqual(try UpnpXML.parseBrowseResponse(xml, baseURL: base, accepts: mimeTypes.contains, screen: ScreenSize(longEdge: 1024, shortEdge: 600)).files.first?.url.absoluteString,
                       "http://192.168.1.10:8200/media/1.jpg?med")
        let sized = didl(
            #"<item id="p2"><dc:title>Grande</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>"#
                + #"<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="6000x4000">/o.jpg</res>"#
                + #"<res protocolInfo="http-get:*:image/jpeg:*" resolution="2560x1707">/l.jpg</res>"#
                + #"<res protocolInfo="http-get:*:image/jpeg:*" resolution="1920x1280">/m.jpg</res>"#
                + #"<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="4000x3000">/t.jpg</res></item>"#
        )
        let page = browseResponse(sized, returned: 1, total: 1)
        XCTAssertEqual(try UpnpXML.parseBrowseResponse(page, baseURL: base, accepts: mimeTypes.contains, screen: fullHD).files.first?.url.absoluteString,
                       "http://192.168.1.10:8200/m.jpg")
        XCTAssertEqual(try UpnpXML.parseBrowseResponse(page, baseURL: base, accepts: mimeTypes.contains, screen: ScreenSize(longEdge: 3840, shortEdge: 2160)).files.first?.url.absoluteString,
                       "http://192.168.1.10:8200/o.jpg")
        XCTAssertEqual(try UpnpXML.parseBrowseResponse(page, baseURL: base, accepts: mimeTypes.contains).files.first?.url.absoluteString,
                       "http://192.168.1.10:8200/o.jpg")
    }

    func testVideosKeepTheirVideoVersionAndCoversAreNotPhotos() throws {
        let content = didl(
            #"<item id="v1"><dc:title>Torta.mp4</dc:title><upnp:class>object.item.videoItem</upnp:class>"#
                + #"<res protocolInfo="http-get:*:image/jpeg:*" resolution="3840x2160">/media/v.jpg</res>"#
                + #"<res protocolInfo="http-get:*:video/mp4:*" resolution="1920x1080">/media/v.mp4</res></item>"#
        )
        let both: (String) -> Bool = { self.mimeTypes.contains($0) || $0.hasPrefix("video/") }
        let page = try UpnpXML.parseBrowseResponse(browseResponse(content, returned: 1, total: 1), baseURL: base, accepts: both)
        XCTAssertEqual(page.files.map(\.url.absoluteString), ["http://192.168.1.10:8200/media/v.mp4"])
        XCTAssertEqual(try UpnpXML.parseBrowseResponse(browseResponse(content, returned: 1, total: 1), baseURL: base, accepts: mimeTypes.contains).files, [])
    }

    func testRefusesDoctypesAndReportsSoapFaults() {
        let evil = #"<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><root><device><UDN>&x;</UDN></device></root>"#
        XCTAssertThrowsError(try UpnpXML.parseDevice(Data(evil.utf8), location: base)) { error in
            XCTAssertEqual(error as? UpnpError, .invalidXML)
        }
        let fault = #"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"#
        XCTAssertThrowsError(try UpnpXML.parseBrowseResponse(Data(fault.utf8), baseURL: base, accepts: mimeTypes.contains)) { error in
            XCTAssertEqual(error as? UpnpError, .fault("701"))
        }
    }

    func testSsdpAnswersAndMessages() {
        let answer = SSDP.parseAnswer("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.10:8200/rootDesc.xml\r\nUSN: uuid:nas-1::urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n")
        XCTAssertEqual(answer?.location.absoluteString, "http://192.168.1.10:8200/rootDesc.xml")
        XCTAssertEqual(answer?.udn, "uuid:nas-1")
        XCTAssertNil(SSDP.parseAnswer("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.10/x\r\nUSN: uuid:a\r\n\r\n"))
        XCTAssertNil(SSDP.parseAnswer("HTTP/1.1 200 OK\r\nLOCATION: https://example.com/x\r\nUSN: uuid:a\r\n\r\n"))
        let multicast = SSDP.searchMessage(target: SSDP.contentDirectory)
        XCTAssertTrue(multicast.hasPrefix("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
        XCTAssertTrue(multicast.contains("MX: 2\r\n"))
        let direct = SSDP.searchMessage(target: SSDP.mediaServer, host: "192.168.1.10:1900", multicast: false)
        XCTAssertTrue(direct.contains("HOST: 192.168.1.10:1900\r\n"))
        XCTAssertFalse(direct.contains("MX:"))
        XCTAssertTrue(direct.hasSuffix("\r\n\r\n"))
    }

    func testOnlyHomeNetworkAddressesAreRead() {
        for host in ["192.168.1.10", "10.0.0.2", "172.16.5.4", "172.31.255.1", "169.254.3.3", "fe80::1", "[fe80::1%en0]", "fd12::3"] {
            XCTAssertTrue(LANAddress.isLocal(host), host)
        }
        for host in ["8.8.8.8", "172.32.0.1", "nas.local", "example.com", "2001:db8::1", "", "127.0.0.1"] {
            XCTAssertFalse(LANAddress.isLocal(host), host)
        }
        XCTAssertFalse(LANAddress.isLocal(URL(string: "https://192.168.1.10/x.jpg")!))
        XCTAssertTrue(LANAddress.isLocal(URL(string: "http://192.168.1.10:8200/x.jpg")!))
    }

    func testTypedAddressFindsTheServerOnAKnownPortAndScansFolders() async throws {
        let http = FakeHTTPClient()
        http.always("GET", "/rootDesc.xml", .bytes(description(controlURL: "/ctl")))
        let root = didl(container("1", "Foto") + container("9", "Musica", upnpClass: "object.container.album.musicAlbum"))
        let photos = didl(photo("p1", "Torta.jpg", "/media/1.jpg") + container("2", "Mare"))
        let beach = didl(photo("p2", "Onda.jpg", "/media/2.jpg"))
        http.enqueue("POST", "/ctl", .bytes(String(decoding: browseResponse(root, returned: 2, total: 2), as: UTF8.self)))
        http.enqueue("POST", "/ctl", .bytes(String(decoding: browseResponse(photos, returned: 2, total: 2), as: UTF8.self)))
        http.enqueue("POST", "/ctl", .bytes(String(decoding: browseResponse(beach, returned: 1, total: 1), as: UTF8.self)))
        let media = NetworkMedia(http: http, search: { _, _ in [] })

        let servers = await media.servers(at: "192.168.1.10")
        XCTAssertEqual(servers.map(\.name), ["NAS di casa"])
        let server = try XCTUnwrap(servers.first)
        let page = try await media.list(server, containerID: NetworkMedia.root)
        XCTAssertEqual(page.containers.map(\.title), ["Foto", "Musica"])

        let found = try await media.scanPhotos(server, containerID: "1")
        XCTAssertEqual(found.map(\.title), ["Torta", "Onda"])
        XCTAssertEqual(found.first?.source, .network(url: "http://192.168.1.10:8200/media/1.jpg"))
        XCTAssertEqual(found.first?.id, "net:uuid:nas-1:p1")
        // Una richiesta Browse per cartella, con l'azione SOAP giusta.
        let browses = http.requests("POST", "/ctl")
        XCTAssertEqual(browses.count, 3)
        XCTAssertEqual(browses.first?.value(forHTTPHeaderField: "SOAPAction"), UpnpXML.browseAction)
        // Gli indirizzi fuori dalla rete di casa non si provano nemmeno.
        let outside = await media.servers(at: "8.8.8.8")
        XCTAssertEqual(outside, [])
    }

    func testSavedFolderIsFoundAgainByItsIdentifier() async throws {
        let http = FakeHTTPClient()
        // Il server ha cambiato indirizzo: quello salvato non risponde, la ricerca lo ritrova.
        http.always("GET", "/old.xml", .failure(URLError(.cannotConnectToHost)))
        http.always("GET", "/rootDesc.xml", .bytes(description(controlURL: "/ctl")))
        let answer = SSDP.Answer(location: URL(string: "http://192.168.1.20:8200/rootDesc.xml")!, udn: "uuid:nas-1")
        let media = NetworkMedia(http: http, search: { multicast, _ in multicast ? [answer] : [] })
        let server = await media.find(udn: "uuid:nas-1", location: URL(string: "http://192.168.1.10:8200/old.xml")!)
        XCTAssertEqual(server?.location, answer.location)
        let known = await media.known("uuid:nas-1")
        XCTAssertEqual(known?.name, "NAS di casa")
    }
}
