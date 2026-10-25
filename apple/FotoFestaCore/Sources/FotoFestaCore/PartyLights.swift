import Foundation

/// Luci della festa: Philips Hue (bridge), WLED e Nanoleaf prendono il colore della foto sullo
/// schermo e lampeggiano agli arrivi. Tutto nella rete di casa, con le API locali dei dispositivi
/// (niente account né cloud). Qui le richieste; l'invio è in `PartyLightsClient`.
public enum PartyLights {
    public enum Kind: String, Codable, CaseIterable, Sendable {
        case hue = "HUE", wled = "WLED", nanoleaf = "NANOLEAF"

        /// Servizio Bonjour con cui si trovano nella rete.
        public var serviceType: String {
            switch self {
            case .hue: "_hue._tcp"
            case .wled: "_wled._tcp"
            case .nanoleaf: "_nanoleafapi._tcp"
            }
        }

        public var defaultPort: Int { self == .nanoleaf ? 16021 : 80 }
        /// Hue e Nanoleaf vogliono l'abbinamento con il pulsante; WLED no.
        public var needsPairing: Bool { self != .wled }
    }

    /// Luce collegata: indirizzo nella rete di casa e, per Hue e Nanoleaf, la chiave dell'abbinamento.
    public struct Device: Codable, Equatable, Hashable, Sendable, Identifiable {
        public let kind: Kind
        public let host: String
        public let port: Int
        public var token: String?
        public var name: String

        public init(kind: Kind, host: String, port: Int, token: String? = nil, name: String? = nil) {
            self.kind = kind
            self.host = host
            self.port = port
            self.token = token
            self.name = name ?? host
        }

        public var id: String { "\(kind.rawValue)@\(host):\(port)" }
        public var baseURL: String { "http://\(host.contains(":") ? "[\(host)]" : host):\(port)" }
    }

    /// Una richiesta HTTP da mandare alla luce.
    public struct Call: Equatable, Sendable {
        public let method: String
        public let url: String
        public let body: String
    }

    /// Abbinamento: Hue dopo aver premuto il pulsante del bridge, Nanoleaf tenendo premuto l'interruttore.
    public static func pairCall(_ device: Device) -> Call? {
        switch device.kind {
        case .hue: Call(method: "POST", url: "\(device.baseURL)/api", body: #"{"devicetype":"fotofesta#tv"}"#)
        case .nanoleaf: Call(method: "POST", url: "\(device.baseURL)/api/v1/new", body: "")
        case .wled: nil
        }
    }

    /// La chiave nella risposta all'abbinamento; nil se il pulsante non è stato premuto.
    public static func token(kind: Kind, response: Data) -> String? {
        let object = try? JSONSerialization.jsonObject(with: response)
        let token: String? = switch kind {
        case .hue: ((object as? [[String: Any]])?.first?["success"] as? [String: Any])?["username"] as? String
        case .nanoleaf: (object as? [String: Any])?["auth_token"] as? String
        case .wled: nil
        }
        guard let token, !token.isEmpty, token.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { return nil }
        return token
    }

    /// Accende la luce del colore `rgb` (0xRRGGBB) con luminosità da 0 a 1.
    public static func colorCall(_ device: Device, rgb: UInt32, brightness: Double = 1) -> Call? {
        let level = min(max(brightness, 0), 1)
        switch device.kind {
        case .hue:
            guard let token = device.token else { return nil }
            let (x, y) = hueXY(rgb)
            let body = #"{"on":true,"bri":\#(Int((1 + level * 253).rounded())),"xy":[\#(x),\#(y)],"transitiontime":6}"#
            return Call(method: "PUT", url: "\(device.baseURL)/api/\(token)/groups/0/action", body: body)
        case .wled:
            let body = #"{"on":true,"bri":\#(Int((level * 255).rounded())),"transition":6,"seg":[{"col":[[\#(rgb >> 16 & 0xFF),\#(rgb >> 8 & 0xFF),\#(rgb & 0xFF)]]}]}"#
            return Call(method: "POST", url: "\(device.baseURL)/json/state", body: body)
        case .nanoleaf:
            guard let token = device.token else { return nil }
            let (hue, saturation, _) = hsv(rgb)
            let body = #"{"on":{"value":true},"hue":{"value":\#(Int(hue.rounded()))},"sat":{"value":\#(Int((saturation * 100).rounded()))},"brightness":{"value":\#(Int((level * 100).rounded())),"duration":1}}"#
            return Call(method: "PUT", url: "\(device.baseURL)/api/v1/\(token)/state", body: body)
        }
    }

    /// Colore di una foto adatto alle luci: il medio, con più saturazione e luminosità (un grigio
    /// resta un bianco caldo, un colore spento diventa vivace).
    public static func partyColor(_ average: UInt32) -> UInt32 {
        let (hue, saturation, value) = hsv(average)
        if saturation < 0.12 { return 0xFFC98A }
        return fromHSV(hue: hue, saturation: min(1, saturation * 1.6 + 0.2), value: max(value, 0.85))
    }

    /// Coordinate CIE xy (gamut di Hue) per un colore sRGB.
    public static func hueXY(_ rgb: UInt32) -> (Double, Double) {
        func linear(_ channel: UInt32) -> Double {
            let c = Double(channel) / 255
            return c > 0.04045 ? pow((c + 0.055) / 1.055, 2.4) : c / 12.92
        }
        let r = linear(rgb >> 16 & 0xFF)
        let g = linear(rgb >> 8 & 0xFF)
        let b = linear(rgb & 0xFF)
        let x = r * 0.664511 + g * 0.154324 + b * 0.162028
        let y = r * 0.283881 + g * 0.668433 + b * 0.047685
        let z = r * 0.000088 + g * 0.072310 + b * 0.986039
        let sum = x + y + z
        guard sum > 0 else { return (0.3227, 0.329) }
        return ((x / sum * 10_000).rounded() / 10_000, (y / sum * 10_000).rounded() / 10_000)
    }

    /// Tinta (0–360), saturazione e valore (0–1).
    public static func hsv(_ rgb: UInt32) -> (Double, Double, Double) {
        let r = Double(rgb >> 16 & 0xFF) / 255
        let g = Double(rgb >> 8 & 0xFF) / 255
        let b = Double(rgb & 0xFF) / 255
        let high = max(r, g, b)
        let delta = high - min(r, g, b)
        var hue: Double
        if delta == 0 {
            hue = 0
        } else if high == r {
            hue = 60 * ((g - b) / delta).truncatingRemainder(dividingBy: 6)
            if hue < 0 { hue += 360 }
        } else if high == g {
            hue = 60 * ((b - r) / delta + 2)
        } else {
            hue = 60 * ((r - g) / delta + 4)
        }
        return (hue, high == 0 ? 0 : delta / high, high)
    }

    public static func fromHSV(hue: Double, saturation: Double, value: Double) -> UInt32 {
        let c = value * saturation
        let sector = hue / 60
        let x = c * (1 - abs(sector.truncatingRemainder(dividingBy: 2) - 1))
        let m = value - c
        let (r, g, b): (Double, Double, Double) = switch Int(sector) % 6 {
        case 0: (c, x, 0)
        case 1: (x, c, 0)
        case 2: (0, c, x)
        case 3: (0, x, c)
        case 4: (x, 0, c)
        default: (c, 0, x)
        }
        func channel(_ v: Double) -> UInt32 { UInt32(min(max(((v + m) * 255).rounded(), 0), 255)) }
        return channel(r) << 16 | channel(g) << 8 | channel(b)
    }
}

/// Manda le richieste di `PartyLights` alle luci, solo nella rete di casa.
public struct PartyLightsClient: Sendable {
    private let http: HTTPClient

    public init(http: HTTPClient) {
        self.http = http
    }

    /// Abbina la luce; nil se serve ancora premere il pulsante o se non risponde.
    public func pair(_ device: PartyLights.Device) async -> String? {
        guard let call = PartyLights.pairCall(device), let data = try? await send(call) else { return nil }
        return PartyLights.token(kind: device.kind, response: data)
    }

    @discardableResult
    public func show(_ device: PartyLights.Device, rgb: UInt32, brightness: Double = 1) async -> Bool {
        guard let call = PartyLights.colorCall(device, rgb: rgb, brightness: brightness) else { return false }
        return (try? await send(call)) != nil
    }

    private func send(_ call: PartyLights.Call) async throws -> Data {
        guard let url = URL(string: call.url), LANAddress.isLocal(url) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 4)
        request.httpMethod = call.method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(call.body.utf8)
        let (data, response) = try await http.send(request)
        guard (200...299).contains(response.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }
}
