import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

/// Server multimediale della rete di casa con il servizio ContentDirectory, che elenca cartelle e file.
public struct MediaServer: Hashable, Sendable {
    public let udn: String
    public let name: String
    /// Indirizzo della descrizione del dispositivo (LOCATION di SSDP).
    public let location: URL
    /// Indirizzo a cui mandare le richieste Browse.
    public let controlURL: URL

    public init(udn: String, name: String, location: URL, controlURL: URL) {
        self.udn = udn
        self.name = name
        self.location = location
        self.controlURL = controlURL
    }
}

/// Cartella di un server. `musicOrVideo`: album, artisti, generi e playlist, che non contengono foto.
public struct MediaContainer: Hashable, Sendable {
    public let id: String
    public let title: String
    public let musicOrVideo: Bool

    public init(id: String, title: String, musicOrVideo: Bool = false) {
        self.id = id
        self.title = title
        self.musicOrVideo = musicOrVideo
    }
}

/// File di un server, con l'indirizzo da cui scaricarlo e il tipo MIME.
public struct MediaFile: Hashable, Sendable {
    public let id: String
    public let title: String
    public let url: URL
    public let mimeType: String

    public init(id: String, title: String, url: URL, mimeType: String) {
        self.id = id
        self.title = title
        self.url = url
        self.mimeType = mimeType
    }
}

/// Lato lungo e lato corto dello schermo in pixel, per scegliere la versione di una foto.
public struct ScreenSize: Hashable, Sendable {
    public let longEdge: Int
    public let shortEdge: Int

    public init(longEdge: Int, shortEdge: Int) {
        self.longEdge = longEdge
        self.shortEdge = shortEdge
    }
}

/// Contenuto di una pagina di Browse: cartelle, file e quanti elementi ha in tutto la cartella.
public struct BrowsePage: Sendable {
    public let containers: [MediaContainer]
    public let files: [MediaFile]
    public let returned: Int
    public let total: Int
}

public enum UpnpError: Error, Equatable {
    case invalidXML
    /// Il server ha rifiutato la richiesta (errore SOAP con il codice UPnP, se c'è).
    case fault(String?)
    case missingResult
}

/// XML di UPnP: descrizione del dispositivo, richiesta e risposta Browse, elenco DIDL-Lite.
public enum UpnpXML {
    /// Lato massimo plausibile di una foto dichiarata da un server: oltre, il valore è un errore.
    static let maxDeclaredSide = 100_000

    public static let browseAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
    private static let contentDirectoryPrefix = "urn:schemas-upnp-org:service:ContentDirectory:"

    /// Classi UPnP delle cartelle senza foto (gli album di foto sono object.container.album.photoAlbum).
    private static let notPhotoContainers = [
        "object.container.album.musicAlbum",
        "object.container.person",
        "object.container.genre",
        "object.container.playlistContainer",
    ]

    /// Descrizione del dispositivo: nome, UDN e indirizzo del ContentDirectory; nil se non ce l'ha.
    public static func parseDevice(_ data: Data, location: URL) throws -> MediaServer? {
        let root = try XMLTree.parse(data)
        guard let device = root.descendants("device").first,
              let udn = device.child("UDN")?.text, !udn.isEmpty else { return nil }
        let name = device.child("friendlyName")?.text ?? ""
        // Il servizio può stare in un dispositivo incluso (deviceList), come nei NAS con più funzioni.
        guard let service = root.descendants("service").first(where: {
            $0.child("serviceType")?.text.hasPrefix(contentDirectoryPrefix) == true
        }), let control = service.child("controlURL")?.text, !control.isEmpty else { return nil }
        let baseText = root.child("URLBase")?.text ?? ""
        let base = baseText.isEmpty ? location : URL(string: baseText) ?? location
        guard let controlURL = resolve(control, against: base) else { return nil }
        return MediaServer(udn: udn, name: name.isEmpty ? udn : name, location: location, controlURL: controlURL)
    }

    /// Busta SOAP di Browse per i figli diretti di `objectID`.
    public static func browseRequest(objectID: String, start: Int, count: Int) -> String {
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body>"
            + "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
            + "<ObjectID>\(escape(objectID))</ObjectID>"
            + "<BrowseFlag>BrowseDirectChildren</BrowseFlag>"
            + "<Filter>*</Filter>"
            + "<StartingIndex>\(start)</StartingIndex>"
            + "<RequestedCount>\(count)</RequestedCount>"
            + "<SortCriteria></SortCriteria>"
            + "</u:Browse>"
            + "</s:Body>"
            + "</s:Envelope>"
    }

    /// Risposta di Browse: l'elenco DIDL-Lite dentro Result. Tiene i file con un tipo MIME accettato da
    /// `accepts`; con `screen` le foto usano la versione più leggera che copre lo schermo.
    public static func parseBrowseResponse(
        _ data: Data,
        baseURL: URL,
        accepts: (String) -> Bool,
        screen: ScreenSize? = nil
    ) throws -> BrowsePage {
        let root = try XMLTree.parse(data)
        if let fault = root.descendants("Fault").first {
            throw UpnpError.fault(fault.descendants("errorCode").first?.text)
        }
        guard let result = root.descendants("Result").first?.text else { throw UpnpError.missingResult }
        let returned = root.descendants("NumberReturned").first.flatMap { Int($0.text) } ?? 0
        let total = root.descendants("TotalMatches").first.flatMap { Int($0.text) } ?? 0
        if result.isEmpty { return BrowsePage(containers: [], files: [], returned: returned, total: total) }
        let didl = try XMLTree.parse(Data(result.utf8))
        let containers = didl.children("container").compactMap { container -> MediaContainer? in
            guard let id = container.attributes["id"], !id.isEmpty else { return nil }
            let upnpClass = container.child("class")?.text ?? ""
            let title = container.child("title")?.text ?? ""
            return MediaContainer(
                id: id,
                title: title.isEmpty ? id : title,
                musicOrVideo: notPhotoContainers.contains { upnpClass.hasPrefix($0) }
            )
        }
        let files = didl.children("item").compactMap { item -> MediaFile? in
            guard let id = item.attributes["id"], !id.isEmpty else { return nil }
            // La classe dice cos'è: un brano o un video può avere fra le versioni la copertina in JPEG,
            // che non va presa per una foto.
            let upnpClass = item.child("class")?.text ?? ""
            let kind: (String) -> Bool
            if upnpClass.hasPrefix("object.item.audioItem") {
                return nil
            } else if upnpClass.hasPrefix("object.item.videoItem") {
                kind = { $0.hasPrefix("video/") }
            } else if upnpClass.hasPrefix("object.item.imageItem") {
                kind = { $0.hasPrefix("image/") }
            } else {
                kind = { _ in true }
            }
            guard let resource = bestResource(item, screen: screen, accepts: { accepts($0) && kind($0) }),
                  let url = resolve(resource.url, against: baseURL) else { return nil }
            let title = item.child("title")?.text ?? ""
            return MediaFile(id: id, title: title.isEmpty ? id : title, url: url, mimeType: resource.mime)
        }
        return BrowsePage(containers: containers, files: files, returned: returned, total: total)
    }

    /// Fra le versioni di un file sceglie la più grande con un tipo accettato; le miniature DLNA
    /// (*_TN, JPEG_SM) vanno per ultime e senza risoluzione una versione conta come originale. Per le
    /// foto, con `screen`, vince la versione più piccola che copre lo schermo.
    private static func bestResource(_ item: XMLTree, screen: ScreenSize?, accepts: (String) -> Bool) -> (url: String, mime: String)? {
        struct Candidate {
            let url: String
            let mime: String
            let thumbnail: Bool
            let pixels: Int64
            let order: Int
            let longEdge: Int
            let shortEdge: Int
        }
        var candidates: [Candidate] = []
        for (index, res) in item.children("res").enumerated() {
            let info = (res.attributes["protocolInfo"] ?? "").split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            guard info.count >= 3, info[0].lowercased() == "http-get" else { continue }
            let mime = info[2].split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
            let url = res.text
            guard !url.isEmpty, accepts(mime) else { continue }
            let profile = info.count > 3 ? info[3].uppercased() : ""
            let thumbnail = profile.contains("_TN") || profile.contains("JPEG_SM")
            var width = 0
            var height = 0
            let parts = (res.attributes["resolution"] ?? "").lowercased().split(separator: "x")
            if parts.count == 2 {
                width = Int(parts[0].trimmingCharacters(in: .whitespaces)) ?? 0
                height = Int(parts[1].trimmingCharacters(in: .whitespaces)) ?? 0
            }
            // Oltre un lato plausibile il valore è un errore del server: moltiplicarlo farebbe traboccare Int64.
            let known = (1...Self.maxDeclaredSide).contains(width) && (1...Self.maxDeclaredSide).contains(height)
            candidates.append(Candidate(
                url: url,
                mime: mime,
                thumbnail: thumbnail,
                pixels: known ? Int64(width) * Int64(height) : Int64.max,
                order: index,
                longEdge: known ? max(width, height) : 0,
                shortEdge: known ? min(width, height) : 0
            ))
        }
        if let screen {
            let fitting = candidates.filter {
                $0.mime.hasPrefix("image/") && !$0.thumbnail && $0.longEdge >= screen.longEdge && $0.shortEdge >= screen.shortEdge
            }
            if let lightest = fitting.min(by: { $0.pixels < $1.pixels }) { return (lightest.url, lightest.mime) }
        }
        let best = candidates.sorted { first, second in
            if first.thumbnail != second.thumbnail { return !first.thumbnail }
            if first.pixels != second.pixels { return first.pixels > second.pixels }
            return first.order < second.order
        }.first
        return best.map { ($0.url, $0.mime) }
    }

    /// Indirizzo assoluto; solo http, come i server DLNA.
    static func resolve(_ reference: String, against base: URL) -> URL? {
        let trimmed = reference.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed, relativeTo: base)?.absoluteURL,
              url.scheme?.lowercased() == "http", url.host != nil else { return nil }
        return url
    }

    private static func escape(_ text: String) -> String {
        var result = ""
        for character in text {
            switch character {
            case "&": result += "&amp;"
            case "<": result += "&lt;"
            case ">": result += "&gt;"
            case "\"": result += "&quot;"
            case "'": result += "&apos;"
            default: result.append(character)
            }
        }
        return result
    }
}

/// Albero XML minimo, con i nomi senza prefisso. Le risposte UPnP non hanno DOCTYPE: si rifiutano a
/// monte, così nessuna entità può far leggere file o indirizzi.
final class XMLTree {
    let name: String
    let attributes: [String: String]
    private(set) var children: [XMLTree] = []
    fileprivate var buffer = ""

    init(name: String, attributes: [String: String]) {
        self.name = name
        self.attributes = attributes
    }

    var text: String { buffer.trimmingCharacters(in: .whitespacesAndNewlines) }

    func children(_ name: String) -> [XMLTree] { children.filter { $0.name == name } }

    func child(_ name: String) -> XMLTree? { children.first { $0.name == name } }

    func descendants(_ name: String) -> [XMLTree] {
        var found: [XMLTree] = []
        for child in children {
            if child.name == name { found.append(child) }
            found += child.descendants(name)
        }
        return found
    }

    static func parse(_ data: Data) throws -> XMLTree {
        let head = String(decoding: data.prefix(64 * 1024), as: UTF8.self).uppercased()
        if head.contains("<!DOCTYPE") || head.contains("<!ENTITY") { throw UpnpError.invalidXML }
        let builder = Builder()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = builder
        guard parser.parse(), let root = builder.root else { throw UpnpError.invalidXML }
        return root
    }

    fileprivate func append(_ child: XMLTree) {
        children.append(child)
    }

    private final class Builder: NSObject, XMLParserDelegate {
        var root: XMLTree?
        private var stack: [XMLTree] = []

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String] = [:]
        ) {
            let local = elementName.split(separator: ":").last.map(String.init) ?? elementName
            var attributes: [String: String] = [:]
            for (key, value) in attributeDict {
                attributes[key.split(separator: ":").last.map(String.init) ?? key] = value
            }
            let node = XMLTree(name: local, attributes: attributes)
            if let parent = stack.last { parent.append(node) } else if root == nil { root = node }
            stack.append(node)
            if stack.count > 64 { parser.abortParsing() }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            _ = stack.popLast()
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            stack.last?.buffer += string
        }

        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            stack.last?.buffer += String(decoding: CDATABlock, as: UTF8.self)
        }
    }
}
