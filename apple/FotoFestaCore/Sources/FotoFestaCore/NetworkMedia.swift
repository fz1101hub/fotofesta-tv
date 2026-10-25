import Foundation

/// Cartella di rete scelta: basta a ritrovarla anche se il server cambia indirizzo IP.
public struct NetworkFolder: Codable, Hashable, Sendable {
    public let udn: String
    public let location: URL
    public let containerID: String
    public let title: String

    public init(udn: String, location: URL, containerID: String, title: String) {
        self.udn = udn
        self.location = location
        self.containerID = containerID
        self.title = title
    }
}

public enum NetworkMediaError: Error, Equatable {
    /// Il server non risponde o non è nella rete di casa.
    case unreachable
}

/// Cartelle dei server multimediali (DLNA) della rete di casa: NAS, PC, router, Plex, Jellyfin.
/// Le richieste vanno solo a indirizzi della rete di casa, in chiaro come vuole DLNA.
public actor NetworkMedia {
    public static let root = "0"
    static let pageSize = 500
    static let maxChildren = 5_000
    public static let maxPhotos = 10_000
    static let maxFolders = 2_000

    /// Indirizzi delle descrizioni dei server più diffusi, provati quando si scrive l'indirizzo di un
    /// NAS: servono se la domanda SSDP diretta non riceve risposta.
    static let knownDescriptions = [
        "8200/rootDesc.xml", // MiniDLNA / ReadyMedia (molti NAS, OpenMediaVault, router)
        "50001/desc/device.xml", // Synology Media Server
        "32469/DeviceDescription.xml", // Plex
        "9000/TMSDeviceDescription.xml", // Twonky
        "49000/MediaServerDevDesc.xml", // FRITZ!Box
    ]

    private let http: HTTPClient
    private let imageMimeTypes: Set<String>
    private let screen: ScreenSize?
    private let search: @Sendable (_ multicast: Bool, _ hosts: [String]) async -> [SSDP.Answer]
    private var cache: [String: MediaServer] = [:]

    public init(
        http: HTTPClient,
        imageMimeTypes: Set<String> = NetworkMedia.defaultImageMimeTypes,
        screen: ScreenSize? = nil,
        search: @escaping @Sendable (_ multicast: Bool, _ hosts: [String]) async -> [SSDP.Answer] = { multicast, hosts in
            await SSDP.search(multicast: multicast, hosts: hosts)
        }
    ) {
        self.http = http
        self.imageMimeTypes = imageMimeTypes
        self.screen = screen
        self.search = search
    }

    /// Foto che tvOS sa decodificare (i RAW e i TIFF dei NAS restano fuori).
    public static let defaultImageMimeTypes: Set<String> = [
        "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/gif", "image/heic", "image/heif", "image/webp",
    ]

    /// Server della rete con il servizio ContentDirectory, per nome (domanda in multicast).
    public func discover() async -> [MediaServer] {
        await describeAll(search(true, []))
    }

    /// Server a un indirizzo scritto dall'utente: domanda SSDP diretta e, se tace, le porte dei server
    /// più diffusi.
    public func servers(at host: String) async -> [MediaServer] {
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard LANAddress.isLocal(host) else { return [] }
        var found = await describeAll(search(false, [host]))
        if found.isEmpty {
            let name = host.contains(":") ? "[\(host)]" : host
            let locations = Self.knownDescriptions.compactMap { URL(string: "http://\(name):\($0)") }
            found = await withTaskGroup(of: MediaServer?.self) { group in
                for location in locations {
                    group.addTask { [self] in try? await self.describe(location) }
                }
                var result: [MediaServer] = []
                for await server in group {
                    if let server { result.append(server) }
                }
                return result
            }
            found = unique(found)
        }
        return found
    }

    /// Server già trovato in questa sessione.
    public func known(_ udn: String) -> MediaServer? { cache[udn] }

    /// Il server non risponde più all'indirizzo noto (ha cambiato IP): la prossima `find` lo ricerca.
    public func forget(_ udn: String) { cache[udn] = nil }

    /// Server della cartella salvata: prima all'indirizzo noto, poi (se ha cambiato IP) cercandolo per UDN.
    public func find(udn: String, location: URL) async -> MediaServer? {
        if let server = cache[udn] { return server }
        if let server = try? await describe(location), server.udn == udn {
            cache[udn] = server
            return server
        }
        if let host = location.host, LANAddress.isLocal(host),
           let server = await describeAll(search(false, [host])).first(where: { $0.udn == udn }) {
            return server
        }
        return await discover().first { $0.udn == udn }
    }

    private func describeAll(_ answers: [SSDP.Answer]) async -> [MediaServer] {
        var locations: [URL] = []
        for answer in answers where !locations.contains(answer.location) && LANAddress.isLocal(answer.location) {
            locations.append(answer.location)
        }
        let found = await withTaskGroup(of: MediaServer?.self) { group in
            for location in locations {
                group.addTask { [self] in try? await self.describe(location) }
            }
            var result: [MediaServer] = []
            for await server in group {
                if let server { result.append(server) }
            }
            return result
        }
        return unique(found)
    }

    private func unique(_ found: [MediaServer]) -> [MediaServer] {
        var byUDN: [String: MediaServer] = [:]
        for server in found where byUDN[server.udn] == nil { byUDN[server.udn] = server }
        for server in byUDN.values { cache[server.udn] = server }
        return byUDN.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func describe(_ location: URL) async throws -> MediaServer? {
        guard LANAddress.isLocal(location) else { return nil }
        var request = URLRequest(url: location, timeoutInterval: 4)
        request.httpMethod = "GET"
        let (data, response) = try await http.send(request)
        guard (200..<300).contains(response.statusCode) else { return nil }
        return try UpnpXML.parseDevice(data, location: location)
    }

    /// Sottocartelle e file di una cartella ("0" è la cartella principale del server).
    public func list(_ server: MediaServer, containerID: String) async throws -> BrowsePage {
        var containers: [MediaContainer] = []
        var files: [MediaFile] = []
        var start = 0
        var total = 0
        repeat {
            let page = try await browse(server, containerID: containerID, start: start)
            containers += page.containers
            files += page.files
            // Alcuni server dichiarano TotalMatches 0: ci si ferma quando una pagina arriva vuota.
            total = page.total
            start += page.returned
            if page.returned <= 0 { break }
        } while (total <= 0 || start < total) && start < Self.maxChildren
        return BrowsePage(containers: containers, files: files, returned: start, total: total)
    }

    /// Foto e video della cartella e delle sottocartelle, al massimo `limit`, nell'ordine del server.
    /// Una sottocartella illeggibile si salta; la cartella scelta deve leggersi.
    public func scanPhotos(_ server: MediaServer, containerID: String, limit: Int = NetworkMedia.maxPhotos) async throws -> [Photo] {
        var queue = [containerID]
        var visited = Set<String>()
        var photos: [Photo] = []
        var seen = Set<URL>()
        var first = true
        while !queue.isEmpty, photos.count < limit, visited.count < Self.maxFolders {
            try Task.checkCancellation()
            let current = queue.removeFirst()
            guard visited.insert(current).inserted else { continue }
            let page: BrowsePage
            do {
                page = try await list(server, containerID: current)
            } catch {
                if first || error is CancellationError { throw error }
                continue
            }
            first = false
            for file in page.files where photos.count < limit && seen.insert(file.url).inserted {
                photos.append(Self.photo(file, server: server))
            }
            // Sui NAS con molta musica gli album e gli artisti sono migliaia: senza foto, si saltano.
            queue += page.containers.filter { !$0.musicOrVideo }.map(\.id)
        }
        return photos
    }

    private func browse(_ server: MediaServer, containerID: String, start: Int) async throws -> BrowsePage {
        guard LANAddress.isLocal(server.controlURL) else { throw NetworkMediaError.unreachable }
        var request = URLRequest(url: server.controlURL, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        request.setValue(UpnpXML.browseAction, forHTTPHeaderField: "SOAPAction")
        request.httpBody = Data(UpnpXML.browseRequest(objectID: containerID, start: start, count: Self.pageSize).utf8)
        let (data, response) = try await http.send(request)
        // Gli errori SOAP arrivano con 500 e un Fault nel corpo, che parseBrowseResponse spiega.
        guard (200..<300).contains(response.statusCode) || response.statusCode == 500 else { throw NetworkMediaError.unreachable }
        let page = try UpnpXML.parseBrowseResponse(data, baseURL: server.controlURL, accepts: acceptsMedia, screen: screen)
        // Solo file raggiungibili nella rete di casa.
        return BrowsePage(containers: page.containers, files: page.files.filter { LANAddress.isLocal($0.url) }, returned: page.returned, total: page.total)
    }

    private nonisolated func acceptsMedia(_ mime: String) -> Bool {
        imageMimeTypes.contains(mime) || mime.hasPrefix("video/")
    }

    static func photo(_ file: MediaFile, server: MediaServer) -> Photo {
        let title = (file.title as NSString).deletingPathExtension
        return Photo(
            id: "net:\(server.udn):\(file.id)",
            title: title.isEmpty ? file.title : title,
            source: .network(url: file.url.absoluteString),
            isVideo: file.mimeType.hasPrefix("video/")
        )
    }
}
