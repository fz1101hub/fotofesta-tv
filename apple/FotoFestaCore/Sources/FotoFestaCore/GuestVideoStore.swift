import Foundation

/// Video brevi mandati dagli invitati: vanno su disco, in una cartella con un limite (`maxTotalBytes`
/// e al massimo `freeShare` dello spazio libero). Come per le foto, i dati di ogni video stanno nel
/// nome del file, così la festa riprende dopo un riavvio. A fine festa (`clear`) si cancella tutto.
/// Solo MP4 e MOV: sono i formati che Apple TV e Mac sanno riprodurre (WebM no). Come `GuestVideoStore.kt`.
///
/// Thread-safe: il server lo usa dalla sua coda, l'interfaccia dal thread principale.
public final class GuestVideoStore: @unchecked Sendable {
    public struct Video: Equatable, Sendable, Identifiable {
        public let id: String
        public let sender: String?
        public let receivedAt: Date
        public let sizeBytes: Int
        public let url: URL
    }

    public enum AddResult: Equatable, Sendable {
        case added(Video)
        case full
        case invalid
    }

    public static let idLength = 16

    private let directory: URL
    private let maxTotalBytes: Int
    private let maxVideos: Int
    private let freeShare: Double
    private let freeSpace: @Sendable () -> Int64
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var stored: [Video] = []
    private var onChange: (@Sendable () -> Void)?

    public init(
        directory: URL,
        maxTotalBytes: Int,
        maxVideos: Int = 100,
        freeShare: Double = 0.10,
        freeSpace: (@Sendable () -> Int64)? = nil,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.directory = directory
        self.maxTotalBytes = maxTotalBytes
        self.maxVideos = maxVideos
        self.freeShare = freeShare
        self.freeSpace = freeSpace ?? { GuestVideoStore.availableSpace(directory) }
        self.now = now
    }

    /// Video della festa, dal più vecchio.
    public var videos: [Video] { lock.withLock { stored } }

    /// Chiamato (dalla coda di chi ha cambiato i video) a ogni video aggiunto o tolto.
    public func setOnChange(_ handler: (@Sendable () -> Void)?) {
        lock.withLock { onChange = handler }
    }

    /// Salva `data` come video nuovo; `.invalid` se non comincia come un MP4/MOV.
    public func add(_ data: Data, sender: String?) -> AddResult {
        guard Self.looksLikeVideo(data) else { return .invalid }
        let handler: (@Sendable () -> Void)?
        let video: Video
        do {
            let result: AddResult = try lock.withLock {
                let used = stored.reduce(0) { $0 + $1.sizeBytes }
                if stored.count >= maxVideos || used + data.count > maxTotalBytes || Double(data.count) > Double(freeSpace()) * freeShare {
                    return .full
                }
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let id = Self.newID()
                let at = now()
                let clean = GuestSender.clean(sender)
                let url = directory.appendingPathComponent(Self.fileName(id: id, at: at, sender: clean))
                try data.write(to: url, options: .atomic)
                let added = Video(id: id, sender: clean, receivedAt: at, sizeBytes: data.count, url: url)
                stored.append(added)
                return .added(added)
            }
            guard case let .added(added) = result else { return result }
            video = added
            handler = lock.withLock { onChange }
        } catch {
            return .full
        }
        handler?()
        return .added(video)
    }

    @discardableResult
    public func remove(_ id: String) -> Bool {
        let (removed, handler): (Bool, (@Sendable () -> Void)?) = lock.withLock {
            guard let index = stored.firstIndex(where: { $0.id == id }) else { return (false, nil) }
            try? FileManager.default.removeItem(at: stored[index].url)
            stored.remove(at: index)
            return (true, onChange)
        }
        if removed { handler?() }
        return removed
    }

    /// Video di una festa interrotta da poco; quelli più vecchi di `maxAge` secondi si cancellano.
    public func restore(maxAge: TimeInterval) {
        let manager = FileManager.default
        let files = (try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        let current = now()
        var found: [Video] = []
        for file in files {
            guard let parsed = Self.parse(file.lastPathComponent), current.timeIntervalSince(parsed.at) <= maxAge,
                  let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else {
                try? manager.removeItem(at: file)
                continue
            }
            found.append(Video(id: parsed.id, sender: parsed.sender, receivedAt: parsed.at, sizeBytes: size, url: file))
        }
        let handler = lock.withLock {
            stored = found.sorted { $0.receivedAt < $1.receivedAt }
            return onChange
        }
        handler?()
    }

    public func clear() {
        let handler = lock.withLock {
            try? FileManager.default.removeItem(at: directory)
            stored = []
            return onChange
        }
        handler?()
    }

    /// Inizio di un video MP4/MOV ("ftyp" dopo i primi quattro byte).
    public static func looksLikeVideo(_ data: Data) -> Bool {
        guard data.count >= 12 else { return false }
        let bytes = [UInt8](data.prefix(8))
        return bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70
    }

    static func fileName(id: String, at: Date, sender: String?) -> String {
        let encoded = sender.map { Data($0.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } ?? ""
        return "v_\(id)_\(Int64(at.timeIntervalSince1970 * 1000))_\(encoded).mp4"
    }

    static func parse(_ name: String) -> (id: String, at: Date, sender: String?)? {
        guard name.hasPrefix("v_"), name.hasSuffix(".mp4") else { return nil }
        let parts = name.dropFirst(2).dropLast(4).split(separator: "_", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == idLength, parts[0].allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
              let millis = Int64(parts[1]) else { return nil }
        var sender: String?
        if !parts[2].isEmpty {
            var base64 = parts[2].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            while base64.count % 4 != 0 { base64 += "=" }
            sender = Data(base64Encoded: base64).flatMap { String(data: $0, encoding: .utf8) }
        }
        return (String(parts[0]), Date(timeIntervalSince1970: TimeInterval(millis) / 1000), sender)
    }

    private static func newID() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<(idLength / 2)).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }

    private static func availableSpace(_ directory: URL) -> Int64 {
        var probe = directory
        while !FileManager.default.fileExists(atPath: probe.path), probe.pathComponents.count > 1 { probe.deleteLastPathComponent() }
        #if os(tvOS) || os(watchOS)
        // Su tvOS la capacità "per uso importante" non esiste.
        return Int64((try? probe.resourceValues(forKeys: [.volumeAvailableCapacityKey]))?.volumeAvailableCapacity ?? 0)
        #else
        let values = try? probe.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        if let important = values?.volumeAvailableCapacityForImportantUsage { return important }
        return Int64(values?.volumeAvailableCapacity ?? 0)
        #endif
    }
}
