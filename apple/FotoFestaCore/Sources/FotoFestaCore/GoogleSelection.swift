import Foundation

/// Foto scelte in Google Foto: sul dispositivo restano solo la sessione Picker, gli identificativi e
/// i nomi, mai le immagini. Le foto si caricano da Google quando servono e solo finché la sessione è valida.
public struct GoogleSelection: Codable, Equatable, Sendable {
    public struct Item: Codable, Equatable, Sendable {
        public let id: String
        public let filename: String
        public let isVideo: Bool

        public init(id: String, filename: String, isVideo: Bool = false) {
            self.id = id
            self.filename = filename
            self.isVideo = isVideo
        }

        private enum CodingKeys: String, CodingKey {
            case id, filename, isVideo
        }

        // Le selezioni salvate dalle versioni senza video non hanno isVideo.
        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id)
            filename = try container.decode(String.self, forKey: .filename)
            isVideo = try container.decodeIfPresent(Bool.self, forKey: .isVideo) ?? false
        }
    }

    public let sessionID: String
    /// Scadenza della sessione indicata da Google, se nota.
    public let expiresAt: Date?
    public let items: [Item]

    public init(sessionID: String, expiresAt: Date?, items: [Item]) {
        self.sessionID = sessionID
        self.expiresAt = expiresAt
        self.items = items
    }

    public func isExpired(at date: Date) -> Bool {
        guard let expiresAt else { return false }
        return date >= expiresAt
    }

    public var photos: [Photo] {
        items.map { Photo(id: "google:\($0.id)", title: $0.filename, source: .google(mediaID: $0.id), isVideo: $0.isVideo) }
    }
}

/// Selezione salvata in UserDefaults, su tvOS l'unico spazio persistente garantito: circa 150 byte per
/// foto, con al massimo `PhotosPicker.maxItemCount` foto per selezione. Una copia in memoria è
/// condivisa da app e caricamento delle immagini.
public final class GoogleSelectionStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let lock = NSLock()
    private var loaded = false
    private var cached: GoogleSelection?

    public init(defaults: UserDefaults = .standard, key: String = "googleSelection") {
        self.defaults = defaults
        self.key = key
    }

    public func current() -> GoogleSelection? {
        lock.lock(); defer { lock.unlock() }
        return currentLocked()
    }

    public func save(_ selection: GoogleSelection) {
        lock.lock(); defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(selection) { defaults.set(data, forKey: key) }
        cached = selection
        loaded = true
    }

    /// Cancella la selezione solo se appartiene ancora a `sessionID`; restituisce true se l'ha cancellata.
    @discardableResult
    public func clearIfCurrent(sessionID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard currentLocked()?.sessionID == sessionID else { return false }
        clearLocked()
        return true
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        clearLocked()
    }

    private func currentLocked() -> GoogleSelection? {
        if !loaded {
            cached = defaults.data(forKey: key)
                .flatMap { try? JSONDecoder().decode(GoogleSelection.self, from: $0) }
                .flatMap { $0.items.isEmpty || $0.sessionID.isEmpty ? nil : $0 }
            loaded = true
        }
        return cached
    }

    private func clearLocked() {
        defaults.removeObject(forKey: key)
        cached = nil
        loaded = true
    }
}
