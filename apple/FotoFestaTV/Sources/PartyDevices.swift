import FotoFestaCore
import Foundation
import ImageIO
import Network
import Observation
import UniformTypeIdentifiers

/// Dispositivi della festa scelti nelle impostazioni, salvati in UserDefaults.
struct DeviceSettings: Codable, Equatable {
    var lights: [PartyLights.Device] = []
    var printer: Ipp.Printer?
    var receiptHost = ""
    var paper: EscPos.Paper = .mm58
}

/// Luci, stampante delle foto, stampante degli scontrini e fotocamere Wi‑Fi (FTP), tutto nella rete
/// di casa con i protocolli locali dei dispositivi. Le luci si trovano con Bonjour; Hue e Nanoleaf
/// si abbinano con il loro pulsante.
@MainActor
@Observable
final class PartyDevices {
    private(set) var config: DeviceSettings
    private(set) var foundLights: [PartyLights.Device] = []
    private(set) var foundPrinters: [Ipp.Printer] = []
    private(set) var searching = false
    /// Luce in attesa che si prema il suo pulsante.
    private(set) var pairing: PartyLights.Device?
    /// Porta del server FTP acceso, nil se spento.
    private(set) var ftpPort: UInt16?
    /// Password delle fotocamere: nuova a ogni avvio dell'app.
    let ftpUser = "fotofesta"
    let ftpPassword = String(format: "%06d", Int.random(in: 0...999_999))
    private(set) var printed = 0

    static let maxAutoPrints = 100
    private static let key = "partyDevices"

    @ObservationIgnored private var browsers: [NWBrowser] = []
    @ObservationIgnored private var resolving: [NWConnection] = []
    @ObservationIgnored private var ftp: FtpServer?
    @ObservationIgnored private var lastColor: UInt32?
    @ObservationIgnored private let lights: PartyLightsClient
    @ObservationIgnored private let ipp: IppClient

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 60
        let http = URLSessionHTTPClient(session: URLSession(configuration: configuration), maxBytes: 256 * 1024, allowRedirect: { _ in false })
        lights = PartyLightsClient(http: http)
        ipp = IppClient(http: http)
        config = UserDefaults.standard.data(forKey: Self.key).flatMap { try? JSONDecoder().decode(DeviceSettings.self, from: $0) } ?? DeviceSettings()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(config) { UserDefaults.standard.set(data, forKey: Self.key) }
    }

    // MARK: Ricerca nella rete

    func startSearch() {
        stopSearch()
        searching = true
        for kind in PartyLights.Kind.allCases {
            browse(kind.serviceType) { [weak self] host, port, name in
                guard let self else { return }
                let device = PartyLights.Device(kind: kind, host: host, port: port == 0 ? kind.defaultPort : port, name: name)
                if !self.foundLights.contains(where: { $0.host == host && $0.kind == kind }) { self.foundLights.append(device) }
            }
        }
        browse(Ipp.serviceType) { [weak self] host, port, name in
            guard let self else { return }
            let printer = Ipp.Printer(host: host, port: port == 0 ? Ipp.port : port, name: name)
            if !self.foundPrinters.contains(where: { $0.host == host }) { self.foundPrinters.append(printer) }
        }
    }

    func stopSearch() {
        browsers.forEach { $0.cancel() }
        browsers = []
        resolving.forEach { $0.cancel() }
        resolving = []
        searching = false
    }

    /// Servizi Bonjour di un tipo: ognuno si risolve in un indirizzo IPv4 con una connessione di prova.
    private func browse(_ type: String, found: @escaping (String, Int, String) -> Void) {
        let browser = NWBrowser(for: .bonjour(type: type, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor [weak self] in
                for result in results {
                    guard case let .service(name, _, _, _) = result.endpoint else { continue }
                    self?.resolve(result.endpoint) { host, port in found(host, port, name) }
                }
            }
        }
        browser.start(queue: .main)
        browsers.append(browser)
    }

    private func resolve(_ endpoint: NWEndpoint, done: @escaping (String, Int) -> Void) {
        let parameters = NWParameters.tcp
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let connection = NWConnection(to: endpoint, using: parameters)
        connection.stateUpdateHandler = { [weak connection] state in
            guard let connection else { return }
            switch state {
            case .ready:
                if case let .hostPort(host, port) = connection.currentPath?.remoteEndpoint, case let .ipv4(address) = host {
                    let text = "\(address)"
                    Task { @MainActor in if LANAddress.isLocal(text) { done(text, Int(port.rawValue)) } }
                }
                connection.cancel()
            case .failed, .waiting:
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: .main)
        resolving.append(connection)
    }

    // MARK: Luci

    /// Abbina la luce, riprovando per un minuto mentre si preme il pulsante. WLED si collega subito.
    func connect(_ device: PartyLights.Device) async -> Bool {
        var device = device
        if device.kind.needsPairing {
            pairing = device
            defer { pairing = nil }
            var token: String?
            for _ in 0..<30 {
                if Task.isCancelled { return false }
                token = await lights.pair(device)
                if token != nil { break }
                try? await Task.sleep(for: .seconds(2))
            }
            guard let token else { return false }
            device.token = token
        }
        config.lights.removeAll { $0.id == device.id }
        config.lights.append(device)
        save()
        await lights.show(device, rgb: 0xFF4F9A)
        return true
    }

    func cancelPairing() {
        pairing = nil
    }

    func removeLight(_ device: PartyLights.Device) {
        config.lights.removeAll { $0.id == device.id }
        save()
    }

    func testLight(_ device: PartyLights.Device) {
        Task { await lights.show(device, rgb: 0x8B6CFF) }
    }

    /// Il colore medio della foto sullo schermo, reso vivace, su tutte le luci.
    func showPhotoColor(_ average: UInt32) {
        let color = PartyLights.partyColor(average)
        guard color != lastColor, !config.lights.isEmpty else { return }
        lastColor = color
        let devices = config.lights
        let client = lights
        Task.detached { for device in devices { await client.show(device, rgb: color) } }
    }

    /// Un lampo bianco e poi di nuovo il colore della foto: qualcuno è arrivato.
    func flash() {
        guard !config.lights.isEmpty else { return }
        let devices = config.lights
        let client = lights
        let back = lastColor ?? 0xFFC98A
        Task.detached {
            for device in devices { await client.show(device, rgb: 0xFFFFFF) }
            try? await Task.sleep(for: .milliseconds(900))
            for device in devices { await client.show(device, rgb: back) }
        }
    }

    // MARK: Stampanti

    func usePrinter(_ printer: Ipp.Printer?) async -> Bool {
        guard let printer else {
            config.printer = nil
            save()
            return true
        }
        let format = try? await ipp.format(printer)
        guard format != nil else { return false }
        config.printer = printer
        save()
        return true
    }

    func setReceiptHost(_ host: String) {
        config.receiptHost = host.trimmingCharacters(in: .whitespaces)
        save()
    }

    func setPaper(_ paper: EscPos.Paper) {
        config.paper = paper
        save()
    }

    /// Stampa la foto; `automatic` conta verso il limite della festa.
    func print(_ photo: Photo, automatic: Bool) async -> Bool {
        guard let printer = config.printer else { return false }
        if automatic {
            guard printed < Self.maxAutoPrints else { return false }
            printed += 1
        }
        guard let jpeg = await Self.jpeg(for: photo, maxPixelSize: 2400) else { return false }
        return (try? await ipp.print(printer, jpeg: jpeg, jobName: photo.sender ?? "FotoFesta")) == true
    }

    func receipt(_ photo: Photo, title: String, automatic: Bool) async -> Bool {
        let host = config.receiptHost
        guard !host.isEmpty else { return false }
        if automatic {
            guard printed < Self.maxAutoPrints else { return false }
            printed += 1
        }
        let gray = await Self.gray(for: photo, width: config.paper.rawValue - 32)
        let time = (photo.receivedAt ?? Date()).formatted(date: .abbreviated, time: .shortened)
        let bytes = EscPos.receipt(title: title, photo: gray, lines: [photo.sender ?? "", time].filter { !$0.isEmpty }, footer: "FotoFesta")
        return (try? await EscPos.send(bytes, host: host)) != nil
    }

    func resetPrintCount() {
        printed = 0
    }

    /// JPEG della foto ridotta, per la stampante.
    nonisolated static func jpeg(for photo: Photo, maxPixelSize: Int) async -> Data? {
        if case let .guest(id) = photo.source, let data = GuestStorage.store.read(id), GuestImageFormat.sniff(data) == .jpeg { return data }
        guard let image = await ImageLoader.shared.image(for: photo, maxPixelSize: maxPixelSize)?.platformCGImage else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    /// La foto in toni di grigio larga `width` punti, per lo scontrino.
    nonisolated static func gray(for photo: Photo, width: Int) async -> EscPos.Gray? {
        guard let image = await ImageLoader.shared.image(for: photo, maxPixelSize: 1024)?.platformCGImage, image.width > 0 else { return nil }
        let height = min(max(Int(Double(width) * Double(image.height) / Double(image.width)), 1), width * 2)
        var pixels = [UInt8](repeating: 255, count: width * height)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? EscPos.Gray(width: width, height: height, pixels: pixels) : nil
    }

    // MARK: Fotocamere Wi‑Fi

    func startFTP(onFile: @escaping @Sendable (String, Data) -> Void) {
        guard ftp == nil else { return }
        let password = ftpPassword
        let server = FtpServer(user: ftpUser, password: { password }, onFile: onFile)
        ftp = server
        Task {
            do {
                ftpPort = try await server.start()
            } catch {
                ftp = nil
                ftpPort = nil
            }
        }
    }

    var ftpActive: Bool { ftp != nil }

    func stopFTP() {
        ftp?.stop()
        ftp = nil
        ftpPort = nil
    }
}
