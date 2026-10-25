import Foundation

/// Generatore ripetibile (SplitMix64): le stesse differenze e lo stesso quadro per lo stesso seme.
public struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    public init(seed: UInt64) {
        state = seed
    }

    public mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }

    mutating func unit() -> Double {
        Double(next() >> 11) / Double(1 << 53)
    }
}

/// Logica dei giochi e degli spettacoli dell'ondata 11: chi non c'è, quiz scritti dalla regia,
/// scelte per "chi è da piccolo" e "indovina la canzone", le note della festa, le differenze da
/// trovare, il quadro della festa e le foto di "accadde oggi".
public enum PartyGames {
    public static let maxQuestions = 30

    // MARK: Chi non c'è

    /// Invitati attesi divisi tra arrivati e mancanti, confrontando i nomi senza maiuscole e accenti.
    public struct Roll: Equatable, Sendable {
        public let present: [String]
        public let missing: [String]
    }

    public static func roll(expected: [String], seen: [String]) -> Roll {
        let seenKeys = seen.map(nameKey).filter { !$0.isEmpty }
        var present: [String] = []
        var missing: [String] = []
        for name in expected {
            let key = nameKey(name)
            let first = firstWord(key)
            // "Marco" dalla lista vale anche per "Marco R." dal telefono, e viceversa.
            if seenKeys.contains(where: { $0 == key || firstWord($0) == first }) { present.append(name) } else { missing.append(name) }
        }
        return Roll(present: present, missing: missing)
    }

    static func nameKey(_ name: String) -> String {
        let folded = name.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .lowercased()
        let kept = folded.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == " " }
        return String(String.UnicodeScalarView(kept)).split(separator: " ").joined(separator: " ")
    }

    private static func firstWord(_ key: String) -> Substring {
        key.split(separator: " ", maxSplits: 1).first ?? Substring(key)
    }

    // MARK: Quiz scritti dalla regia: "Domanda? | risposta giusta | sbagliata | sbagliata"

    public struct Question: Equatable, Sendable {
        public let text: String
        public let right: String
        public let wrong: [String]
    }

    public static func parseQuiz(_ text: String) -> [Question] {
        var questions: [Question] = []
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            guard parts.count >= 3 else { continue }
            questions.append(Question(
                text: String(parts[0].prefix(160)),
                right: String(parts[1].prefix(60)),
                wrong: parts.dropFirst(2).prefix(3).map { String($0.prefix(60)) }
            ))
            if questions.count == maxQuestions { break }
        }
        return questions
    }

    /// Risposte mescolate con l'indice di quella giusta.
    public static func shuffle<G: RandomNumberGenerator>(_ question: Question, using generator: inout G) -> (choices: [String], right: Int) {
        let choices = ([question.right] + question.wrong).shuffled(using: &generator)
        return (choices, choices.firstIndex(of: question.right) ?? 0)
    }

    public static func shuffle(_ question: Question) -> (choices: [String], right: Int) {
        var generator = SystemRandomNumberGenerator()
        return shuffle(question, using: &generator)
    }

    /// `correct` e altri nomi presi da `pool` (fino a quattro risposte), mescolati.
    public static func nameChoices<G: RandomNumberGenerator>(correct: String, pool: [String], using generator: inout G) -> (choices: [String], right: Int) {
        var seen = Set([correct.lowercased()])
        var others: [String] = []
        for name in pool.shuffled(using: &generator) where seen.insert(name.lowercased()).inserted {
            others.append(name)
            if others.count == 3 { break }
        }
        let choices = (others + [correct]).shuffled(using: &generator)
        return (choices, choices.firstIndex(of: correct) ?? 0)
    }

    public static func nameChoices(correct: String, pool: [String]) -> (choices: [String], right: Int) {
        var generator = SystemRandomNumberGenerator()
        return nameChoices(correct: correct, pool: pool, using: &generator)
    }

    /// Titolo leggibile dal nome di un file musicale: "01 - Gloria (Remastered).mp3" → "Gloria".
    public static func songTitle(_ fileName: String) -> String {
        var name = String(fileName.split(separator: "/").last ?? Substring(fileName))
        if let dot = name.lastIndex(of: "."), dot != name.startIndex { name = String(name[..<dot]) }
        name = name.replacingOccurrences(of: "_", with: " ")
        name = name.replacingOccurrences(of: #"^\d{1,3}\s*[-.)]?\s*"#, with: "", options: .regularExpression)
        name = name.replacingOccurrences(
            of: #"\s*[(\[][^)\]]*(remaster|live|edit|version|mix|radio)[^)\]]*[)\]]"#,
            with: "", options: [.regularExpression, .caseInsensitive]
        )
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? fileName : trimmed
    }

    // MARK: La festa suona: una nota per foto, dalla luminosità e dal colore

    public struct Note: Equatable, Sendable {
        public let frequency: Double
        public let milliseconds: Int
    }

    private static let pentatonic = [0, 2, 4, 7, 9]

    /// Scala pentatonica (suona sempre bene): la luminosità sceglie l'altezza, la saturazione la
    /// durata. Due ottave a partire dal La 220 Hz. Colori come 0xRRGGBB.
    public static func notes(_ colors: [UInt32]) -> [Note] {
        colors.map { color in
            let r = Double((color >> 16) & 0xFF)
            let g = Double((color >> 8) & 0xFF)
            let b = Double(color & 0xFF)
            let brightness = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            let saturation = (max(r, g, b) - min(r, g, b)) / 255
            let steps = pentatonic.count * 2
            let step = min(max(Int(brightness * Double(steps - 1)), 0), steps - 1)
            let semitones = Double(pentatonic[step % pentatonic.count] + 12 * (step / pentatonic.count))
            return Note(frequency: 220 * pow(2, semitones / 12), milliseconds: saturation > 0.35 ? 260 : 520)
        }
    }

    // MARK: Trova le differenze

    public enum DifferenceKind: CaseIterable, Sendable {
        case dot, star, mirror, tint, hide
    }

    /// Differenza nella foto di destra: centro e raggio in frazioni della foto; colore 0xRRGGBB.
    public struct Difference: Equatable, Sendable {
        public let x: Double
        public let y: Double
        public let radius: Double
        public let kind: DifferenceKind
        public let color: UInt32
    }

    /// `count` differenze lontane tra loro e dai bordi, uguali per lo stesso `seed`.
    public static func differences(seed: UInt64, count: Int = 5) -> [Difference] {
        var random = SeededGenerator(seed: seed)
        let palette: [UInt32] = [0xFF7A57, 0xFF4F9A, 0x8B6CFF, 0xFFC145, 0x4CD787]
        var result: [Difference] = []
        var attempts = 0
        while result.count < count, attempts < 500 {
            attempts += 1
            let x = 0.12 + random.unit() * 0.76
            let y = 0.14 + random.unit() * 0.72
            let radius = 0.045 + random.unit() * 0.03
            if result.contains(where: { ($0.x - x) * ($0.x - x) + ($0.y - y) * ($0.y - y) < 0.04 }) { continue }
            let kind = DifferenceKind.allCases[result.count % DifferenceKind.allCases.count]
            result.append(Difference(x: x, y: y, radius: radius, kind: kind, color: palette[Int(random.next() % UInt64(palette.count))]))
        }
        return result
    }

    // MARK: L'opera d'arte della festa: rettangoli alla Mondrian con i colori delle foto

    /// Rettangolo in frazioni della tela, con il colore di una foto.
    public struct Block: Equatable, Sendable {
        public let left: Double
        public let top: Double
        public let right: Double
        public let bottom: Double
        public let color: UInt32
    }

    public static func artwork(_ colors: [UInt32], seed: UInt64) -> [Block] {
        guard !colors.isEmpty else { return [] }
        var random = SeededGenerator(seed: seed)
        var blocks: [Block] = []
        func split(_ left: Double, _ top: Double, _ right: Double, _ bottom: Double, _ depth: Int) {
            let width = right - left
            let height = bottom - top
            if depth >= 5 || (depth >= 2 && random.unit() < 0.25) || max(width, height) < 0.12 {
                blocks.append(Block(left: left, top: top, right: right, bottom: bottom, color: colors[blocks.count % colors.count]))
                return
            }
            let ratio = 0.3 + random.unit() * 0.4
            if width >= height {
                let middle = left + width * ratio
                split(left, top, middle, bottom, depth + 1)
                split(middle, top, right, bottom, depth + 1)
            } else {
                let middle = top + height * ratio
                split(left, top, right, middle, depth + 1)
                split(left, middle, right, bottom, depth + 1)
            }
        }
        split(0, 0, 1, 1, 0)
        return blocks
    }

    // MARK: Accadde oggi

    /// La foto è stata scattata oggi (stesso giorno e mese) in un anno passato.
    public static func sameDayPastYear(_ taken: Date, now: Date, calendar: Calendar = .current) -> Bool {
        let a = calendar.dateComponents([.year, .month, .day], from: taken)
        let b = calendar.dateComponents([.year, .month, .day], from: now)
        return a.month == b.month && a.day == b.day && (a.year ?? 0) < (b.year ?? 0)
    }

    /// Anni passati da una foto di "accadde oggi".
    public static func yearsAgo(_ taken: Date, now: Date, calendar: Calendar = .current) -> Int {
        (calendar.component(.year, from: now)) - calendar.component(.year, from: taken)
    }
}
