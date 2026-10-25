package it.fotofesta.tv.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoRangeTest {
    @Test
    fun `without a range the first chunk is sent`() {
        assertEquals(0L..999L, discoRange(null, 5_000, 1_000))
        assertEquals(0L..299L, discoRange(null, 300, 1_000))
    }

    @Test
    fun `ranges are clamped to the file and to the chunk`() {
        assertEquals(100L..199L, discoRange("bytes=100-199", 5_000, 1_000))
        assertEquals(4_000L..4_999L, discoRange("bytes=4000-", 5_000, 1_000))
        assertEquals(100L..1_099L, discoRange("bytes=100-", 5_000, 1_000))
        assertEquals(4_900L..4_999L, discoRange("bytes=4900-9999", 5_000, 1_000))
        assertEquals(4_500L..4_999L, discoRange("bytes=-500", 5_000, 1_000))
        assertEquals(0L..999L, discoRange("bytes=-9000", 5_000, 1_000))
    }

    @Test
    fun `invalid ranges are refused`() {
        assertNull(discoRange("bytes=5000-", 5_000, 1_000))
        assertNull(discoRange("bytes=300-200", 5_000, 1_000))
        assertNull(discoRange("bytes=0-1,5-9", 5_000, 1_000))
        assertNull(discoRange("items=0-1", 5_000, 1_000))
        assertNull(discoRange("bytes=x-", 5_000, 1_000))
        assertNull(discoRange("bytes=-0", 5_000, 1_000))
        assertNull(discoRange(null, 0, 1_000))
    }
}
