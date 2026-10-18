package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KenBurnsTest {
    @Test
    fun `motion is deterministic per photo`() {
        assertEquals(KenBurnsMotion.forPhoto("usb:content://a/1"), KenBurnsMotion.forPhoto("usb:content://a/1"))
    }

    @Test
    fun `photo edges never enter the frame`() {
        (0 until 500).map { KenBurnsMotion.forPhoto("photo-$it") }.forEach { motion ->
            (0..20).map { it / 20f }.forEach { progress ->
                val scale = motion.scaleAt(progress)
                val margin = (scale - 1f) / 2f
                assertTrue(scale in 1f..KenBurnsMotion.MAX_ZOOM)
                assertTrue("x $motion at $progress", abs(motion.offsetXAt(progress)) <= margin + 1e-6f)
                assertTrue("y $motion at $progress", abs(motion.offsetYAt(progress)) <= margin + 1e-6f)
            }
        }
    }

    @Test
    fun `both zoom directions are used`() {
        val motions = (0 until 50).map { KenBurnsMotion.forPhoto("p$it") }
        assertTrue(motions.any { it.endScale > it.startScale })
        assertTrue(motions.any { it.endScale < it.startScale })
    }

    @Test
    fun `progress is clamped`() {
        val motion = KenBurnsMotion(1f, 1.1f, 1f, 0f)
        assertEquals(1.1f, motion.scaleAt(2f), 1e-6f)
        assertEquals(0f, motion.offsetXAt(-1f), 1e-6f)
    }
}
