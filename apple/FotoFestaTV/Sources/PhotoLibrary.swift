import AVFoundation
import FotoFestaCore
import Photos
import UIKit

/// Album della libreria Foto della Apple TV: tutte le foto, preferiti, album e album condivisi.
struct LibraryAlbum: Identifiable, Hashable, Sendable {
    enum Kind: String, Sendable {
        /// Tutte le foto e i video della libreria (Foto di iCloud).
        case all
        case favorites
        case album
        /// Album condivisi di iCloud, anche quelli a cui si è stati invitati.
        case shared
    }

    /// Identificativo della raccolta in PhotoKit; "all" per tutta la libreria.
    let id: String
    let kind: Kind
    let title: String
    let count: Int
    /// Foto più recente, per la copertina.
    let cover: Photo?
}

/// Libreria Foto del dispositivo, letta con PhotoKit. Sulla Apple TV contiene le Foto di iCloud (se
/// attive in Impostazioni › Utenti e account) e gli album condivisi. Le foto si chiedono nella
/// dimensione dello schermo e, se servono, si scaricano da iCloud al momento: l'app non ne tiene copie.
enum PhotoLibrary {
    enum Access: Equatable {
        case notDetermined, authorized, denied
    }

    /// Al massimo tante foto per album, come per le altre sorgenti.
    static let maxPhotos = 10_000
    static let allID = "all"

    static var access: Access {
        map(PHPhotoLibrary.authorizationStatus(for: .readWrite))
    }

    /// Chiede l'accesso alla libreria (la prima volta il sistema mostra la richiesta).
    static func requestAccess() async -> Access {
        await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                continuation.resume(returning: map(status))
            }
        }
    }

    private static func map(_ status: PHAuthorizationStatus) -> Access {
        switch status {
        case .authorized: return .authorized
        case .notDetermined: return .notDetermined
        // Su tvOS non c'è l'accesso limitato: qualunque altro stato vuol dire niente foto.
        default: return .denied
        }
    }

    /// Album con almeno una foto o un video: tutta la libreria, preferiti, album, album condivisi.
    static func albums() -> [LibraryAlbum] {
        var result: [LibraryAlbum] = []
        let all = PHAsset.fetchAssets(with: mediaOptions())
        if all.count > 0 {
            result.append(LibraryAlbum(id: allID, kind: .all, title: L("library.all"), count: all.count, cover: all.lastObject.map(Self.photo)))
        }
        let favorites = PHAssetCollection.fetchAssetCollections(with: .smartAlbum, subtype: .smartAlbumFavorites, options: nil)
        favorites.enumerateObjects { collection, _, _ in
            let assets = PHAsset.fetchAssets(in: collection, options: Self.mediaOptions())
            if assets.count > 0 {
                result.append(LibraryAlbum(
                    id: collection.localIdentifier,
                    kind: .favorites,
                    title: L("library.favorites"),
                    count: assets.count,
                    cover: assets.lastObject.map(Self.photo)
                ))
            }
        }
        for (subtype, kind) in [(PHAssetCollectionSubtype.albumRegular, LibraryAlbum.Kind.album), (.albumCloudShared, .shared)] {
            var found: [LibraryAlbum] = []
            PHAssetCollection.fetchAssetCollections(with: .album, subtype: subtype, options: nil).enumerateObjects { collection, _, _ in
                let assets = PHAsset.fetchAssets(in: collection, options: Self.mediaOptions())
                guard assets.count > 0 else { return }
                let title = collection.localizedTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                found.append(LibraryAlbum(
                    id: collection.localIdentifier,
                    kind: kind,
                    title: title.isEmpty ? L("library.untitled") : title,
                    count: assets.count,
                    cover: assets.lastObject.map(Self.photo)
                ))
            }
            result += found.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        }
        return result
    }

    /// Foto e video di un album, dal più vecchio al più recente; nil se l'album non c'è più.
    static func photos(albumID: String) -> [Photo]? {
        let assets: PHFetchResult<PHAsset>
        if albumID == allID {
            assets = PHAsset.fetchAssets(with: mediaOptions())
        } else {
            guard let collection = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [albumID], options: nil).firstObject else {
                return nil
            }
            assets = PHAsset.fetchAssets(in: collection, options: mediaOptions())
        }
        var photos: [Photo] = []
        photos.reserveCapacity(min(assets.count, maxPhotos))
        assets.enumerateObjects { asset, index, stop in
            guard index < Self.maxPhotos else {
                stop.pointee = true
                return
            }
            photos.append(Self.photo(asset))
        }
        return photos
    }

    /// Data e ora dello scatto: nei giochi fanno da titolo, perché PhotoKit non dà un nome al file.
    private static let titleFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    private static func photo(_ asset: PHAsset) -> Photo {
        Photo(
            id: "library:\(asset.localIdentifier)",
            title: asset.creationDate.map { titleFormatter.string(from: $0) } ?? "",
            source: .library(localIdentifier: asset.localIdentifier),
            isVideo: asset.mediaType == .video
        )
    }

    /// Foto ridotta al lato massimo indicato; scaricata da iCloud se sul dispositivo non c'è.
    static func image(localIdentifier: String, maxPixelSize: Int) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject else { return nil }
        let options = PHImageRequestOptions()
        // Una sola risposta, già nella qualità finale.
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        let size = CGSize(width: maxPixelSize, height: maxPixelSize)
        return await withCheckedContinuation { continuation in
            let once = Once()
            PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFit, options: options) { image, _ in
                once.run { continuation.resume(returning: image) }
            }
        }
    }

    /// Video per AVPlayer, letto da iCloud se serve.
    static func playerItem(localIdentifier: String) async -> AVPlayerItem? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject,
              asset.mediaType == .video else { return nil }
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .automatic
        let box = await withCheckedContinuation { (continuation: CheckedContinuation<PlayerItemBox, Never>) in
            let once = Once()
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { item, _ in
                once.run { continuation.resume(returning: PlayerItemBox(item: item)) }
            }
        }
        return box.item
    }

    /// Solo foto e video, dal più vecchio: l'ordine delle feste.
    private static func mediaOptions() -> PHFetchOptions {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "mediaType == %d OR mediaType == %d",
            PHAssetMediaType.image.rawValue,
            PHAssetMediaType.video.rawValue
        )
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        return options
    }

    /// Il video passa dal gestore di PhotoKit al lettore; AVPlayerItem non è Sendable.
    private struct PlayerItemBox: @unchecked Sendable {
        let item: AVPlayerItem?
    }

    /// Le risposte di PhotoKit possono arrivare più di una volta: la continuazione riprende una sola.
    private final class Once: @unchecked Sendable {
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

/// Avvisa quando la libreria cambia (foto aggiunte a un album condiviso, Foto di iCloud aggiornate).
final class PhotoLibraryWatcher: NSObject, PHPhotoLibraryChangeObserver, @unchecked Sendable {
    private let onChange: @Sendable () -> Void

    init(onChange: @escaping @Sendable () -> Void) {
        self.onChange = onChange
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    deinit {
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        onChange()
    }
}
