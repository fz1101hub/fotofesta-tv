import FotoFestaCore
import XCTest

/// Generatore ripetibile per i test (SplitMix64).
private struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }
}

final class PuzzleTests: XCTestCase {
    func testLevelsHaveSixTwelveAndTwentyFourPieces() {
        XCTAssertEqual(PuzzleLevel.allCases.map(\.pieces), [6, 12, 24])
    }

    func testShuffleLeavesNoPieceInPlace() {
        for level in PuzzleLevel.allCases {
            for seed in 0..<200 {
                var generator = SeededGenerator(state: UInt64(seed))
                let board = PuzzleBoard.shuffled(level, using: &generator)
                XCTAssertEqual(board.positions.sorted(), Array(0..<level.pieces))
                XCTAssertTrue(board.positions.indices.allSatisfy { !board.isCorrect($0) })
                XCTAssertFalse(board.isSolved)
                XCTAssertEqual(board.moves, 0)
            }
        }
    }

    func testShuffleNeedsPiecesMinusOneSwaps() {
        for level in PuzzleLevel.allCases {
            for seed in 0..<50 {
                var generator = SeededGenerator(state: UInt64(seed))
                var board = PuzzleBoard.shuffled(level, using: &generator)
                // Strategia migliore: mettere a posto un pezzo a ogni scambio.
                while !board.isSolved {
                    let cell = board.positions.indices.first { !board.isCorrect($0) }!
                    board.swap(cell, board.positions.firstIndex(of: cell)!)
                }
                XCTAssertEqual(board.moves, level.pieces - 1)
            }
        }
    }

    func testSwapExchangesPiecesAndCountsAMove() {
        var board = PuzzleBoard(level: .easy, positions: [1, 0, 2, 3, 4, 5])
        board.swap(0, 1)
        XCTAssertEqual(board.positions, [0, 1, 2, 3, 4, 5])
        XCTAssertEqual(board.moves, 1)
        XCTAssertTrue(board.isSolved)
        board.swap(2, 2)
        XCTAssertEqual(board.moves, 1)
    }

    func testCropKeepsTheCentreInSixteenByNine() {
        XCTAssertEqual(PuzzleGeometry.crop(width: 1920, height: 1080), PixelRect(x: 0, y: 0, width: 1920, height: 1080))
        XCTAssertEqual(PuzzleGeometry.crop(width: 1080, height: 1440), PixelRect(x: 0, y: 416, width: 1080, height: 607))
        XCTAssertEqual(PuzzleGeometry.crop(width: 4000, height: 1080), PixelRect(x: 1040, y: 0, width: 1920, height: 1080))
    }

    func testPiecesCoverTheCropWithoutGaps() {
        let crop = PixelRect(x: 10, y: 20, width: 1001, height: 563)
        for level in PuzzleLevel.allCases {
            let rects = (0..<level.pieces).map { PuzzleGeometry.piece($0, of: crop, level: level) }
            XCTAssertEqual(rects.reduce(0) { $0 + $1.width * $1.height }, crop.width * crop.height)
            for row in 0..<level.rows {
                let cells = Array(rects[(row * level.columns)..<((row + 1) * level.columns)])
                XCTAssertEqual(cells.first?.x, crop.x)
                for (a, b) in zip(cells, cells.dropFirst()) { XCTAssertEqual(a.x + a.width, b.x) }
                XCTAssertEqual(cells.last.map { $0.x + $0.width }, crop.x + crop.width)
            }
            XCTAssertEqual(rects.last.map { $0.y + $0.height }, crop.y + crop.height)
        }
    }
}
