package it.fotofesta.tv.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiNetworkTest {
    @Test
    fun `protected network payload`() {
        assertEquals("WIFI:T:WPA;S:Casa Rossi;P:segreta123;;", WifiNetwork("Casa Rossi", "segreta123").qrPayload())
    }

    @Test
    fun `open network payload has no password`() {
        assertEquals("WIFI:T:nopass;S:Ospiti;;", WifiNetwork("Ospiti", "").qrPayload())
    }

    @Test
    fun `special characters are escaped`() {
        val payload = WifiNetwork("""a;b,c:d""", """p\w"d;12""").qrPayload()
        assertEquals("""WIFI:T:WPA;S:a\;b\,c\:d;P:p\\w\"d\;12;;""", payload)
    }

    @Test
    fun `validation of name and password`() {
        assertNull(WifiNetwork.validate("Casa", ""))
        assertNull(WifiNetwork.validate("Casa", "12345678"))
        assertEquals(WifiNetwork.Problem.MISSING_NAME, WifiNetwork.validate("  ", "12345678"))
        assertEquals(WifiNetwork.Problem.NAME_TOO_LONG, WifiNetwork.validate("x".repeat(33), ""))
        assertEquals(WifiNetwork.Problem.BAD_PASSWORD, WifiNetwork.validate("Casa", "1234567"))
        assertEquals(WifiNetwork.Problem.BAD_PASSWORD, WifiNetwork.validate("Casa", "x".repeat(64)))
    }
}
