import Foundation

/// Chi ha mandato più foto: nome, quante e l'ultima arrivata, da mostrare sulla scheda.
public struct TopPhotographer: Equatable, Sendable {
    public let name: String
    public let count: Int
    public let photo: Photo
}

/// Premi di fine festa, calcolati dalle foto degli invitati in mostra: "Obiettivo d'oro" a chi ne ha
/// mandate di più, la prima e l'ultima foto della festa.
public struct PartyAwards: Equatable, Sendable {
    public let photos: Int
    /// Invitati diversi che hanno firmato almeno una foto.
    public let guests: Int
    public let topPhotographer: TopPhotographer?
    public let firstPhoto: Photo?
    /// Ultima foto arrivata; nil se è anche la prima.
    public let lastPhoto: Photo?

    public init(_ all: [Photo]) {
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
    }
}
