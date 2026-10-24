import Foundation

/// Foto mandata da un invitato durante la festa.
public struct GuestPhoto: Identifiable, Hashable, Sendable {
    public let id: String
    /// Nome con cui l'invitato ha firmato la foto, già ripulito.
    public let sender: String?
    public let receivedAt: Date
    public let sizeBytes: Int
    public let approved: Bool

    public init(id: String, sender: String?, receivedAt: Date, sizeBytes: Int, approved: Bool) {
        self.id = id
        self.sender = sender
        self.receivedAt = receivedAt
        self.sizeBytes = sizeBytes
        self.approved = approved
    }
}

/// Foto mandate dagli invitati durante la festa.
///
/// Restano in RAM finché c'è posto. Quando la memoria assegnata non basta, quando il sistema chiede
/// di liberarne o quando l'app va in background, le più vecchie passano in `directory`, una cartella
/// temporanea con un limite (`maxDiskBytes` e al massimo `freeShare` dello spazio libero). Se anche
/// quella è piena, le foto più vecchie fanno posto alle nuove: prima quelle ancora da approvare, così
/// un invitato che manda molte foto non cancella quelle già in mostra. Le foto in attesa non possono
/// superare `maxPending`. Alla fine della festa (`clear()`) tutto viene cancellato.
///
/// I dati di ogni foto su disco stanno nel nome del file, così dopo un riavvio dell'app la festa può
/// riprendere senza un indice separato da tenere allineato.
public final class GuestPhotoStore: @unchecked Sendable {
    /// Esito di `add`.
    public enum AddResult: Equatable, Sendable {
        case added(GuestPhoto)
        /// Né RAM né cartella hanno posto per la foto.
        case full
        /// Troppe foto in attesa di approvazione.
        case tooManyPending

        public var photo: GuestPhoto? {
            if case let .added(photo) = self { return photo }
            return nil
        }
    }

    private final class Entry {
        var photo: GuestPhoto
        var data: Data?
        var file: URL?

        init(photo: GuestPhoto, data: Data?, file: URL?) {
            self.photo = photo
            self.data = data
            self.file = file
        }
    }

    private let directory: URL
    private let memoryBudget: Int
    private let maxDiskBytes: Int
    private let freeSpace: @Sendable () -> Int
    private let freeShare: Double
    private let maxPhotos: Int
    private let maxPending: Int
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private let fileManager = FileManager.default

    // In ordine di arrivo: la prima è la più vecchia.
    private var entries: [Entry] = []
    private var memoryUsed = 0
    private var diskUsed = 0
    private var onChange: (@Sendable ([GuestPhoto]) -> Void)?
    private var durable = false

    public init(
        directory: URL,
        memoryBudget: Int,
        maxDiskBytes: Int,
        freeSpace: (@Sendable () -> Int)? = nil,
        freeShare: Double = 0.10,
        maxPhotos: Int = 1_000,
        maxPending: Int = 100,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.directory = directory
        self.memoryBudget = memoryBudget
        self.maxDiskBytes = maxDiskBytes
        self.freeSpace = freeSpace ?? { GuestPhotoStore.availableCapacity(of: directory) }
        self.freeShare = freeShare
        self.maxPhotos = maxPhotos
        self.maxPending = maxPending
        self.now = now
    }

    /// Chiamato (sul thread che ha cambiato l'archivio) con tutte le foto, dalla più vecchia.
    public func setOnChange(_ handler: (@Sendable ([GuestPhoto]) -> Void)?) {
        lock.lock()
        onChange = handler
        lock.unlock()
    }

    /// Ogni foto va subito anche su disco, così un arresto improvviso dell'app non la perde e la
    /// festa riprende al riavvio ("Riprendi la festa").
    public func setDurable(_ value: Bool) {
        lock.withLock { durable = value }
    }

    /// Tutte le foto, approvate e da approvare, dalla più vecchia alla più recente.
    public var photos: [GuestPhoto] {
        lock.lock()
        defer { lock.unlock() }
        return entries.map(\.photo)
    }

    public var memoryUsedBytes: Int {
        lock.lock()
        defer { lock.unlock() }
        return memoryUsed
    }

    public var diskUsedBytes: Int {
        lock.lock()
        defer { lock.unlock() }
        return diskUsed
    }

    @discardableResult
    public func add(_ data: Data, sender: String?, approved: Bool) -> AddResult {
        guard !data.isEmpty else { return .full }
        return mutate {
            if !approved, entries.filter({ !$0.photo.approved }).count >= maxPending { return .tooManyPending }
            let photo = GuestPhoto(
                id: Self.newID(),
                sender: GuestSender.clean(sender),
                receivedAt: now(),
                sizeBytes: data.count,
                approved: approved
            )
            let entry = Entry(photo: photo, data: data, file: nil)
            entries.append(entry)
            memoryUsed += data.count
            while entries.count > maxPhotos { drop(victim(entries)) }
            // Una foto in attesa non può far cancellare foto già approvate: piuttosto viene rifiutata.
            fitInMemory(allowApprovedEviction: approved)
            // Se la scrittura non riesce la foto resta in RAM: si mostra lo stesso.
            if durable, entries.contains(where: { $0 === entry }) { _ = spill(entry, evictOthers: true, allowApprovedEviction: approved) }
            return entries.contains { $0 === entry } ? .added(photo) : .full
        }
    }

    /// Byte della foto, letti con il lock: un'approvazione non può rinominare il file a metà lettura.
    public func read(_ id: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries.first(where: { $0.photo.id == id }) else { return nil }
        if let data = entry.data { return data }
        return entry.file.flatMap { try? Data(contentsOf: $0) }
    }

    /// Dove si trova la foto (per i test): true su disco, false in RAM, nil se non c'è.
    func isOnDisk(_ id: String) -> Bool? {
        lock.lock()
        defer { lock.unlock() }
        return entries.first(where: { $0.photo.id == id }).map { $0.file != nil }
    }

    @discardableResult
    public func approve(_ id: String) -> Bool {
        mutate {
            guard let entry = entries.first(where: { $0.photo.id == id }) else { return false }
            if entry.photo.approved { return true }
            let approved = GuestPhoto(
                id: entry.photo.id,
                sender: entry.photo.sender,
                receivedAt: entry.photo.receivedAt,
                sizeBytes: entry.photo.sizeBytes,
                approved: true
            )
            if let file = entry.file {
                let renamed = directory.appendingPathComponent(Self.fileName(approved))
                do {
                    try fileManager.moveItem(at: file, to: renamed)
                    entry.file = renamed
                } catch {
                    // Senza il nuovo nome, dopo un riavvio la foto tornerebbe da approvare: meglio in RAM.
                    guard let data = try? Data(contentsOf: file) else { return false }
                    entry.data = data
                    memoryUsed += entry.photo.sizeBytes
                    try? fileManager.removeItem(at: file)
                    diskUsed -= entry.photo.sizeBytes
                    entry.file = nil
                }
            }
            entry.photo = approved
            fitInMemory()
            return true
        }
    }

    public func remove(_ id: String) {
        mutate {
            if let entry = entries.first(where: { $0.photo.id == id }) { drop(entry) }
        }
    }

    /// Sposta tutte le foto in RAM nella cartella di riserva, finché c'è posto.
    public func spillAll() {
        mutate {
            for entry in entries where entry.data != nil {
                _ = spill(entry, evictOthers: false)
            }
        }
    }

    /// Cancella tutte le foto, in RAM e su disco, compresi file rimasti da sessioni precedenti.
    public func clear() {
        mutate { removeEverything() }
    }

    /// Riprende le foto rimaste nella cartella di riserva, se la più recente è arrivata da meno di
    /// `maxAge`; altrimenti la festa è finita da tempo e la cartella viene svuotata.
    @discardableResult
    public func restore(maxAge: TimeInterval) -> Int {
        mutate {
            entries = []
            memoryUsed = 0
            diskUsed = 0
            let files = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            var restored: [(GuestPhoto, URL)] = []
            for file in files {
                if let photo = Self.parse(file) {
                    restored.append((photo, file))
                } else {
                    try? fileManager.removeItem(at: file)
                }
            }
            restored.sort { $0.0.receivedAt < $1.0.receivedAt }
            guard let newest = restored.last?.0.receivedAt, now().timeIntervalSince(newest) <= maxAge else {
                removeEverything()
                return 0
            }
            for (photo, file) in restored {
                entries.append(Entry(photo: photo, data: nil, file: file))
                diskUsed += photo.sizeBytes
            }
            while entries.count > maxPhotos { drop(victim(entries)) }
            while diskUsed > diskBudget(), !entries.isEmpty { drop(victim(entries)) }
            return entries.count
        }
    }

    // MARK: - Interni (chiamati con il lock acquisito)

    /// Esegue una modifica con il lock e poi avvisa chi osserva l'archivio, fuori dal lock.
    private func mutate<T>(_ body: () -> T) -> T {
        lock.lock()
        let result = body()
        let snapshot = entries.map(\.photo)
        let handler = onChange
        lock.unlock()
        handler?(snapshot)
        return result
    }

    private func removeEverything() {
        entries = []
        memoryUsed = 0
        diskUsed = 0
        let files = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for file in files { try? fileManager.removeItem(at: file) }
    }

    /// Prima da sacrificare: la foto in attesa più vecchia, altrimenti la più vecchia in assoluto.
    private func victim(_ candidates: [Entry]) -> Entry {
        candidates.first { !$0.photo.approved } ?? candidates[0]
    }

    private func fitInMemory(allowApprovedEviction: Bool = true) {
        while memoryUsed > memoryBudget {
            let inMemory = entries.filter { $0.data != nil }
            guard let oldest = inMemory.first else { return }
            if !spill(oldest, evictOthers: true, allowApprovedEviction: allowApprovedEviction) { drop(victim(inMemory)) }
        }
    }

    /// Scrive la foto su disco e la toglie dalla RAM. Con `evictOthers` fa posto cancellando le foto
    /// più vecchie già su disco: prima quelle in attesa, e quelle approvate solo con
    /// `allowApprovedEviction` e se anche la foto da spostare è approvata. Restituisce false se non
    /// c'è posto o la scrittura non riesce.
    private func spill(_ entry: Entry, evictOthers: Bool, allowApprovedEviction: Bool = true) -> Bool {
        guard let data = entry.data else { return true }
        let size = data.count
        if size > diskBudget() { return false }
        while diskUsed + size > diskBudget() {
            guard evictOthers else { return false }
            let onDisk = entries.filter { $0.file != nil && $0 !== entry }
            if let pending = onDisk.first(where: { !$0.photo.approved }) {
                drop(pending)
            } else if allowApprovedEviction, entry.photo.approved, let oldest = onDisk.first {
                drop(oldest)
            } else {
                return false
            }
        }
        let target = directory.appendingPathComponent(Self.fileName(entry.photo))
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            // Scrittura atomica: un file a metà non viene mai scambiato per una foto.
            try data.write(to: target, options: .atomic)
        } catch {
            try? fileManager.removeItem(at: target)
            return false
        }
        entry.file = target
        entry.data = nil
        memoryUsed -= size
        diskUsed += size
        return true
    }

    private func drop(_ entry: Entry) {
        entries.removeAll { $0 === entry }
        if entry.data != nil { memoryUsed -= entry.photo.sizeBytes }
        if let file = entry.file {
            try? fileManager.removeItem(at: file)
            diskUsed -= entry.photo.sizeBytes
        }
        entry.data = nil
        entry.file = nil
    }

    /// Limite della cartella: il minore tra il massimo fisso e una quota dello spazio libero.
    private func diskBudget() -> Int {
        let free = max(freeSpace(), 0)
        return min(maxDiskBytes, Int(Double(free + diskUsed) * freeShare))
    }

    // MARK: - Nomi dei file

    private static let fileExtension = "img"
    private static let idLength = 16

    private static func newID() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<(idLength / 2)).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }

    static func fileName(_ photo: GuestPhoto) -> String {
        let millis = Int64((photo.receivedAt.timeIntervalSince1970 * 1000).rounded())
        let sender = photo.sender.map { Data($0.utf8).base64URLEncoded() } ?? ""
        return "\(millis)_\(photo.id)_\(photo.approved ? "a" : "p")_\(sender).\(fileExtension)"
    }

    static func parse(_ file: URL) -> GuestPhoto? {
        guard file.pathExtension == fileExtension else { return nil }
        // Il nome del mittente (ultimo campo) può contenere '_': si divide in quattro parti al massimo.
        let parts = file.deletingPathExtension().lastPathComponent.split(separator: "_", maxSplits: 3, omittingEmptySubsequences: false)
        guard parts.count == 4, let millis = Int64(parts[0]) else { return nil }
        let id = String(parts[1])
        guard id.count == idLength, id.allSatisfy({ $0.isASCII && $0.isHexDigit && !$0.isUppercase }) else { return nil }
        let approved: Bool
        switch parts[2] {
        case "a": approved = true
        case "p": approved = false
        default: return nil
        }
        var sender: String?
        if !parts[3].isEmpty {
            guard let data = Data(base64URLEncoded: String(parts[3])), let text = String(data: data, encoding: .utf8) else { return nil }
            sender = GuestSender.clean(text)
        }
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0 else { return nil }
        return GuestPhoto(
            id: id,
            sender: sender,
            receivedAt: Date(timeIntervalSince1970: TimeInterval(millis) / 1000),
            sizeBytes: size,
            approved: approved
        )
    }

    /// Spazio libero sul volume della cartella, misurato sulla prima cartella esistente del percorso.
    private static func availableCapacity(of directory: URL) -> Int {
        var probe = directory
        while !FileManager.default.fileExists(atPath: probe.path), probe.pathComponents.count > 1 {
            probe = probe.deletingLastPathComponent()
        }
        #if os(tvOS) || os(watchOS)
        // Su tvOS la capacità "per uso importante" non esiste.
        return (try? probe.resourceValues(forKeys: [.volumeAvailableCapacityKey]))?.volumeAvailableCapacity ?? 0
        #else
        let values = try? probe.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        if let important = values?.volumeAvailableCapacityForImportantUsage { return Int(clamping: important) }
        return values?.volumeAvailableCapacity ?? 0
        #endif
    }
}

extension Data {
    /// Base64 senza padding e con l'alfabeto sicuro per i nomi dei file e gli URL.
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    init?(base64URLEncoded text: String) {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        self.init(base64Encoded: base64)
    }
}
