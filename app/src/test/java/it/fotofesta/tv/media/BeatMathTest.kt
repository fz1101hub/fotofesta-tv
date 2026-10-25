package it.fotofesta.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class BeatMathTest {
    /** Energia con un colpo ogni [period] finestre a partire da [first], più un po' di rumore. */
    private fun energy(frames: Int, period: Int, first: Int): FloatArray {
        val random = Random(7)
        return FloatArray(frames) { i ->
            val hit = i >= first && (i - first) % period == 0
            (if (hit) 4f else 0.05f) + random.nextFloat() * 0.05f
        }
    }

    @Test
    fun `finds 120 bpm and the first beat`() {
        // Finestre da 10 ms: un battito ogni 50 = 120 BPM, il primo a 130 ms.
        val beat = BeatMath.tempo(BeatMath.onsets(energy(3_000, 50, 13)), 10f)!!
        assertEquals(120f, beat.bpm, 0.5f)
        assertEquals(130L, beat.offsetMs)
    }

    @Test
    fun `finds a faster tempo`() {
        // Un battito ogni 40 finestre = 150 BPM.
        val beat = BeatMath.tempo(BeatMath.onsets(energy(3_000, 40, 5)), 10f)!!
        assertEquals(150f, beat.bpm, 0.5f)
    }

    @Test
    fun `too little audio gives no tempo`() {
        assertNull(BeatMath.tempo(FloatArray(10), 10f))
        assertNull(BeatMath.tempo(FloatArray(100), 10f))
    }

    @Test
    fun `waits for the next beat or bar`() {
        val beat = Beat(120f, 100)
        assertEquals(100L, BeatMath.delayToBeat(1_000, beat, minMs = 0))
        assertEquals(1_100L, BeatMath.delayToBeat(1_000, beat, minMs = 0, every = 4))
        assertEquals(4_100L, BeatMath.delayToBeat(0, beat, minMs = 3_000, every = 4))
        // Prima del primo battito si aspetta il primo battito.
        assertEquals(100L, BeatMath.delayToBeat(0, beat, minMs = 0, every = 4))
    }
}
