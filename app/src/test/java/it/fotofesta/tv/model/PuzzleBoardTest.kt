package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PuzzleBoardTest {
    @Test
    fun `levels keep pieces in sixteen by nine proportions`() {
        assertEquals(listOf(6, 12, 24), PuzzleLevel.entries.map { it.pieces })
    }

    @Test
    fun `shuffle leaves no piece in place`() {
        PuzzleLevel.entries.forEach { level ->
            repeat(200) { seed ->
                val board = PuzzleBoard.shuffled(level, Random(seed))
                assertEquals(List(level.pieces) { it }, board.positions.sorted())
                assertTrue(board.positions.indices.none { board.isCorrect(it) })
                assertFalse(board.solved)
                assertEquals(0, board.moves)
            }
        }
    }

    @Test
    fun `shuffle is a single cycle so it needs pieces minus one swaps`() {
        PuzzleLevel.entries.forEach { level ->
            repeat(50) { seed ->
                var board = PuzzleBoard.shuffled(level, Random(seed))
                // Strategia migliore: mettere a posto un pezzo a ogni scambio.
                while (!board.solved) {
                    val cell = board.positions.indices.first { !board.isCorrect(it) }
                    board = board.swap(cell, board.positions.indexOf(cell))
                }
                assertEquals(level.pieces - 1, board.moves)
            }
        }
    }

    @Test
    fun `swap exchanges two pieces and counts a move`() {
        val board = PuzzleBoard(PuzzleLevel.EASY, listOf(1, 0, 2, 3, 4, 5))
        val swapped = board.swap(0, 1)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), swapped.positions)
        assertEquals(1, swapped.moves)
        assertTrue(swapped.solved)
    }

    @Test
    fun `choosing the same cell twice is not a move`() {
        val board = PuzzleBoard.shuffled(PuzzleLevel.MEDIUM, Random(1))
        assertSame(board, board.swap(3, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `positions must contain every piece once`() {
        PuzzleBoard(PuzzleLevel.EASY, listOf(0, 0, 2, 3, 4, 5))
    }

    @Test
    fun `crop keeps the centre in sixteen by nine`() {
        assertEquals(PixelRect(0, 0, 1920, 1080), puzzleCrop(1920, 1080))
        // Foto verticale 3:4: si tiene la fascia centrale.
        assertEquals(PixelRect(0, 416, 1080, 607), puzzleCrop(1080, 1440))
        // Panorama: si tiene la parte centrale in larghezza.
        assertEquals(PixelRect(1040, 0, 1920, 1080), puzzleCrop(4000, 1080))
    }

    @Test
    fun `pieces cover the crop without gaps`() {
        val crop = PixelRect(10, 20, 1001, 563)
        PuzzleLevel.entries.forEach { level ->
            val rects = (0 until level.pieces).map { level.pieceRect(crop, it) }
            assertEquals(crop.width.toLong() * crop.height, rects.sumOf { it.width.toLong() * it.height })
            (0 until level.rows).forEach { row ->
                val cells = rects.subList(row * level.columns, (row + 1) * level.columns)
                assertEquals(crop.left, cells.first().left)
                cells.zipWithNext().forEach { (a, b) -> assertEquals(a.left + a.width, b.left) }
                assertEquals(crop.left + crop.width, cells.last().left + cells.last().width)
            }
            assertEquals(crop.top + crop.height, rects.last().top + rects.last().height)
        }
    }
}
