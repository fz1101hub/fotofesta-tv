import FotoFestaCore
import ImageIO
import SwiftUI
import UIKit

/// Decodifica le foto ridimensionate alla risoluzione dello schermo, fuori dal thread principale,
/// con una piccola cache in memoria per la foto corrente e le successive. Le foto di Google Foto
/// arrivano da Google già ridotte e non vengono mai salvate su disco (sessione di rete effimera);
/// quelle degli invitati arrivano dall'archivio della festa.
actor ImageLoader {
    static let shared = ImageLoader()

    private let cache = NSCache<NSString, UIImage>()
    private let network: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }()

    init() {
        cache.countLimit = 8
        cache.totalCostLimit = 160 * 1024 * 1024
    }

    func image(for photo: Photo, maxPixelSize: Int) async -> UIImage? {
        let key = "\(photo.id)#\(maxPixelSize)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        let decoded: CGImage?
        switch photo.source {
        case let .bundled(fileName):
            let name = (fileName as NSString).deletingPathExtension
            let ext = (fileName as NSString).pathExtension
            guard let url = Bundle.main.url(forResource: name, withExtension: ext),
                  let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            decoded = Self.thumbnail(source, maxPixelSize: maxPixelSize)
        case let .google(mediaID):
            guard let data = await googleData(mediaID: mediaID, maxPixelSize: maxPixelSize),
                  let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
            decoded = Self.thumbnail(source, maxPixelSize: maxPixelSize)
        case let .guest(id):
            // Dall'archivio della festa: in RAM o nella cartella di riserva. I byte si leggono subito e
            // con il lock dell'archivio, così un'approvazione o una cancellazione a metà non rompe la foto.
            guard let data = GuestStorage.store.read(id),
                  let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
            decoded = Self.thumbnail(source, maxPixelSize: maxPixelSize)
        }
        guard let decoded else { return nil }
        let image = UIImage(cgImage: decoded)
        cache.setObject(image, forKey: key, cost: decoded.bytesPerRow * decoded.height)
        return image
    }

    /// Svuota la cache in memoria (ad esempio dopo "Disconnetti e cancella").
    func removeAll() {
        cache.removeAllObjects()
    }

    /// Byte della foto ridotta da Google al lato massimo richiesto. Dopo un 401/403 rinnova una volta
    /// indirizzo e token. Errori e selezione scaduta restituiscono nil (la scadenza la gestisce il modello).
    private func googleData(mediaID: String, maxPixelSize: Int) async -> Data? {
        let stream = GoogleServices.shared.stream
        do {
            let first = try await stream.access(mediaID: mediaID)
            var (data, status) = try await download(first, maxPixelSize: maxPixelSize)
            if status == 401 || status == 403 {
                // Rinnovo condiviso con le altre foto in caricamento che hanno ricevuto lo stesso errore.
                (data, status) = try await download(try await stream.access(mediaID: mediaID, failed: first), maxPixelSize: maxPixelSize)
            }
            return (200..<300).contains(status) && !data.isEmpty ? data : nil
        } catch {
            return nil
        }
    }

    private func download(_ access: GooglePhotosStream.Access, maxPixelSize: Int) async throws -> (Data, Int) {
        guard let url = PhotosPicker.sizedURL(base: access.baseURL, width: maxPixelSize, height: maxPixelSize, crop: false) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(access.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await network.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private static func thumbnail(_ source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// Foto caricata in modo asincrono; mostra un segnaposto se il file non è leggibile.
struct PhotoImage: View {
    let photo: Photo
    var fitMode: FitMode = .contain
    var maxPixelSize = 3840
    var accessibilityLabel: String?

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: fitMode == .crop ? .fill : .fit)
                    .accessibilityLabel(Text(accessibilityLabel ?? photo.title))
            } else if failed {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 64))
                    Text(L("photo.unavailable"))
                }
                .foregroundStyle(Theme.secondaryText)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: "\(photo.id)#\(maxPixelSize)") {
            failed = false
            let loaded = await ImageLoader.shared.image(for: photo, maxPixelSize: maxPixelSize)
            image = loaded
            failed = loaded == nil
        }
    }
}
