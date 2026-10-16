package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTest {
    private val hour = 3_600_000L
    private val minute = 60_000L

    /** Istante UTC delle [hours]:[minutes]:[seconds] del 1° gennaio 2027. */
    private fun at(hours: Int, minutes: Int = 0, seconds: Int = 0): Long =
        1_798_761_600_000L + hours * hour + minutes * minute + seconds * 1_000L

    @Test
    fun `more than an hour before nothing is shown`() {
        assertEquals(CountdownPhase.Hidden, Countdown.phase(at(22, 59), 0, 0))
    }

    @Test
    fun `in the last hour the remaining time is shown`() {
        assertEquals(CountdownPhase.Showing(3_600), Countdown.phase(at(23, 0), 0, 0))
        assertEquals(CountdownPhase.Showing(754), Countdown.phase(at(23, 47, 26), 0, 0))
        assertEquals(CountdownPhase.Showing(11), Countdown.phase(at(23, 59, 49), 0, 0))
    }

    @Test
    fun `the last ten seconds are big numbers`() {
        assertEquals(CountdownPhase.Final(10), Countdown.phase(at(23, 59, 50), 0, 0))
        assertEquals(CountdownPhase.Final(1), Countdown.phase(at(23, 59, 59), 0, 0))
        // Mezzo secondo prima conta ancora come 1.
        assertEquals(CountdownPhase.Final(1), Countdown.phase(at(0) - 500, 0, 0))
    }

    @Test
    fun `the moment is celebrated for twenty seconds`() {
        assertEquals(CountdownPhase.Celebrate, Countdown.phase(at(0), 0, 0))
        assertEquals(CountdownPhase.Celebrate, Countdown.phase(at(0, 0, 19), 0, 0))
        assertEquals(CountdownPhase.Hidden, Countdown.phase(at(0, 0, 20), 0, 0))
    }

    @Test
    fun `local time follows the offset from UTC`() {
        // Torta alle 21:30 in Italia d'inverno (UTC+1): le 20:15 UTC sono le 21:15 locali.
        assertEquals(CountdownPhase.Showing(900), Countdown.phase(at(20, 15), 21 * 60 + 30, (1 * hour).toInt()))
    }

    @Test
    fun `remaining time is formatted as minutes and seconds`() {
        assertEquals("12:34", Countdown.format(754))
        assertEquals("0:09", Countdown.format(9))
        assertEquals("1:00:00", Countdown.format(3_600))
    }

    @Test
    fun `times are offered every quarter of an hour`() {
        assertEquals(96, Countdown.TIME_OPTIONS.size)
        assertEquals(listOf(0, 15, 30, 45), Countdown.TIME_OPTIONS.take(4))
        assertEquals(23 * 60 + 45, Countdown.TIME_OPTIONS.last())
    }
}
