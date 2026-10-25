import Foundation

/// Come si presenta la festa, preparato dalla regia sul telefono o importato da un file: nome e
/// colore dell'evento con il logo, scaletta della serata, sponsor da mostrare tra le foto, invitati
/// attesi, quiz e foto da piccoli. Resta sul dispositivo tra una festa e l'altra (un locale lo usa
/// ogni sera), in `directory`: un file JSON e le immagini accanto. Come `PartySetup.kt`. Thread-safe.
public final class PartySetup: @unchecked Sendable {
    /// `logoStamp` cambia a ogni logo nuovo, così chi lo mostra sa di doverlo rileggere.
    public struct Brand: Equatable, Sendable {
        public var name = ""
        public var accent: String?
        public var hasLogo = false
        public var logoStamp = 0
    }

    /// Momento della scaletta: minuti dalla mezzanotte e titolo ("21:30 Taglio della torta").
    public struct Slot: Equatable, Sendable {
        public let minuteOfDay: Int
        public let title: String

        public var time: String { String(format: "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60) }
    }

    /// Foto di un invitato da piccolo, per il gioco "Chi è da piccolo?".
    public struct Kid: Equatable, Sendable, Identifiable {
        public let id: String
        public let name: String
    }

    public struct State: Equatable, Sendable {
        public var brand = Brand()
        public var schedule: [Slot] = []
        public var sponsors: [String] = []
        public var guestList: [String] = []
        /// Quiz sui festeggiati, una domanda per riga ("Domanda? | giusta | sbagliata").
        public var quiz = ""
        public var kids: [Kid] = []
    }

    public static let maxImageBytes = 2 * 1024 * 1024
    public static let maxSponsors = 8
    public static let maxGuests = 300
    public static let maxKids = 40
    public static let maxQuizText = 6_000
    public static let maxSlots = 30
    static let maxName = 60
    private static let logoName = "logo"

    private let directory: URL
    private let lock = NSLock()
    private var current: State
    private var revision = 0

    public init(directory: URL) {
        self.directory = directory
        current = Self.load(directory)
    }

    public var state: State { lock.withLock { current } }

    /// Cambia a ogni modifica: l'interfaccia lo confronta per aggiornarsi.
    public func currentRevision() -> Int { lock.withLock { revision } }

    public func setName(_ name: String?) {
        update { $0.brand.name = GuestSender.clean(name, maxLength: Self.maxName) ?? "" }
    }

    public func setAccent(_ color: String?) {
        update { $0.brand.accent = color.flatMap { Self.validColor($0) ? $0 : nil } }
    }

    /// Logo già ridotto dal telefono: al massimo `maxImageBytes`; nil lo toglie.
    @discardableResult
    public func setLogo(_ data: Data?) -> Bool {
        lock.withLock {
            if let data, data.count > Self.maxImageBytes || GuestImageFormat.sniff(data) == nil { return false }
            let url = directory.appendingPathComponent(Self.logoName)
            if let data {
                guard (try? createDirectory()) != nil, (try? data.write(to: url, options: .atomic)) != nil else { return false }
            } else {
                try? FileManager.default.removeItem(at: url)
            }
            current.brand.hasLogo = data != nil
            current.brand.logoStamp += 1
            saveLocked()
            return true
        }
    }

    public func logo() -> Data? {
        lock.withLock { try? Data(contentsOf: directory.appendingPathComponent(Self.logoName)) }
    }

    /// Immagine di uno sponsor; nil se ce ne sono già `maxSponsors` o non è un'immagine.
    public func addSponsor(_ data: Data) -> String? {
        lock.withLock {
            guard data.count <= Self.maxImageBytes, GuestImageFormat.sniff(data) != nil, current.sponsors.count < Self.maxSponsors else { return nil }
            let id = Self.newID()
            guard (try? createDirectory()) != nil,
                  (try? data.write(to: directory.appendingPathComponent("sponsor_\(id)"), options: .atomic)) != nil else { return nil }
            current.sponsors.append(id)
            saveLocked()
            return id
        }
    }

    public func removeSponsor(_ id: String) {
        lock.withLock {
            guard current.sponsors.contains(id) else { return }
            try? FileManager.default.removeItem(at: directory.appendingPathComponent("sponsor_\(id)"))
            current.sponsors.removeAll { $0 == id }
            saveLocked()
        }
    }

    public func sponsor(_ id: String) -> Data? {
        lock.withLock {
            guard current.sponsors.contains(id) else { return nil }
            return try? Data(contentsOf: directory.appendingPathComponent("sponsor_\(id)"))
        }
    }

    /// Scaletta da testo, una riga per momento ("21:30 Taglio della torta"); le righe senza ora si saltano.
    public func setSchedule(_ text: String?) {
        update { $0.schedule = Self.parseSchedule(text ?? "") }
    }

    /// Invitati attesi, uno per riga o separati da virgole.
    public func setGuestList(_ text: String?) {
        var seen = Set<String>()
        let names = (text ?? "").split(whereSeparator: { $0 == "\n" || $0 == "," || $0 == ";" })
            .compactMap { GuestSender.clean(String($0)) }
            .filter { seen.insert($0).inserted }
        update { $0.guestList = Array(names.prefix(Self.maxGuests)) }
    }

    public func setQuiz(_ text: String?) {
        update { $0.quiz = String((text ?? "").prefix(Self.maxQuizText)) }
    }

    /// Foto da piccolo di `name` per il gioco; nil se sono già `maxKids` o non è un'immagine.
    public func addKid(name: String?, data: Data) -> String? {
        guard let clean = GuestSender.clean(name) else { return nil }
        return lock.withLock {
            guard data.count <= Self.maxImageBytes, GuestImageFormat.sniff(data) != nil, current.kids.count < Self.maxKids else { return nil }
            let id = Self.newID()
            guard (try? createDirectory()) != nil,
                  (try? data.write(to: directory.appendingPathComponent("kid_\(id)"), options: .atomic)) != nil else { return nil }
            current.kids.append(Kid(id: id, name: clean))
            saveLocked()
            return id
        }
    }

    public func removeKid(_ id: String) {
        lock.withLock {
            // Solo gli id conosciuti: il nome del file non deve mai venire da fuori così com'è.
            guard current.kids.contains(where: { $0.id == id }) else { return }
            try? FileManager.default.removeItem(at: directory.appendingPathComponent("kid_\(id)"))
            current.kids.removeAll { $0.id == id }
            saveLocked()
        }
    }

    public func kidPhoto(_ id: String) -> Data? {
        lock.withLock {
            guard current.kids.contains(where: { $0.id == id }) else { return nil }
            return try? Data(contentsOf: directory.appendingPathComponent("kid_\(id)"))
        }
    }

    /// Tutto in un JSON, immagini comprese (le foto da piccoli no: sono personali e pesanti).
    public func exportJSON() -> Data? {
        let snapshot = state
        let file = ExportFile(
            version: 1, name: snapshot.brand.name, accent: snapshot.brand.accent,
            logo: logo()?.base64EncodedString(),
            schedule: snapshot.schedule.map { "\($0.time) \($0.title)" },
            guests: snapshot.guestList,
            sponsors: snapshot.sponsors.compactMap { sponsor($0)?.base64EncodedString() },
            quiz: snapshot.quiz
        )
        return try? JSONEncoder().encode(file)
    }

    /// Il contrario di `exportJSON`: sostituisce tutto. False se il file non è valido.
    @discardableResult
    public func importJSON(_ data: Data) -> Bool {
        guard let file = try? JSONDecoder().decode(ExportFile.self, from: data), file.version == 1 else { return false }
        for id in state.sponsors { removeSponsor(id) }
        setName(file.name)
        setAccent(file.accent)
        setLogo(file.logo.flatMap { Data(base64Encoded: $0) })
        setSchedule(file.schedule?.joined(separator: "\n"))
        setGuestList(file.guests?.joined(separator: "\n"))
        setQuiz(file.quiz)
        for text in (file.sponsors ?? []).prefix(Self.maxSponsors) {
            if let image = Data(base64Encoded: text) { _ = addSponsor(image) }
        }
        return true
    }

    /// Per la pagina degli invitati: nome, colore, logo e scaletta (niente lista degli invitati).
    public func publicJSON() -> String {
        let snapshot = state
        return encode(PublicSetup(name: snapshot.brand.name, accent: snapshot.brand.accent, logo: snapshot.brand.hasLogo,
                                  schedule: snapshot.schedule.map { PublicSlot(time: $0.time, title: $0.title) }))
    }

    /// Per la regia: tutto, compresi sponsor, invitati attesi, quiz e foto da piccoli (senza immagini).
    public func json() -> String {
        let snapshot = state
        return encode(HostSetup(name: snapshot.brand.name, accent: snapshot.brand.accent, logo: snapshot.brand.hasLogo,
                                schedule: snapshot.schedule.map { PublicSlot(time: $0.time, title: $0.title) },
                                sponsors: snapshot.sponsors, guests: snapshot.guestList, quiz: snapshot.quiz,
                                kids: snapshot.kids.map { HostKid(id: $0.id, name: $0.name) }))
    }

    public static func validColor(_ value: String) -> Bool {
        value.count == 7 && value.first == "#" && value.dropFirst().allSatisfy(\.isHexDigit)
    }

    /// "21:30 Taglio della torta", "9.15 - Arrivo" e simili; ordinata per ora.
    public static func parseSchedule(_ text: String) -> [Slot] {
        var slots: [Slot] = []
        for line in text.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard let separator = trimmed.firstIndex(where: { $0 == ":" || $0 == "." }) else { continue }
            let hourText = trimmed[..<separator]
            let rest = trimmed[trimmed.index(after: separator)...]
            let minuteText = rest.prefix(2)
            guard (1...2).contains(hourText.count), hourText.allSatisfy(\.isNumber), minuteText.count == 2, minuteText.allSatisfy(\.isNumber),
                  let hour = Int(hourText), let minute = Int(minuteText), hour <= 23, minute <= 59 else { continue }
            var title = rest.dropFirst(2).trimmingCharacters(in: .whitespaces)
            if let first = title.first, "-–—".contains(first) { title = String(title.dropFirst()).trimmingCharacters(in: .whitespaces) }
            guard let clean = GuestSender.clean(title, maxLength: 80) else { continue }
            slots.append(Slot(minuteOfDay: hour * 60 + minute, title: clean))
            if slots.count >= maxSlots { break }
        }
        return slots.sorted { $0.minuteOfDay < $1.minuteOfDay }
    }

    // MARK: - Interni

    private func update(_ change: (inout State) -> Void) {
        lock.withLock {
            change(&current)
            saveLocked()
        }
    }

    private func createDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func saveLocked() {
        revision += 1
        let file = StoredFile(name: current.brand.name, accent: current.brand.accent,
                              schedule: current.schedule.map { "\($0.time) \($0.title)" },
                              guests: current.guestList, sponsors: current.sponsors, quiz: current.quiz,
                              kids: current.kids.map { HostKid(id: $0.id, name: $0.name) })
        guard let data = try? JSONEncoder().encode(file), (try? createDirectory()) != nil else { return }
        try? data.write(to: directory.appendingPathComponent("setup.json"), options: .atomic)
    }

    private static func load(_ directory: URL) -> State {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent("setup.json")),
              let file = try? JSONDecoder().decode(StoredFile.self, from: data) else { return State() }
        let manager = FileManager.default
        func exists(_ name: String) -> Bool { manager.fileExists(atPath: directory.appendingPathComponent(name).path) }
        var state = State()
        state.brand = Brand(name: file.name ?? "", accent: file.accent.flatMap { validColor($0) ? $0 : nil }, hasLogo: exists(logoName))
        state.schedule = parseSchedule((file.schedule ?? []).joined(separator: "\n"))
        state.sponsors = (file.sponsors ?? []).filter { isID($0) && exists("sponsor_\($0)") }
        state.guestList = file.guests ?? []
        state.quiz = file.quiz ?? ""
        state.kids = (file.kids ?? []).filter { isID($0.id) && exists("kid_\($0.id)") }.map { Kid(id: $0.id, name: $0.name) }
        return state
    }

    private static func isID(_ value: String) -> Bool { value.count == 12 && value.allSatisfy(\.isHexDigit) }

    private static func newID() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<6).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }

    private func encode<T: Encodable>(_ value: T) -> String {
        guard let data = try? JSONEncoder().encode(value) else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }

    private struct StoredFile: Codable {
        var name: String?
        var accent: String?
        var schedule: [String]?
        var guests: [String]?
        var sponsors: [String]?
        var quiz: String?
        var kids: [HostKid]?
    }

    private struct ExportFile: Codable {
        var version: Int
        var name: String?
        var accent: String?
        var logo: String?
        var schedule: [String]?
        var guests: [String]?
        var sponsors: [String]?
        var quiz: String?
    }

    private struct PublicSlot: Codable {
        let time: String
        let title: String
    }

    private struct HostKid: Codable {
        let id: String
        let name: String
    }

    /// Le chiavi nil restano nel JSON come null, come nella versione Android.
    private struct PublicSetup: Encodable {
        let name: String
        let accent: String?
        let logo: Bool
        let schedule: [PublicSlot]

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(name, forKey: .name)
            try container.encode(accent, forKey: .accent)
            try container.encode(logo, forKey: .logo)
            try container.encode(schedule, forKey: .schedule)
        }

        private enum CodingKeys: String, CodingKey { case name, accent, logo, schedule }
    }

    private struct HostSetup: Encodable {
        let name: String
        let accent: String?
        let logo: Bool
        let schedule: [PublicSlot]
        let sponsors: [String]
        let guests: [String]
        let quiz: String
        let kids: [HostKid]

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(name, forKey: .name)
            try container.encode(accent, forKey: .accent)
            try container.encode(logo, forKey: .logo)
            try container.encode(schedule, forKey: .schedule)
            try container.encode(sponsors, forKey: .sponsors)
            try container.encode(guests, forKey: .guests)
            try container.encode(quiz, forKey: .quiz)
            try container.encode(kids, forKey: .kids)
        }

        private enum CodingKeys: String, CodingKey { case name, accent, logo, schedule, sponsors, guests, quiz, kids }
    }
}
