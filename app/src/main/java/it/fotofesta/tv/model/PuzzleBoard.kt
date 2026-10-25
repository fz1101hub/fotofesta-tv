package it.fotofesta.tv.model

import kotlin.random.Random

/** Livelli del puzzle: colonne × righe, con i pezzi nelle proporzioni 16:9 della foto sullo schermo. */
enum class PuzzleLevel(val columns: Int, val rows: Int) {
    EASY(3, 2),
    MEDIUM(4, 3),
    HARD(6, 4),
    ;

    val pieces: Int get() = columns * rows
}

/**
 * Puzzle a scambio. [positions] dice quale pezzo c'è in ogni casella: il pezzo `p` va nella casella
 * `p`. Si sceglie una casella, poi un'altra, e i due pezzi si scambiano.
 */
data class PuzzleBoard(val level: PuzzleLevel, val positions: List<Int>, val moves: Int = 0) {
    init {
        require(positions.sorted() == List(level.pieces) { it }) { "positions deve contenere ogni pezzo una volta" }
    }

    val solved: Boolean get() = positions.indices.all { isCorrect(it) }

    fun isCorrect(cell: Int): Boolean = positions[cell] == cell

    /** Scambia i pezzi di due caselle; scegliere due volte la stessa casella non conta come mossa. */
    fun swap(first: Int, second: Int): PuzzleBoard {
        require(first in positions.indices && second in positions.indices)
        if (first == second) return this
        val next = positions.toMutableList()
        next[first] = positions[second]
        next[second] = positions[first]
        return copy(positions = next, moves = moves + 1)
    }

    companion object {
        /**
         * Pezzi mescolati in un unico giro (algoritmo di Sattolo): nessun pezzo parte al suo posto e
         * servono sempre `pezzi - 1` scambi, così ogni livello ha la stessa difficoltà a ogni partita.
         */
        fun shuffled(level: PuzzleLevel, random: Random = Random.Default): PuzzleBoard {
            val positions = MutableList(level.pieces) { it }
            for (index in positions.lastIndex downTo 1) {
                val other = random.nextInt(index)
                val piece = positions[index]
                positions[index] = positions[other]
                positions[other] = piece
            }
            return PuzzleBoard(level, positions)
        }
    }
}

/** Rettangolo in pixel di un'immagine. */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** Parte centrale dell'immagine nelle proporzioni 16:9 del tabellone (le foto verticali perdono sopra e sotto). */
fun puzzleCrop(width: Int, height: Int): PixelRect =
    if (width.toLong() * 9 > height.toLong() * 16) {
        val cropWidth = (height.toLong() * 16 / 9).toInt()
        PixelRect((width - cropWidth) / 2, 0, cropWidth, height)
    } else {
        val cropHeight = (width.toLong() * 9 / 16).toInt()
        PixelRect(0, (height - cropHeight) / 2, width, cropHeight)
    }

/**
 * Parte di [crop] che mostra il pezzo [piece]. I bordi si calcolano dalle frazioni del ritaglio, così
 * i pezzi lo coprono tutto senza buchi né sovrapposizioni anche quando non si divide in parti uguali.
 */
fun PuzzleLevel.pieceRect(crop: PixelRect, piece: Int): PixelRect {
    val column = piece % columns
    val row = piece / columns
    val left = crop.left + crop.width * column / columns
    val right = crop.left + crop.width * (column + 1) / columns
    val top = crop.top + crop.height * row / rows
    val bottom = crop.top + crop.height * (row + 1) / rows
    return PixelRect(left, top, right - left, bottom - top)
}
