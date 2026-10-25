package it.fotofesta.tv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsPacketsTest {
    private fun query(name: String, type: Int, unicast: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(value: Int) { out.write(value shr 8 and 0xFF); out.write(value and 0xFF) }
        u16(0); u16(0); u16(1); u16(0); u16(0); u16(0)
        name.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        u16(type)
        u16(if (unicast) 0x8001 else 1)
        return out.toByteArray()
    }

    @Test
    fun `answers only questions for our name`() {
        assertEquals(false, MdnsPackets.asksFor(query("festa.local", 1), "festa.local"))
        assertEquals(true, MdnsPackets.asksFor(query("FESTA.local", 255, unicast = true), "festa.local"))
        assertNull(MdnsPackets.asksFor(query("altra.local", 1), "festa.local"))
        assertNull(MdnsPackets.asksFor(query("festa.local", 28), "festa.local"))
        assertNull(MdnsPackets.asksFor(byteArrayOf(1, 2, 3), "festa.local"))
    }

    @Test
    fun `the answer carries the address`() {
        val answer = MdnsPackets.answer("festa.local", byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        assertEquals(0x84, answer[2].toInt() and 0xFF)
        assertEquals(1, answer[7].toInt())
        val tail = answer.takeLast(4).map { it.toInt() and 0xFF }
        assertEquals(listOf(192, 168, 1, 20), tail)
        // Una risposta non è una domanda.
        assertNull(MdnsPackets.asksFor(answer, "festa.local"))
    }
}
