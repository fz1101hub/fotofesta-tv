import Foundation

/// Livelli del puzzle: colonne × righe, con i pezzi nelle proporzioni 16:9 della foto sullo schermo.
public enum PuzzleLevel: String, CaseIterable, Sendable {
    case easy, medium, hard

    public var columns: Int {
        switch self {
        case .easy: return 3
        case .medium: return 4
        case .hard: return 6
        }
    }

    public var rows: Int {
        switch self {
        case .easy: return 2
        case .medium: return 3
        case .hard: return 4
        }
    }

    public var pieces: Int { columns * rows }
}

/// Puzzle a scambio. `positions` dice quale pezzo c'è in ogni casella: il pezzo `p` va nella casella
/// `p`. Si sceglie una casella, poi un'altra, e i due pezzi si scambiano.
public struct PuzzleBoard: Equatable, Sendable {
    public let level: PuzzleLevel
    public private(set) var positions: [Int]
    public private(set) var moves = 0

    public init(level: PuzzleLevel, positions: [Int]) {
        precondition(positions.sorted() == Array(0..<level.pieces), "positions deve contenere ogni pezzo una volta")
        self.level = level
        self.positions = positions
    }

    /// Pezzi mescolati in un unico giro (algoritmo di Sattolo): nessun pezzo parte al suo posto e
    /// servono sempre `pezzi - 1` scambi, così ogni livello ha la stessa difficoltà a ogni partita.
    public static func shuffled<Generator: RandomNumberGenerator>(_ level: PuzzleLevel, using generator: inout Generator) -> PuzzleBoard {
        var positions = Array(0..<level.pieces)
        var index = positions.count - 1
        while index > 0 {
            let other = Int.random(in: 0..<index, using: &generator)
            positions.swapAt(index, other)
            index -= 1
        }
        return PuzzleBoard(level: level, positions: positions)
    }

    public static func shuffled(_ level: PuzzleLevel) -> PuzzleBoard {
        var generator = SystemRandomNumberGenerator()
        return shuffled(level, using: &generator)
    }

    public var isSolved: Bool { positions.indices.allSatisfy { isCorrect($0) } }

    public func isCorrect(_ cell: Int) -> Bool { positions[cell] == cell }

    /// Scambia i pezzi di due caselle; scegliere due volte la stessa casella non conta come mossa.
    public mutating func swap(_ first: Int, _ second: Int) {
        precondition(positions.indices.contains(first) && positions.indices.contains(second))
        guard first != second else { return }
        positions.swapAt(first, second)
        moves += 1
    }
}

/// Rettangolo in pixel di un'immagine.
public struct PixelRect: Equatable, Sendable {
    public var x: Int
    public var y: Int
    public var width: Int
    public var height: Int

    public init(x: Int, y: Int, width: Int, height: Int) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

public enum PuzzleGeometry {
    /// Parte centrale dell'immagine nelle proporzioni 16:9 del tabellone (le foto verticali perdono
    /// sopra e sotto).
    public static func crop(width: Int, height: Int) -> PixelRect {
        if width * 9 > height * 16 {
            let cropWidth = height * 16 / 9
            return PixelRect(x: (width - cropWidth) / 2, y: 0, width: cropWidth, height: height)
        }
        let cropHeight = width * 9 / 16
        return PixelRect(x: 0, y: (height - cropHeight) / 2, width: width, height: cropHeight)
    }

    /// Parte di `crop` che mostra il pezzo `piece`. I bordi si calcolano dalle frazioni del ritaglio,
    /// così i pezzi lo coprono tutto senza buchi né sovrapposizioni.
    public static func piece(_ piece: Int, of crop: PixelRect, level: PuzzleLevel) -> PixelRect {
        let column = piece % level.columns
        let row = piece / level.columns
        let left = crop.x + crop.width * column / level.columns
        let right = crop.x + crop.width * (column + 1) / level.columns
        let top = crop.y + crop.height * row / level.rows
        let bottom = crop.y + crop.height * (row + 1) / level.rows
        return PixelRect(x: left, y: top, width: right - left, height: bottom - top)
    }
}
