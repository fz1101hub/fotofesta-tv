import Foundation

/// Logica degli spettacoli sullo schermo: la festa in numeri, il fotomosaico, i titoli di coda e i
/// colori del codice a barre. Solo calcoli, senza interfaccia: è la stessa logica dell'app Android
/// (`PartyShows.kt`).
public enum PartyShows {
    /// La festa in numeri. `hourly` conta le foto degli invitati arrivate in ogni ora del giorno.
    public struct Stats: Equatable, Sendable {
        public let photos: Int
        public let guestPhotos: Int
        public let photographers: Int
        public let messages: Int
        public let reactions: Int
        /// Indice della reazione più usata (cuore, risata...), se ce n'è almeno una.
        public let topReaction: Int?
        public let busiestHour: Int?
        public let hourly: [Int]
        public let coins: Int
        /// Foto con più reazioni.
        public let favorite: String?
    }

    public static func stats(
        photos: [Photo],
        messages: Int,
        reactionsByPhoto: [String: Int],
        reactionsByKind: [Int],
        coins: Int,
        calendar: Calendar = .current
    ) -> Stats {
        let guests = photos.filter { if case .guest = $0.source { return true } else { return false } }
        var hourly = [Int](repeating: 0, count: 24)
        for photo in guests {
            guard let date = photo.receivedAt else { continue }
            hourly[calendar.component(.hour, from: date)] += 1
        }
        let busiest = firstMaximum(hourly)
        let top = firstMaximum(reactionsByKind)
        let ids = Set(photos.map(\.id))
        let favorite = reactionsByPhoto.filter { ids.contains($0.key) && $0.value > 0 }
            .max { $0.value < $1.value || ($0.value == $1.value && $0.key > $1.key) }?.key
        let photographers = Set(guests.compactMap { photo -> String? in
            guard let name = photo.sender?.trimmingCharacters(in: .whitespaces).lowercased(), !name.isEmpty else { return nil }
            return name
        })
        return Stats(
            photos: photos.count,
            guestPhotos: guests.count,
            photographers: photographers.count,
            messages: messages,
            reactions: reactionsByKind.reduce(0, +),
            topReaction: top,
            busiestHour: busiest,
            hourly: hourly,
            coins: coins,
            favorite: favorite
        )
    }

    /// Indice del primo valore massimo, se è maggiore di zero.
    private static func firstMaximum(_ values: [Int]) -> Int? {
        var best: Int?
        for index in values.indices where values[index] > 0 && values[index] > (best.map { values[$0] } ?? 0) { best = index }
        return best
    }

    /// Fotomosaico: per ogni casella (colore medio della foto grande in quel punto) la foto con il
    /// colore medio più vicino. Una foto già usata molte volte costa un po' di più, così il mosaico non
    /// è fatto di una foto sola. Restituisce l'indice della foto scelta per ogni casella. I colori sono
    /// interi 0xRRGGBB.
    public static func mosaic(cells: [UInt32], tiles: [UInt32], reusePenalty: Int = 900) -> [Int] {
        guard !tiles.isEmpty else { return [] }
        var uses = [Int](repeating: 0, count: tiles.count)
        return cells.map { cell in
            var best = 0
            var bestCost = Int.max
            for (index, tile) in tiles.enumerated() {
                let cost = distance(cell, tile) + uses[index] * reusePenalty
                if cost < bestCost {
                    bestCost = cost
                    best = index
                }
            }
            uses[best] += 1
            return best
        }
    }

    /// Distanza tra due colori, pesata come li vede l'occhio (il verde conta di più).
    public static func distance(_ a: UInt32, _ b: UInt32) -> Int {
        let dr = Int((a >> 16) & 0xFF) - Int((b >> 16) & 0xFF)
        let dg = Int((a >> 8) & 0xFF) - Int((b >> 8) & 0xFF)
        let db = Int(a & 0xFF) - Int(b & 0xFF)
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    /// Colore medio di pixel RGBA8 (4 byte per pixel), come 0xRRGGBB; nero se non ce ne sono.
    public static func average(rgba: [UInt8]) -> UInt32 {
        let count = rgba.count / 4
        guard count > 0 else { return 0 }
        var r = 0, g = 0, b = 0
        for pixel in 0..<count {
            r += Int(rgba[pixel * 4])
            g += Int(rgba[pixel * 4 + 1])
            b += Int(rgba[pixel * 4 + 2])
        }
        return UInt32(r / count) << 16 | UInt32(g / count) << 8 | UInt32(b / count)
    }

    public enum CreditKind: Sendable, Equatable {
        case photographers, messages, champions, guests
    }

    /// Sezione dei titoli di coda: un titolo ("Fotografi") e i nomi, già in ordine.
    public struct CreditSection: Equatable, Sendable {
        public let kind: CreditKind
        public let names: [String]
    }

    /// Titoli di coda: chi ha mandato più foto prima, poi chi ha scritto dediche, i campioni dei giochi
    /// e tutti gli altri invitati con il nome. Ogni nome compare una volta per sezione, scritto come la
    /// prima volta; le sezioni vuote non ci sono.
    public static func credits(photoSenders: [String?], messageNames: [String?], champions: [String], participants: [String]) -> [CreditSection] {
        func distinct(_ names: [String?]) -> [String] {
            var seen = Set<String>()
            return names.compactMap { $0?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
        }
        var counts: [String: (name: String, count: Int, order: Int)] = [:]
        for name in photoSenders.compactMap({ $0?.trimmingCharacters(in: .whitespaces) }) where !name.isEmpty {
            let key = name.lowercased()
            let current = counts[key]
            counts[key] = (current?.name ?? name, (current?.count ?? 0) + 1, current?.order ?? counts.count)
        }
        let photographers = counts.values.sorted { $0.count > $1.count || ($0.count == $1.count && $0.order < $1.order) }.map(\.name)
        let messages = distinct(messageNames)
        let winners = distinct(champions)
        let shown = Set((photographers + messages + winners).map { $0.lowercased() })
        return [
            CreditSection(kind: .photographers, names: photographers),
            CreditSection(kind: .messages, names: messages),
            CreditSection(kind: .champions, names: winners),
            CreditSection(kind: .guests, names: distinct(participants).filter { !shown.contains($0.lowercased()) }),
        ].filter { !$0.names.isEmpty }
    }
}
