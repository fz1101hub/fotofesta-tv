package it.fotofesta.tv.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosTest {
    @Test
    fun `white stays white, black stays black, grey becomes dots`() {
        assertTrue(EscPos.dither(EscPos.Gray(8, 2, ByteArray(16) { 0xFF.toByte() })).none { it })
        assertTrue(EscPos.dither(EscPos.Gray(8, 2, ByteArray(16))).all { it })
        val grey = EscPos.dither(EscPos.Gray(16, 16, ByteArray(256) { 128.toByte() })).count { it }
        assertTrue(grey in 96..160)
    }

    @Test
    fun `raster packs eight dots per byte with the size in front`() {
        val black = BooleanArray(10 * 2) { it == 0 || it == 9 || it == 10 }
        val bytes = EscPos.raster(black, 10, 2)
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0x00, 2, 0, 2, 0), bytes.copyOfRange(0, 8))
        assertEquals(0x80.toByte(), bytes[8])
        assertEquals(0x40.toByte(), bytes[9])
        assertEquals(0x80.toByte(), bytes[10])
        assertEquals(0x00.toByte(), bytes[11])
    }

    @Test
    fun `text loses accents instead of printing strange symbols`() {
        assertEquals("Perche citta e caffe?", String(EscPos.text("Perché città è caffè€"), Charsets.US_ASCII))
    }

    @Test
    fun `a receipt starts with init and ends with the cut`() {
        val bytes = EscPos.receipt("FotoFesta", EscPos.Gray(8, 1, ByteArray(8)), listOf("Anna"), "21:30")
        assertArrayEquals(byteArrayOf(0x1B, 0x40), bytes.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x1D, 0x56, 0x42, 0x00), bytes.copyOfRange(bytes.size - 4, bytes.size))
    }
}
