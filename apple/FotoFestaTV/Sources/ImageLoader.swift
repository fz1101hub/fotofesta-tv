import FotoFestaCore
import ImageIO
import SwiftUI
import UIKit

/// Decodifica le foto ridimensionate alla risoluzione dello schermo, fuori dal thread principale,
/// con una piccola cache in memoria per la foto corrente e la successiva.
actor ImageLoader {
    static let shared = ImageLoader()

    private let cache = NSCache<NSString, UIImage>()

    init() {
        cache.countLimit = 6
    }

    func image(for photo: Photo, maxPixelSize: Int) -> UIImage? {
        let key = "\(photo.id)#\(maxPixelSize)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let url = Self.url(for: photo.source),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let image = UIImage(cgImage: cgImage)
        cache.setObject(image, forKey: key)
        return image
    }

    private static func url(for source: PhotoSource) -> URL? {
        switch source {
        case let .bundled(fileName):
            let name = (fileName as NSString).deletingPathExtension
            let ext = (fileName as NSString).pathExtension
            return Bundle.main.url(forResource: name, withExtension: ext)
        case let .file(path):
            return URL(fileURLWithPath: path)
        }
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
