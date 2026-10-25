import Foundation

/// Una domanda di "Chi l'ha scattata?": la foto, i nomi tra cui scegliere e quello giusto.
public struct WhoTookItRound: Equatable, Sendable {
    public let photo: Photo
    public let choices: [String]
    public let answer: String
}

/// "Chi l'ha scattata?": si indovina quale invitato ha mandato la foto. Si gioca con le foto della
/// festa firmate con un nome, e servono almeno due nomi diversi.
public enum WhoTookIt {
    /// Nomi proposti a ogni domanda, compreso quello giusto.
    public static let maxChoices = 4

    /// Foto degli invitati firmate con un nome: le sole che hanno una risposta.
    public static func photos(_ all: [Photo]) -> [Photo] {
        all.filter { photo in
            guard case .guest = photo.source, let sender = photo.sender else { return false }
            return !sender.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    /// Nomi di chi ha mandato le foto, senza doppioni: "Marco" e "marco " sono la stessa persona.
    public static func senders(_ all: [Photo]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for photo in photos(all) {
            let name = (photo.sender ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if seen.insert(key(name)).inserted { result.append(name) }
        }
        return result
    }

    public static func canPlay(_ all: [Photo]) -> Bool { senders(all).count >= 2 }

    /// Nuova domanda, se possibile con una foto diversa dalla precedente.
    public static func round<Generator: RandomNumberGenerator>(
        _ all: [Photo],
        previousPhotoID: String?,
        using generator: inout Generator
    ) -> WhoTookItRound? {
        let names = senders(all)
        guard names.count >= 2 else { return nil }
        let candidates = photos(all)
        let fresh = candidates.filter { $0.id != previousPhotoID }
        guard let photo = (fresh.isEmpty ? candidates : fresh).randomElement(using: &generator) else { return nil }
        let answerKey = key(photo.sender ?? "")
        guard let answer = names.first(where: { key($0) == answerKey }) else { return nil }
        let others = Array(names.filter { key($0) != answerKey }.shuffled(using: &generator).prefix(maxChoices - 1))
        return WhoTookItRound(photo: photo, choices: (others + [answer]).shuffled(using: &generator), answer: answer)
    }

    public static func round(_ all: [Photo], previousPhotoID: String?) -> WhoTookItRound? {
        var generator = SystemRandomNumberGenerator()
        return round(all, previousPhotoID: previousPhotoID, using: &generator)
    }

    public static func isAnswer(_ round: WhoTookItRound, _ choice: String) -> Bool {
        key(choice) == key(round.answer)
    }

    private static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
