package it.fotofesta.tv.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IppTest {
    private val printer = Ipp.Printer("192.168.1.40", path = "/ipp/print")

    @Test
    fun `print job carries the operation attributes and the photo`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val bytes = Ipp.printJob(printer, 7, "Festa", "image/jpeg", jpeg, copies = 2)
        assertArrayEquals(byteArrayOf(2, 0, 0, 2, 0, 0, 0, 7), bytes.copyOfRange(0, 8))
        assertArrayEquals(jpeg, bytes.copyOfRange(bytes.size - jpeg.size, bytes.size))
        // Il messaggio ha la stessa forma di una risposta: si rilegge con il parser.
        val parsed = Ipp.parse(bytes.copyOfRange(0, bytes.size - jpeg.size))
        assertEquals(listOf("ipp://192.168.1.40:631/ipp/print"), parsed.strings("printer-uri"))
        assertEquals(listOf("image/jpeg"), parsed.strings("document-format"))
        assertEquals(listOf<Any>(2), parsed.attributes.first { it.name == "copies" }.values)
        assertEquals("http://192.168.1.40:631/ipp/print", printer.httpUrl)
    }

    @Test
    fun `multiple values of an attribute are read together`() {
        val response = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 1, 0x04) +
            attribute(0x49, "document-format-supported", "application/pdf") +
            attribute(0x49, "", "image/jpeg") +
            byteArrayOf(0x03)
        val parsed = Ipp.parse(response)
        assertTrue(parsed.ok)
        assertEquals(listOf("application/pdf", "image/jpeg"), parsed.strings("document-format-supported"))
        assertEquals("image/jpeg", Ipp.chooseFormat(parsed.strings("document-format-supported")))
        assertNull(Ipp.chooseFormat(listOf("application/pdf")))
    }

    private fun attribute(tag: Int, name: String, value: String): ByteArray {
        val n = name.toByteArray()
        val v = value.toByteArray()
        return byteArrayOf(tag.toByte(), 0, n.size.toByte()) + n + byteArrayOf(0, v.size.toByte()) + v
    }
}
