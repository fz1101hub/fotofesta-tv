import Foundation

public enum Mosaic {
    /// Foto piccole accanto a quella grande.
    public static let sidePhotos = 2

    /// Foto accanto a quella grande: prima le ultime mostrate (dalla più recente), poi, se non bastano,
    /// quelle che seguono nella raccolta. Mai la foto grande, mai due volte la stessa.
    public static func side(of photos: [Photo], currentIndex: Int, recentIDs: [String], count: Int = sidePhotos) -> [Photo] {
        guard photos.indices.contains(currentIndex) else { return [] }
        let current = photos[currentIndex]
        var byID: [String: Photo] = [:]
        for photo in photos where byID[photo.id] == nil { byID[photo.id] = photo }
        var chosen: [Photo] = []
        func add(_ photo: Photo) {
            // I video si guardano a schermo intero, mai nelle caselle piccole.
            guard chosen.count < count, photo.id != current.id, !photo.isVideo, !chosen.contains(where: { $0.id == photo.id }) else { return }
            chosen.append(photo)
        }
        for id in recentIDs {
            if let photo = byID[id] { add(photo) }
        }
        var offset = 1
        while chosen.count < count, offset < photos.count {
            add(photos[(currentIndex + offset) % photos.count])
            offset += 1
        }
        return chosen
    }
}
