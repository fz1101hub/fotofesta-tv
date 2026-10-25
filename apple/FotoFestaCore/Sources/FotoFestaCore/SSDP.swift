import Darwin
import Foundation

/// Ricerca dei server multimediali (DLNA/UPnP) con SSDP: una domanda a cui i server rispondono
/// direttamente al dispositivo con l'indirizzo della loro descrizione.
///
/// La domanda in multicast a tutta la rete richiede su tvOS l'entitlement
/// `com.apple.developer.networking.multicast`, concesso da Apple su richiesta: senza, l'invio fallisce
/// e restano le domande dirette (unicast) a un indirizzo scritto dall'utente, che non lo richiedono.
public enum SSDP {
    public static let contentDirectory = "urn:schemas-upnp-org:service:ContentDirectory:1"
    public static let mediaServer = "urn:schemas-upnp-org:device:MediaServer:1"
    public static let multicastAddress = "239.255.255.250"
    public static let port: UInt16 = 1900

    /// Risposta di un server: indirizzo della descrizione e identificativo del dispositivo.
    public struct Answer: Hashable, Sendable {
        public let location: URL
        public let udn: String
    }

    /// Domanda M-SEARCH; `host` è il gruppo multicast o, per una domanda diretta, l'indirizzo del server.
    public static func searchMessage(target: String, host: String = "\(SSDP.multicastAddress):\(SSDP.port)", multicast: Bool = true) -> String {
        var message = "M-SEARCH * HTTP/1.1\r\n"
            + "HOST: \(host)\r\n"
            + "MAN: \"ssdp:discover\"\r\n"
        // MX serve solo in multicast, perché i server non rispondano tutti insieme.
        if multicast { message += "MX: 2\r\n" }
        message += "ST: \(target)\r\n"
            + "USER-AGENT: tvOS UPnP/1.1 FotoFesta-TV/1.0\r\n"
            + "\r\n"
        return message
    }

    /// Risposta a M-SEARCH ("HTTP/1.1 200 OK" con LOCATION e USN); nil se non è una risposta valida.
    public static func parseAnswer(_ text: String) -> Answer? {
        let lines = text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "\r")) }
        guard let status = lines.first?.trimmingCharacters(in: .whitespaces).uppercased(),
              status.hasPrefix("HTTP/1.1 200") || status.hasPrefix("HTTP/1.0 200") else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), colon > line.startIndex else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }
        guard let locationText = headers["location"], locationText.lowercased().hasPrefix("http://"),
              let location = URL(string: locationText),
              let usn = headers["usn"] else { return nil }
        let udn = usn.components(separatedBy: "::").first ?? ""
        guard !udn.isEmpty else { return nil }
        return Answer(location: location, udn: udn)
    }

    /// Chiede in multicast (se `multicast`) e direttamente ai `hosts`, due volte perché UDP può perdere
    /// pacchetti, e raccoglie le risposte per `timeout` secondi.
    public static func search(
        timeout: TimeInterval = 3,
        multicast: Bool = true,
        hosts: [String] = [],
        port: UInt16 = SSDP.port,
        targets: [String] = [SSDP.contentDirectory, SSDP.mediaServer]
    ) async -> [Answer] {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: searchBlocking(timeout: timeout, multicast: multicast, hosts: hosts, port: port, targets: targets))
            }
        }
    }

    private static func searchBlocking(timeout: TimeInterval, multicast: Bool, hosts: [String], port: UInt16, targets: [String]) -> [Answer] {
        let socket = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socket >= 0 else { return [] }
        defer { close(socket) }
        var destinations: [(address: sockaddr_in, multicast: Bool, host: String)] = []
        if multicast, let group = ipv4(multicastAddress, port: port) {
            destinations.append((group, true, "\(multicastAddress):\(port)"))
        }
        for host in hosts {
            if let address = ipv4(host, port: port) { destinations.append((address, false, "\(host):\(port)")) }
        }
        guard !destinations.isEmpty else { return [] }
        for _ in 0..<2 {
            for destination in destinations {
                for target in targets {
                    let message = Array(searchMessage(target: target, host: destination.host, multicast: destination.multicast).utf8)
                    var address = destination.address
                    // Senza l'entitlement multicast l'invio al gruppo fallisce: si va avanti con gli altri.
                    _ = withUnsafePointer(to: &address) { pointer in
                        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            sendto(socket, message, message.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
            }
        }
        var answers: [Answer] = []
        var seen = Set<URL>()
        let deadline = Date().addingTimeInterval(timeout)
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let left = deadline.timeIntervalSinceNow
            if left <= 0 { break }
            var wait = timeval(tv_sec: Int(left), tv_usec: Int32((left - floor(left)) * 1_000_000))
            if wait.tv_sec == 0 && wait.tv_usec == 0 { wait.tv_usec = 1_000 }
            setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
            let count = recv(socket, &buffer, buffer.count, 0)
            if count < 0 {
                if errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR { continue }
                break
            }
            let text = String(decoding: buffer[0..<count], as: UTF8.self)
            if let answer = parseAnswer(text), seen.insert(answer.location).inserted {
                answers.append(answer)
            }
        }
        return answers
    }

    private static func ipv4(_ host: String, port: UInt16) -> sockaddr_in? {
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &address.sin_addr) == 1 else { return nil }
        return address
    }
}

/// Indirizzi della rete di casa: solo questi si leggono in chiaro (http) per le cartelle di rete.
public enum LANAddress {
    /// Solo per le schermate dal simulatore nella CI (build di debug): il server finto gira sul Mac in
    /// loopback, perché il runner non lascia passare il traffico del simulatore verso la rete. Spento
    /// di serie e mai acceso nell'app pubblicata.
    public nonisolated(unsafe) static var allowsLoopback = false

    /// Indirizzo IP numerico privato o locale: 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6
    /// link-local (fe80::/10) e locali (fc00::/7). I nomi non contano: potrebbero puntare fuori.
    public static func isLocal(_ host: String) -> Bool {
        let bare = host.hasPrefix("[") && host.hasSuffix("]") ? String(host.dropFirst().dropLast()) : host
        var v4 = in_addr()
        if inet_pton(AF_INET, bare, &v4) == 1 {
            let value = UInt32(bigEndian: v4.s_addr)
            let a = value >> 24
            let b = (value >> 16) & 0xFF
            if allowsLoopback && a == 127 { return true }
            return a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        var v6 = in6_addr()
        let scopeless = bare.split(separator: "%").first.map(String.init) ?? bare
        if inet_pton(AF_INET6, scopeless, &v6) == 1 {
            let first = withUnsafeBytes(of: &v6) { $0[0] }
            let second = withUnsafeBytes(of: &v6) { $0[1] }
            return (first == 0xFE && (second & 0xC0) == 0x80) || (first & 0xFE) == 0xFC
        }
        return false
    }

    /// L'indirizzo è http verso un dispositivo della rete di casa.
    public static func isLocal(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "http", let host = url.host else { return false }
        return isLocal(host)
    }
}
