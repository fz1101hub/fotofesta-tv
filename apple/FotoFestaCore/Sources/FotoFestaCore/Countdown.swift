import Foundation

/// Momento del conto alla rovescia: l'app ne mostra il nome e, quando arriva, il messaggio.
public enum CountdownEvent: String, Codable, CaseIterable, Sendable {
    case midnight, cake, toast, surprise
}

/// Cosa mostra il conto alla rovescia in un dato istante.
public enum CountdownPhase: Equatable, Sendable {
    /// Manca più di un'ora: non si mostra nulla.
    case hidden
    /// Nell'ultima ora: etichetta piccola con i secondi che mancano.
    case showing(Int)
    /// Negli ultimi secondi: numeri grandi al centro.
    case final(Int)
    /// Appena arrivato il momento: il messaggio di festa.
    case celebrate
}

public enum Countdown {
    public static let showBeforeSeconds = 3_600
    public static let finalSeconds = 10
    public static let celebrateSeconds = 20

    /// Orari tra cui scegliere, in minuti dalla mezzanotte: ogni quarto d'ora.
    public static let timeOptions: [Int] = (0..<(24 * 4)).map { $0 * 15 }

    private static let dayMillis: Int64 = 86_400_000

    /// Fase all'istante `date` per un momento che torna ogni giorno a `minutesOfDay` nell'ora locale
    /// di `timeZone`.
    public static func phase(at date: Date, minutesOfDay: Int, timeZone: TimeZone) -> CountdownPhase {
        let now = Int64((date.timeIntervalSince1970 * 1_000).rounded(.down))
        let offset = Int64(timeZone.secondsFromGMT(for: date)) * 1_000
        let sinceMidnight = positiveModulo(now + offset, dayMillis)
        let target = Int64(minutesOfDay) * 60_000
        let untilTarget = positiveModulo(target - sinceMidnight, dayMillis)
        let sinceTarget = positiveModulo(sinceMidnight - target, dayMillis)
        let seconds = Int((untilTarget + 999) / 1_000)
        if sinceTarget < Int64(celebrateSeconds) * 1_000 { return .celebrate }
        if seconds <= finalSeconds { return .final(seconds) }
        if seconds <= showBeforeSeconds { return .showing(seconds) }
        return .hidden
    }

    /// "12:34" o "1:02:03": tempo che manca.
    public static func format(_ seconds: Int) -> String {
        let hours = seconds / 3_600
        let minutes = seconds % 3_600 / 60
        let rest = seconds % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, rest)
            : String(format: "%d:%02d", minutes, rest)
    }

    private static func positiveModulo(_ value: Int64, _ modulus: Int64) -> Int64 {
        let remainder = value % modulus
        return remainder >= 0 ? remainder : remainder + modulus
    }
}
