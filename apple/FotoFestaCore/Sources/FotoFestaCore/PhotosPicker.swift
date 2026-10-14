import Foundation

/// Fornisce un access token valido a ogni richiesta, rinnovandolo se sta per scadere.
public typealias TokenProvider = @Sendable () async throws -> String

/// Google Photos Picker API e archivio privato delle foto scelte (stessa logica dell'app Android).
///
/// Su tvOS la cartella Caches può essere svuotata dal sistema quando l'app non è in uso:
/// `cachedPhotos()` restituisce solo i file ancora presenti e l'app propone di importarli di nuovo.
public final class PhotosPicker: @unchecked Sendable {
    public struct Session: Equatable, Sendable {
        public let id: String
        public let pickerURI: String
        public let mediaItemsSet: Bool
        public let pollInterval: TimeInterval
        /// Tempo residuo indicato da Google nell'ultima risposta.
        public let timeout: TimeInterval?
    }

    public struct PickedMedia: Equatable, Sendable {
        public let id: String
        public let filename: String
        public let baseURL: String
        public let mimeType: String
    }

    public static let defaultAPIBase = URL(string: "https://photospicker.googleapis.com/v1/")!
    static let cacheFolder = "google_photos"
    static let manifestName = "index.json"
    private static let parallelDownloads = 4
    private static let maximumImageBytes: Int64 = 24 * 1024 * 1024
    private static let defaultSessionTimeout: TimeInterval = 900

    private let http: HTTPClient
    private let cacheRoot: URL
    private let apiBase: URL
    private let minimumFreeBytes: Int64
    private let freeBytes: @Sendable () -> Int64?
    private let sleep: @Sendable (TimeInterval) async throws -> Void
    private let fileManager = FileManager.default

    public init(
        http: HTTPClient,
        cacheRoot: URL,
        apiBase: URL = PhotosPicker.defaultAPIBase,
        minimumFreeBytes: Int64 = 300 * 1024 * 1024,
        freeBytes: (@Sendable () -> Int64?)? = nil,
        sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    ) {
        self.http = http
        self.cacheRoot = cacheRoot
        self.apiBase = apiBase
        self.minimumFreeBytes = minimumFreeBytes
        self.freeBytes = freeBytes ?? {
            let values = try? cacheRoot.resourceValues(forKeys: [.volumeAvailableCapacityKey])
            return values?.volumeAvailableCapacity.map(Int64.init)
        }
        self.sleep = sleep
    }

    private var cacheDirectory: URL { cacheRoot.appendingPathComponent(Self.cacheFolder, isDirectory: true) }

    // MARK: - Sessione Picker

    public func createSession(token: String) async throws -> Session {
        var components = URLComponents(url: apiBase.appendingPathComponent("sessions"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "requestId", value: UUID().uuidString.lowercased())]
        var request = authorized(components.url!, token: token)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("{}".utf8)
        return try await http.json(SessionResponse.self, for: request).session
    }

    public func awaitSelection(token: TokenProvider, session initial: Session) async throws -> Session {
        var current = initial
        var deadline = Date().addingTimeInterval(max(current.timeout ?? Self.defaultSessionTimeout, 60))
        while !current.mediaItemsSet {
            guard Date() < deadline else { throw FotoFestaError.selectionExpired }
            try await sleep(min(max(current.pollInterval, 2), 30))
            current = try await session(token: token, id: current.id)
            // timeoutIn è relativo alla risposta: la scadenza si ricalcola a ogni polling.
            if let timeout = current.timeout { deadline = Date().addingTimeInterval(max(timeout, 60)) }
        }
        return current
    }

    public func listPickedMedia(token: TokenProvider, sessionID: String) async throws -> [PickedMedia] {
        var output: [PickedMedia] = []
        var pageToken: String?
        repeat {
            var components = URLComponents(url: apiBase.appendingPathComponent("mediaItems"), resolvingAgainstBaseURL: false)!
            var query = [("sessionId", sessionID), ("pageSize", "100")]
            if let pageToken { query.append(("pageToken", pageToken)) }
            // Codifica rigorosa: URLComponents lascerebbe "+" (letto come spazio) e altri separatori.
            components.percentEncodedQueryItems = query.map { name, value in
                URLQueryItem(name: name, value: value.addingPercentEncoding(withAllowedCharacters: .urlStrictValue))
            }
            let accessToken = try await token()
            let page = try await http.json(MediaItemsResponse.self, for: authorized(components.url!, token: accessToken))
            for item in page.mediaItems ?? [] {
                guard let file = item.mediaFile, let mime = file.mimeType, mime.hasPrefix("image/"),
                      let baseURL = file.baseUrl, !baseURL.isEmpty else { continue }
                output.append(PickedMedia(
                    id: item.id.flatMap { $0.isEmpty ? nil : $0 } ?? "photo-\(output.count)",
                    filename: file.filename.flatMap { $0.isEmpty ? nil : $0 } ?? "Photo \(output.count + 1)",
                    baseURL: baseURL,
                    mimeType: mime
                ))
            }
            pageToken = page.nextPageToken.flatMap { $0.isEmpty ? nil : $0 }
        } while pageToken != nil
        return output
    }

    /// Elimina la sessione lato Google. Best effort: un errore qui non invalida le foto scaricate.
    public func deleteSession(token: TokenProvider, sessionID: String) async {
        guard let accessToken = try? await token() else { return }
        var request = authorized(apiBase.appendingPathComponent("sessions").appendingPathComponent(sessionID), token: accessToken)
        request.httpMethod = "DELETE"
        _ = try? await http.send(request)
    }

    // MARK: - Download e cache

    /// Scarica in una cartella temporanea e sostituisce la cache precedente solo a download concluso.
    /// In caso di errore o annullamento la raccolta in uso resta intatta.
    public func download(
        token: @escaping TokenProvider,
        items: [PickedMedia],
        progress: @escaping @Sendable (Int, Int) -> Void
    ) async throws -> [Photo] {
        let staging = cacheRoot.appendingPathComponent("\(Self.cacheFolder).staging-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        var committed = false
        defer { if !committed { try? fileManager.removeItem(at: staging) } }

        var fileNames = [Int: String]()
        try await withThrowingTaskGroup(of: (Int, String?).self) { group in
            var pending = items.enumerated().makeIterator()
            func enqueueNext() {
                guard !Task.isCancelled, let (index, item) = pending.next() else { return }
                group.addTask {
                    try Task.checkCancellation()
                    return (index, try await self.downloadOne(token: token, item: item, index: index, into: staging))
                }
            }
            for _ in 0..<Self.parallelDownloads { enqueueNext() }
            var completed = 0
            while let (index, fileName) = try await group.next() {
                try Task.checkCancellation()
                if let fileName { fileNames[index] = fileName }
                completed += 1
                progress(completed, items.count)
                enqueueNext()
            }
        }
        try Task.checkCancellation()

        let downloaded = items.indices.compactMap { index in fileNames[index].map { (items[index], $0) } }
        guard !downloaded.isEmpty else { return [] }
        let photos = downloaded.map { item, fileName in
            Photo(id: "google:\(item.id)", title: item.filename, source: .file(path: cacheDirectory.appendingPathComponent(fileName).path))
        }
        try writeManifest(photos, in: staging)

        let previous = cacheRoot.appendingPathComponent("\(Self.cacheFolder).old-\(UUID().uuidString)", isDirectory: true)
        let hadPrevious = fileManager.fileExists(atPath: cacheDirectory.path)
        do {
            if hadPrevious { try fileManager.moveItem(at: cacheDirectory, to: previous) }
        } catch {
            throw FotoFestaError.cacheWrite
        }
        do {
            try fileManager.moveItem(at: staging, to: cacheDirectory)
        } catch {
            if hadPrevious { try? fileManager.moveItem(at: previous, to: cacheDirectory) }
            throw FotoFestaError.cacheWrite
        }
        committed = true
        try? fileManager.removeItem(at: previous)
        return photos
    }

    /// Foto in cache, con titoli e ordine originali. Non usa la rete.
    public func cachedPhotos() -> [Photo] {
        recoverInterruptedSwap()
        let directory = cacheDirectory
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(Self.manifestName)),
              let entries = try? JSONDecoder().decode([ManifestEntry].self, from: data) else { return [] }
        return entries.compactMap { entry in
            // Il nome deve restare dentro la cache: niente percorsi relativi o assoluti.
            guard !entry.file.contains("/"), entry.file != "..", entry.file != "." else { return nil }
            let url = directory.appendingPathComponent(entry.file)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            guard size > 0 else { return nil }
            return Photo(id: entry.id, title: entry.title, source: .file(path: url.path))
        }
    }

    public func clearCache() {
        let contents = (try? fileManager.contentsOfDirectory(at: cacheRoot, includingPropertiesForKeys: nil)) ?? []
        for url in contents where url.lastPathComponent == Self.cacheFolder || url.lastPathComponent.hasPrefix("\(Self.cacheFolder).") {
            try? fileManager.removeItem(at: url)
        }
    }

    // MARK: - Interni

    // Un'interruzione fra le due moveItem puo lasciare l'unica copia valida in old-*.
    private func recoverInterruptedSwap() {
        let entries = (try? fileManager.contentsOfDirectory(at: cacheRoot, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        let backups = entries.filter { $0.lastPathComponent.hasPrefix("\(Self.cacheFolder).old-") }
            .sorted {
                let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                return a > b
            }
        if usableCache(at: cacheDirectory) {
            for backup in backups { try? fileManager.removeItem(at: backup) }
            return
        }
        guard let previous = backups.first(where: { usableCache(at: $0) }) else { return }
        let displaced = cacheRoot.appendingPathComponent("\(Self.cacheFolder).corrupt-\(UUID().uuidString)")
        let hadActive = fileManager.fileExists(atPath: cacheDirectory.path)
        do {
            if hadActive { try fileManager.moveItem(at: cacheDirectory, to: displaced) }
            try fileManager.moveItem(at: previous, to: cacheDirectory)
            if hadActive { try? fileManager.removeItem(at: displaced) }
        } catch {
            if hadActive && !fileManager.fileExists(atPath: cacheDirectory.path) {
                try? fileManager.moveItem(at: displaced, to: cacheDirectory)
            }
        }
    }

    private func usableCache(at directory: URL) -> Bool {
        let entries = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey])) ?? []
        return entries.contains {
            $0.lastPathComponent != Self.manifestName &&
            ((try? $0.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) > 0
        }
    }

    private func downloadOne(token: TokenProvider, item: PickedMedia, index: Int, into directory: URL) async throws -> String? {
        if let free = freeBytes(), free <= minimumFreeBytes { return nil }
        let fileExtension: String
        switch item.mimeType {
        case "image/png": fileExtension = "png"
        case "image/webp": fileExtension = "webp"
        case "image/gif": fileExtension = "gif"
        default: fileExtension = "jpg"
        }
        let fileName = String(format: "%05d.%@", index, fileExtension)
        guard let url = URL(string: item.baseURL + "=w3840-h2160") else { return nil }
        let target = directory.appendingPathComponent(fileName)
        do {
            try Task.checkCancellation()
            let accessToken = try await token()
            let response = try await http.download(authorized(url, token: accessToken),
                                                   to: target, maxBytes: Self.maximumImageBytes)
            try Task.checkCancellation()
            let mime = response.value(forHTTPHeaderField: "Content-Type")?.lowercased()
            guard (200..<300).contains(response.statusCode),
                  mime == nil || mime!.hasPrefix("image/"),
                  ((try? target.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) > 0 else {
                try? fileManager.removeItem(at: target)
                return nil
            }
            return fileName
        } catch is CancellationError {
            try? fileManager.removeItem(at: target)
            throw CancellationError()
        } catch {
            try? fileManager.removeItem(at: target)
            return nil
        }
    }

    private func session(token: TokenProvider, id: String) async throws -> Session {
        let url = apiBase.appendingPathComponent("sessions").appendingPathComponent(id)
        let accessToken = try await token()
        return try await http.json(SessionResponse.self, for: authorized(url, token: accessToken)).session
    }

    private func authorized(_ url: URL, token: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func writeManifest(_ photos: [Photo], in directory: URL) throws {
        let entries = photos.map { photo -> ManifestEntry in
            guard case let .file(path) = photo.source else { fatalError("Solo foto su file") }
            return ManifestEntry(id: photo.id, title: photo.title, file: URL(fileURLWithPath: path).lastPathComponent)
        }
        do {
            try JSONEncoder().encode(entries).write(to: directory.appendingPathComponent(Self.manifestName), options: .atomic)
        } catch {
            throw FotoFestaError.cacheWrite
        }
    }

    private struct ManifestEntry: Codable {
        let id: String
        let title: String
        let file: String
    }

    private struct SessionResponse: Decodable {
        struct PollingConfig: Decodable {
            let pollInterval: String?
            let timeoutIn: String?
        }

        let id: String
        let pickerUri: String
        let mediaItemsSet: Bool?
        let pollingConfig: PollingConfig?

        var session: Session {
            Session(
                id: id,
                pickerURI: pickerUri,
                mediaItemsSet: mediaItemsSet ?? false,
                pollInterval: parseProtoDuration(pollingConfig?.pollInterval) ?? 5,
                timeout: parseProtoDuration(pollingConfig?.timeoutIn)
            )
        }
    }

    private struct MediaItemsResponse: Decodable {
        struct Item: Decodable {
            struct MediaFile: Decodable {
                let baseUrl: String?
                let mimeType: String?
                let filename: String?
            }

            let id: String?
            let mediaFile: MediaFile?
        }

        let mediaItems: [Item]?
        let nextPageToken: String?
    }
}

extension CharacterSet {
    /// Caratteri non riservati RFC 3986: tutto il resto viene codificato.
    static let urlStrictValue: CharacterSet = {
        var set = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        set.insert(charactersIn: "-._~")
        return set
    }()
}
