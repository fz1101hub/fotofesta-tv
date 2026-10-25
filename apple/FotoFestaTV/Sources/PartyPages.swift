import FotoFestaCore
import Foundation

/// Le pagine e le funzioni in più del server della festa (ingresso, parete, muro di telefoni, album,
/// video degli invitati, preparazione della festa), chiamate dalla coda del server. Tiene solo oggetti
/// thread-safe e interruttori letti con un lock: l'interfaccia li cambia dal thread principale.
final class PartyPages: PartyExtras, @unchecked Sendable {
    private let store: GuestPhotoStore
    private let videos: GuestVideoStore
    private let hub: PartyHub
    private let phoneWall: PhoneWall
    private let setup: PartySetup
    private let door: String
    private let wall: String
    let albumEnabled = LockedFlag()
    let videosEnabled = LockedFlag()
    /// Con l'approvazione delle foto i video non si accettano: non c'è un modo per approvarli.
    let approval = LockedFlag()

    static let wallPhotos = 30
    static let wallMessages = 20

    init(store: GuestPhotoStore, videos: GuestVideoStore, hub: PartyHub, phoneWall: PhoneWall, setup: PartySetup) {
        self.store = store
        self.videos = videos
        self.hub = hub
        self.phoneWall = phoneWall
        self.setup = setup
        door = Self.resource("door")
        wall = Self.resource("wall")
    }

    func doorPage() -> String { door }

    func wallPage() -> String { wall }

    func wallState() -> String {
        let approved = store.photos.filter(\.approved).sorted { $0.receivedAt > $1.receivedAt }
        let name = setup.state.brand.name
        let title = name.isEmpty ? AppModel.partyTitle(for: AppModel.storedSettings().template) : name
        let photos = approved.prefix(Self.wallPhotos).map { #"{"id":\#(Self.json($0.id)),"sender":\#($0.sender.map(Self.json) ?? "null")}"# }
        let messages = hub.approvedMessages().suffix(Self.wallMessages).map { #"{"text":\#(Self.json($0.text)),"name":\#($0.name.map(Self.json) ?? "null")}"# }
        let announcement = hub.currentAnnouncement().map { Self.json($0.text) } ?? "null"
        return #"{"title":\#(Self.json(title)),"count":\#(approved.count),"photos":[\#(photos.joined(separator: ","))],"messages":[\#(messages.joined(separator: ","))],"announcement":\#(announcement)}"#
    }

    func wallPhoto(_ id: String) -> Data? {
        guard store.photos.contains(where: { $0.id == id && $0.approved }) else { return nil }
        return store.read(id)
    }

    func album() -> [ZipStream.Entry]? {
        guard albumEnabled.value else { return nil }
        let store = self.store
        let photos = store.photos.filter(\.approved).enumerated().map { index, photo in
            ZipStream.Entry(name: String(format: "FotoFesta-%03d", index + 1) + Self.suffix(photo.sender) + ".jpg") { store.read(photo.id) }
        }
        let clips = videos.videos.enumerated().map { index, video in
            ZipStream.Entry(name: String(format: "FotoFesta-video-%03d", index + 1) + Self.suffix(video.sender) + ".mp4") {
                try? Data(contentsOf: video.url)
            }
        }
        return photos + clips
    }

    func phoneWallJSON(player: String?) -> String { phoneWall.phoneJSON(player: player) }

    func phoneWallAction(_ form: [String: String]) -> (status: Int, json: String)? { phoneWall.handle(form) }

    func deleteByGuest(_ id: String) -> Bool {
        if videos.remove(id) { return true }
        guard store.photos.contains(where: { $0.id == id }) else { return false }
        store.remove(id)
        return true
    }

    func receiveVideo(_ data: Data, sender: String?, player: String?) -> GuestUploadServer.Upload {
        guard videosEnabled.value, !approval.value else { return .init(.unsupported) }
        // Un video conta come una foto per i limiti a testa (una a testa, rullino).
        switch hub.takePhoto(player: player) {
        case .quiet: return .init(.quiet)
        case .limit, .noPlayer: return .init(.limit)
        case .allowed: break
        }
        switch videos.add(data, sender: sender) {
        case let .added(video):
            return .init(.shown, photoID: video.id)
        case .full:
            hub.returnPhoto(player: player)
            return .init(.full)
        case .invalid:
            hub.returnPhoto(player: player)
            return .init(.unsupported)
        }
    }

    func setupJSON() -> String { setup.publicJSON() }

    func setupHostJSON() -> String { setup.json() }

    func logo() -> Data? { setup.logo() }

    func sponsor(_ id: String) -> Data? { setup.sponsor(id) }

    func hostUpload(kind: String, data: Data, name: String?) -> Bool {
        switch kind {
        case "logo": return setup.setLogo(data)
        case "kid": return setup.addKid(name: name, data: data) != nil
        default: return setup.addSponsor(data) != nil
        }
    }

    private static func suffix(_ sender: String?) -> String {
        guard let sender else { return "" }
        let clean = String(sender.filter { $0.isLetter || $0.isNumber }.prefix(20))
        return clean.isEmpty ? "" : "-" + clean
    }

    private static func json(_ text: String) -> String {
        guard let data = try? JSONEncoder().encode(text) else { return "\"\"" }
        return String(decoding: data, as: UTF8.self)
    }

    private static func resource(_ name: String) -> String {
        Bundle.main.url(forResource: name, withExtension: "html").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
    }
}
