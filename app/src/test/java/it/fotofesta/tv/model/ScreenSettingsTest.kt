package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenSettingsTest {
    private val heavy = SlideshowSettings(
        fitMode = FitMode.BLUR,
        kenBurns = true,
        layout = SlideshowLayout.MOSAIC,
        transition = TransitionType.ZAPPING,
        confetti = true,
    )

    @Test
    fun `light mode drops the heavy effects`() {
        val screen = heavy.copy(lightMode = true).forScreen()
        assertEquals(FitMode.CONTAIN, screen.fitMode)
        assertFalse(screen.kenBurns)
        assertEquals(SlideshowLayout.SINGLE, screen.layout)
        assertEquals(TransitionType.FADE, screen.transition)
        assertFalse(screen.confetti)
    }

    @Test
    fun `light mode keeps cheap choices`() {
        val screen = SlideshowSettings(fitMode = FitMode.CROP, transition = TransitionType.SLIDE, lightMode = true).forScreen()
        assertEquals(FitMode.CROP, screen.fitMode)
        assertEquals(TransitionType.SLIDE, screen.transition)
    }

    @Test
    fun `projector replaces only the blurred background`() {
        val screen = heavy.copy(projectorMode = true).forScreen()
        assertEquals(FitMode.CONTAIN, screen.fitMode)
        assertEquals(SlideshowLayout.MOSAIC, screen.layout)
        assertEquals(heavy.copy(projectorMode = true, fitMode = FitMode.CONTAIN), screen)
    }

    @Test
    fun `without the tv modes nothing changes`() {
        assertEquals(heavy, heavy.forScreen())
    }
}
