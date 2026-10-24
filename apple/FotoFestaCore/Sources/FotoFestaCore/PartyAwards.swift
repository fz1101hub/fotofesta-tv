import Foundation

/// Chi ha mandato più foto: nome, quante e l'ultima arrivata, da mostrare sulla scheda.
public struct TopPhotographer: Equatable, Sendable {
    public let name: String
    public let count: Int
    public let photo: Photo
}

/// Foto con più reazioni dai telefoni e quante ne ha avute.
public struct FavoritePhoto: Equatable, Sendable {
    public let photo: Photo
    public let reactions: Int
}

/// Premi di fine festa, calcolati dalle foto degli invitati in mostra: "Obiettivo d'oro" a chi ne ha
/// mandate di più, la prima e l'ultima foto della festa e la "Foto della serata", quella con più
/// reazioni dai telefoni (tra tutte le foto in mostra).
public struct PartyAwards: Equatable, Sendable {
    public let photos: Int
    /// Invitati diversi che hanno firmato almeno una foto.
    public let guests: Int
    public let topPhotographer: TopPhotographer?
    public let firstPhoto: Photo?
    /// Ultima foto arrivata; nil se è anche la prima.
    public let lastPhoto: Photo?
    public let favorite: FavoritePhoto?

    /// `reactions`: reazioni ricevute da ogni foto, per identificativo.
    public init(_ all: [Photo], reactions: [String: Int] = [:]) {
        let guestPhotos = all
            .filter { photo in
                if case .guest = photo.source { return true }
                return false
            }
            .sorted { a, b in
                let first = a.receivedAt ?? .distantFuture
                let second = b.receivedAt ?? .distantFuture
                return first != second ? first < second : a.id < b.id
            }
        // Invitati nell'ordine della prima foto: a parità di foto vince chi ha cominciato prima.
        var order: [String] = []
        var groups: [String: [Photo]] = [:]
        for photo in guestPhotos {
            guard let sender = photo.sender?.trimmingCharacters(in: .whitespacesAndNewlines), !sender.isEmpty else { continue }
            let key = sender.lowercased()
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(photo)
        }
        var top: TopPhotographer?
        for key in order {
            guard let group = groups[key], let first = group.first, let last = group.last else { continue }
            if group.count > (top?.count ?? 0) {
                let name = (first.sender ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                top = TopPhotographer(name: name, count: group.count, photo: last)
            }
        }
        photos = guestPhotos.count
        guests = order.count
        topPhotographer = top
        firstPhoto = guestPhotos.first
        lastPhoto = guestPhotos.count > 1 ? guestPhotos.last : nil
        // A parità di reazioni vince la foto che compare prima nella raccolta.
        var best: FavoritePhoto?
        for photo in all {
            let count = reactions[photo.id] ?? 0
            if count > (best?.reactions ?? 0) { best = FavoritePhoto(photo: photo, reactions: count) }
        }
        favorite = best
    }
}
