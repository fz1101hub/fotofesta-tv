import FotoFestaCore
import XCTest

private final class Commands: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [PartyCommand] = []
    func add(_ command: PartyCommand) { lock.withLock { items.append(command) } }
    var all: [PartyCommand] { lock.withLock { items } }
}

final class HostRemoteTests: XCTestCase {
    private let clock = TestClock()
    private let phone = "192.168.1.20"

    private func makeHost() -> HostRemote {
        let clock = clock
        return HostRemote(now: { clock.now })
    }

    private func token(_ host: HostRemote, client: String = "192.168.1.20") -> String {
        guard case let .accepted(token) = host.login(host.currentPIN(), client: client) else {
            XCTFail("PIN rifiutato")
            return ""
        }
        return token
    }

    private func wrongPIN(_ host: HostRemote) -> String { host.currentPIN() == "000000" ? "111111" : "000000" }

    func testThePINOpensTheHostPageAndANewPartyChangesIt() {
        let host = makeHost()
        let pin = host.currentPIN()!
        XCTAssertEqual(pin.count, HostRemote.pinLength)
        XCTAssertTrue(pin.allSatisfy(\.isNumber))
        let token = token(host)
        XCTAssertTrue(host.isHost(token))
        XCTAssertFalse(host.isHost("altro"))
        XCTAssertFalse(host.isHost(nil))
        host.reset()
        XCTAssertFalse(host.isHost(token))
    }

    func testWrongPINsAreLimitedAndLockTheHostPage() {
        let host = makeHost()
        let wrong = wrongPIN(host)
        for _ in 0..<HostRemote.maxAttemptsPerClient { XCTAssertEqual(host.login(wrong, client: phone), .wrong) }
        XCTAssertEqual(host.login(host.currentPIN(), client: phone), .rateLimited)
        clock.advance(HostRemote.attemptWindow)
        for index in 0..<(HostRemote.maxFailures - HostRemote.maxAttemptsPerClient) {
            _ = host.login(wrong, client: "10.0.0.\(index / HostRemote.maxAttemptsPerClient + 1)")
        }
        XCTAssertEqual(host.login(host.currentPIN(), client: "10.0.0.99"), .locked)
        clock.advance(HostRemote.lockTime)
        if case .accepted = host.login(host.currentPIN(), client: "10.0.0.99") {} else { XCTFail("dopo il blocco si entra") }
    }

    func testThePINCanBeTypedWithSpacesAndTheHostPageCanBeTurnedOff() {
        let host = makeHost()
        let pin = host.currentPIN()!
        let spaced = String(pin.prefix(3)) + " " + String(pin.suffix(3))
        if case .accepted = host.login(spaced, client: phone) {} else { XCTFail("PIN con lo spazio") }
        let token = token(host)
        host.setEnabled(false)
        XCTAssertNil(host.currentPIN())
        XCTAssertFalse(host.isHost(token))
        XCTAssertEqual(host.login("123456", client: phone), .disabled)
    }

    func testCommandsReachTheTVOnlyWithAValidToken() {
        let host = makeHost()
        let received = Commands()
        host.setOnCommand { received.add($0) }
        let token = token(host)
        XCTAssertEqual(host.handle(token: "sbagliato", form: ["command": "next"]).status, 401)
        XCTAssertEqual(host.handle(token: token, form: ["command": "next"]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "approve_photo", "id": "abc-123"]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "approve_photo", "id": "../etc"]).status, 400)
        XCTAssertEqual(host.handle(token: token, form: ["command": "announce", "text": "  Il buffet\nè aperto!  "]).status, 200)
        XCTAssertEqual(host.handle(token: token, form: ["command": "announce", "text": "  "]).status, 400)
        XCTAssertEqual(host.handle(token: token, form: ["command": "boh"]).status, 400)
        XCTAssertEqual(received.all, [.next, .approvePhoto("abc-123"), .announce("Il buffet è aperto!")])
    }

    func testStatusListsWhatWaitsForApproval() throws {
        let host = makeHost()
        let clock = clock
        let hub = PartyHub(now: { clock.now })
        hub.setFeatures(PartyHub.Features(moderateMessages: true))
        _ = hub.message(player: "player-giulia", text: "Auguri \"sposi\"", name: "Giulia")
        host.updateStatus(HostRemote.Status(slideshow: true, playing: true, title: "Torta", photos: 12,
                                            pendingPhotos: [HostRemote.PendingPhoto(id: "p1", sender: "Marco")]))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(host.statusJSON(hub: hub).utf8)) as? [String: Any])
        XCTAssertEqual(json["playing"] as? Bool, true)
        XCTAssertEqual(json["title"] as? String, "Torta")
        XCTAssertEqual((json["pendingPhotos"] as? [[String: Any]])?.first?["id"] as? String, "p1")
        XCTAssertEqual((json["pendingMessages"] as? [[String: Any]])?.first?["text"] as? String, "Auguri \"sposi\"")
        XCTAssertTrue(host.canShow("p1"))
        XCTAssertFalse(host.canShow("p2"))
    }

    func testAnnouncementsAndTheDirectorAppearOnPhones() throws {
        let clock = clock
        let hub = PartyHub(now: { clock.now })
        _ = hub.react(player: "player-giulia", index: 0, name: "Giulia")
        XCTAssertTrue(hub.announce("Si taglia la torta!"))
        XCTAssertFalse(hub.announce("   "))
        XCTAssertEqual(hub.startDirector()?.name, "Giulia")
        let forGiulia = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(hub.partyJSON(player: "player-giulia").utf8)) as? [String: Any])
        XCTAssertEqual((forGiulia["announcement"] as? [String: Any])?["text"] as? String, "Si taglia la torta!")
        XCTAssertEqual((forGiulia["director"] as? [String: Any])?["you"] as? Bool, true)
        let forMarco = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(hub.partyJSON(player: "player-marco").utf8)) as? [String: Any])
        XCTAssertEqual((forMarco["director"] as? [String: Any])?["you"] as? Bool, false)

        let commands = Commands()
        hub.setOnDirectorCommand { commands.add($0) }
        XCTAssertEqual(hub.handle(["type": "director", "player": "player-giulia", "command": "next"]).status, 200)
        XCTAssertEqual(hub.handle(["type": "director", "player": "player-marco", "command": "next"]).status, 403)
        XCTAssertEqual(commands.all, [.next])

        clock.advance(PartyHub.directorTime)
        XCTAssertNil(hub.currentDirector())
        clock.advance(PartyHub.announcementPhoneTime)
        XCTAssertNil(hub.currentAnnouncement())
    }

    func testNoDirectorWithoutNames() {
        let hub = PartyHub()
        _ = hub.react(player: "player-anonimo", index: 0, name: nil)
        XCTAssertNil(hub.startDirector())
    }

    func testServerLetsTheHostLogInAndControlTheParty() async throws {
        let host = HostRemote()
        let received = Commands()
        host.setOnCommand { received.add($0) }
        let photo = Data([0xFF, 0xD8, 0xFF, 0xE0, 9, 9])
        let server = GuestUploadServer(key: "test-key", page: { "<html></html>" }, onPhoto: { _, _ in .shown },
                                       game: PhoneGame(), hub: PartyHub(), host: host,
                                       hostPhoto: { $0 == "attesa-1" ? photo : nil })
        let port = try await server.start(host: "127.0.0.1", preferredPort: 0)
        defer { server.stop() }
        let session = URLSession(configuration: .ephemeral)

        func call(_ method: String, _ path: String, form: String? = nil, token: String? = nil) async throws -> (Int, Data, String?) {
            var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)" + path)!)
            request.httpMethod = method
            request.setValue("test-key", forHTTPHeaderField: "X-FotoFesta-Key")
            request.setValue(token, forHTTPHeaderField: "X-FotoFesta-Host")
            let data: Data
            let response: URLResponse
            if let form {
                request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                (data, response) = try await session.upload(for: request, from: Data(form.utf8))
            } else {
                (data, response) = try await session.data(for: request)
            }
            let http = response as? HTTPURLResponse
            return (http?.statusCode ?? 0, data, http?.value(forHTTPHeaderField: "Content-Type"))
        }

        let wrong = try await call("POST", "/party/host/login", form: "pin=\(wrongPIN(host))")
        XCTAssertEqual(wrong.0, 403)
        let login = try await call("POST", "/party/host/login", form: "pin=\(host.currentPIN()!)")
        XCTAssertEqual(login.0, 200)
        let token = try XCTUnwrap((try JSONSerialization.jsonObject(with: login.1) as? [String: Any])?["token"] as? String)
        let anonymous = try await call("GET", "/party/host")
        XCTAssertEqual(anonymous.0, 401)
        host.updateStatus(HostRemote.Status(slideshow: true, playing: true, pendingPhotos: [HostRemote.PendingPhoto(id: "attesa-1", sender: nil)]))
        let status = try await call("GET", "/party/host", token: token)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: status.1) as? [String: Any])?["playing"] as? Bool, true)
        let paused = try await call("POST", "/party/host", form: "command=pause", token: token)
        XCTAssertEqual(paused.0, 200)
        let refused = try await call("POST", "/party/host", form: "command=pause", token: "falso")
        XCTAssertEqual(refused.0, 401)
        XCTAssertEqual(received.all, [.pause])
        let thumbnail = try await call("GET", "/party/host/photo?id=attesa-1", token: token)
        XCTAssertEqual(thumbnail.0, 200)
        XCTAssertEqual(thumbnail.2, "image/jpeg")
        let other = try await call("GET", "/party/host/photo?id=altra", token: token)
        XCTAssertEqual(other.0, 404)
        let state = try await call("GET", "/party")
        XCTAssertEqual((try JSONSerialization.jsonObject(with: state.1) as? [String: Any])?["host"] as? Bool, true)
    }
}
