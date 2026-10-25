import Foundation

/// File fotofesta-config.json: impostazioni, preparazione della festa e Wi‑Fi degli invitati, per
/// preparare un altro Mac o Apple TV allo stesso modo.
public enum VenueConfig {
    public static let fileName = "fotofesta-config.json"
    public static let maxBytes = 24 * 1024 * 1024
    static let version = 1

    public struct Contents {
        public let settings: SlideshowSettings
        /// Preparazione della festa nel formato di `PartySetup.exportJSON()`.
        public let setup: Data?
        public let wifi: WifiNetwork?
    }

    public static func make(settings: SlideshowSettings, setup: Data?, wifi: WifiNetwork?) -> Data? {
        guard let settingsData = try? JSONEncoder().encode(settings),
              let settingsObject = try? JSONSerialization.jsonObject(with: settingsData) else { return nil }
        var root: [String: Any] = ["app": "FotoFesta", "version": version, "settings": settingsObject]
        if let setup, let setupObject = try? JSONSerialization.jsonObject(with: setup) { root["setup"] = setupObject }
        if let wifi { root["wifi"] = ["ssid": wifi.ssid, "password": wifi.password] }
        return try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    public static func parse(_ data: Data) -> Contents? {
        guard data.count <= maxBytes,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              root["app"] as? String == "FotoFesta", root["version"] as? Int == version,
              let settingsObject = root["settings"] as? [String: Any],
              let settingsData = try? JSONSerialization.data(withJSONObject: settingsObject),
              let settings = try? JSONDecoder().decode(SlideshowSettings.self, from: settingsData) else { return nil }
        let setup = (root["setup"] as? [String: Any]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }
        var wifi: WifiNetwork?
        if let object = root["wifi"] as? [String: Any], let ssid = object["ssid"] as? String {
            let password = object["password"] as? String ?? ""
            if WifiNetwork.validate(ssid: ssid, password: password) == nil { wifi = WifiNetwork(ssid: ssid, password: password) }
        }
        return Contents(settings: settings, setup: setup, wifi: wifi)
    }
}
