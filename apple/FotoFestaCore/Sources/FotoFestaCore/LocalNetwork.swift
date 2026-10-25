import Darwin
import Foundation

/// Indirizzo del dispositivo sulla rete di casa, da scrivere nel QR per gli invitati.
public enum LocalNetwork {
    /// Primo indirizzo IPv4 di un'interfaccia Wi‑Fi o Ethernet attiva (`en*`), esclusi loopback e
    /// indirizzi autoassegnati (169.254.x.x). Nil se il dispositivo non è in rete.
    public static func currentIPv4() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var candidates: [(name: String, address: String)] = []
        for item in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(bitPattern: item.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0,
                  let address = item.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: item.pointee.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else {
                continue
            }
            let text = String(cString: host)
            if text.hasPrefix("169.254.") { continue }
            candidates.append((name, text))
        }
        // en0 prima di en1: sulle Apple TV con cavo e Wi‑Fi vale l'interfaccia principale.
        return candidates.sorted { $0.name < $1.name }.first?.address
    }
}
