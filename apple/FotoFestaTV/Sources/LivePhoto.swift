import FotoFestaCore
import Photos
import PhotosUI
import SwiftUI

extension PhotoLibrary {
    /// La Live Photo della libreria, o nil se la foto non lo è.
    static func livePhoto(localIdentifier: String, maxPixelSize: Int) async -> PHLivePhoto? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject,
              asset.mediaSubtypes.contains(.photoLive) else { return nil }
        let options = PHLivePhotoRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        let size = CGSize(width: maxPixelSize, height: maxPixelSize)
        let box = await withCheckedContinuation { (continuation: CheckedContinuation<LiveBox, Never>) in
            let once = LiveOnce()
            PHImageManager.default().requestLivePhoto(for: asset, targetSize: size, contentMode: .aspectFit, options: options) { live, info in
                // Con highQualityFormat arriva una sola risposta; un errore o un annullamento danno nil.
                if (info?[PHImageResultIsDegradedKey] as? Bool) == true { return }
                once.run { continuation.resume(returning: LiveBox(live: live)) }
            }
        }
        return box.live
    }

    /// Byte originali della foto, per decodificarla in HDR.
    static func imageData(localIdentifier: String) async -> Data? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject else { return nil }
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        options.version = .current
        let box = await withCheckedContinuation { (continuation: CheckedContinuation<DataBox, Never>) in
            let once = LiveOnce()
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                once.run { continuation.resume(returning: DataBox(data: data)) }
            }
        }
        return box.data
    }

    private struct LiveBox: @unchecked Sendable {
        let live: PHLivePhoto?
    }

    private struct DataBox: @unchecked Sendable {
        let data: Data?
    }

    private final class LiveOnce: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false

        func run(_ action: () -> Void) {
            lock.lock()
            let first = !done
            done = true
            lock.unlock()
            if first { action() }
        }
    }
}

/// Live Photo nello slideshow: quando la foto compare si anima una volta il breve video, poi resta
/// la foto. Sopra la foto normale, così senza Live Photo non cambia nulla.
struct LivePhotoOverlay: View {
    let photo: Photo
    @State private var live: PHLivePhoto?

    var body: some View {
        ZStack {
            if let live { LivePhotoPlayer(live: live).transition(.opacity) }
        }
        .allowsHitTesting(false)
        .task(id: photo.id) {
            guard case let .library(identifier) = photo.source,
                  let found = await PhotoLibrary.livePhoto(localIdentifier: identifier, maxPixelSize: 1920) else { return }
            live = found
            // Il breve video dura circa tre secondi: poi resta la foto dello slideshow, con il suo taglio.
            try? await Task.sleep(for: .milliseconds(3_600))
            withAnimation(.easeOut(duration: 0.6)) { live = nil }
        }
    }
}

#if canImport(UIKit)
private struct LivePhotoPlayer: UIViewRepresentable {
    let live: PHLivePhoto

    func makeUIView(context: Context) -> PHLivePhotoView {
        let view = PHLivePhotoView()
        view.contentMode = .scaleAspectFit
        view.isMuted = true
        return view
    }

    func updateUIView(_ view: PHLivePhotoView, context: Context) {
        guard view.livePhoto !== live else { return }
        view.livePhoto = live
        view.startPlayback(with: .full)
    }
}
#else
private struct LivePhotoPlayer: NSViewRepresentable {
    let live: PHLivePhoto

    func makeNSView(context: Context) -> PHLivePhotoView {
        let view = PHLivePhotoView()
        view.contentMode = .aspectFit
        view.isMuted = true
        return view
    }

    func updateNSView(_ view: PHLivePhotoView, context: Context) {
        guard view.livePhoto !== live else { return }
        view.livePhoto = live
        view.startPlayback(with: .full)
    }
}
#endif
