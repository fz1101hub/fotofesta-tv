package it.fotofesta.tv.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbLocationTest {
    @Test
    fun browserPathRoundTripsWithSpacesAndAccents() {
        val location = SmbLocation("192.168.1.20", 445, "Foto di famiglia", "2024/Compleanno di Léa #1")
        assertEquals("smb://192.168.1.20:445/Foto di famiglia/2024/Compleanno di Léa #1", location.browserPath)
        assertEquals(location, SmbLocation.parse(location.browserPath))
        assertEquals("Compleanno di Léa #1", location.name)
        assertEquals("2024\\Compleanno di Léa #1", location.smbPath)
    }

    @Test
    fun serverAndShareRootsParse() {
        assertEquals(SmbLocation("nas.local", 4450), SmbLocation.parse("smb://nas.local:4450"))
        assertEquals(SmbLocation("nas.local", 445, "Foto"), SmbLocation.parse("smb://nas.local:445/Foto"))
        assertEquals(SmbLocation("fe80::1", 445, "Foto"), SmbLocation.parse(SmbLocation("fe80::1", 445, "Foto").browserPath))
        assertNull(SmbLocation.parse("/storage/1234-ABCD"))
        assertNull(SmbLocation.parse("upnp:uuid:1#0"))
        assertNull(SmbLocation.parse("smb://nas"))
        assertNull(SmbLocation.parse("smb://nas:0/Foto"))
    }

    @Test
    fun childAndParentWalkTheTree() {
        val server = SmbLocation("nas", 445)
        val share = server.child("Foto")
        val folder = share.child("2024").child("Mare")
        assertEquals(SmbLocation("nas", 445, "Foto", "2024/Mare"), folder)
        assertEquals(share.child("2024"), folder.parent())
        assertEquals(share, share.child("2024").parent())
        assertEquals(server, share.parent())
        assertNull(server.parent())
        assertEquals("nas", server.name)
        assertEquals("Foto", share.name)
    }

    @Test
    fun typedAddressesInEveryUsualForm() {
        assertEquals(SmbLocation("192.168.1.20"), SmbAddress.parse(" 192.168.1.20 "))
        assertEquals(SmbLocation("nas.local", 4450), SmbAddress.parse("nas.local:4450"))
        assertEquals(SmbLocation("NAS", 445, "Foto", "2024/Mare"), SmbAddress.parse("\\\\NAS\\Foto\\2024\\Mare\\"))
        assertEquals(SmbLocation("nas", 445, "Foto di casa", "Estate"), SmbAddress.parse("smb://nas/Foto%20di%20casa/Estate"))
        assertEquals(SmbLocation("nas", 445, "Foto"), SmbAddress.parse("SMB://mario@nas/Foto"))
        assertEquals(SmbLocation("fe80::1"), SmbAddress.parse("fe80::1"))
        assertEquals(SmbLocation("fe80::1", 4450, "Foto"), SmbAddress.parse("[fe80::1]:4450/Foto"))
        assertEquals(SmbLocation("nas", 445, "Foto"), SmbAddress.parse("nas/Foto/../"))
    }

    @Test
    fun badAddressesAreRejected() {
        listOf("", "   ", "nas:abc", "nas:70000", "na s", "http://nas", "nas;rm", "[fe80::1", "\\\\").forEach { input ->
            assertNull(input, SmbAddress.parse(input))
        }
    }

    @Test
    fun loginsSplitTheDomainAndHideThePassword() {
        assertEquals(SmbLogin("mario", "pw", "UFFICIO"), SmbLogin.of("UFFICIO\\mario", "pw"))
        assertEquals(SmbLogin("mario@ufficio.it", "pw"), SmbLogin.of(" mario@ufficio.it ", "pw"))
        assertTrue(SmbLogin.of("", "ignorata").guest)
        assertEquals("", SmbLogin.of("  ", "ignorata").password)
        assertFalse(SmbLogin("mario", "segreta").toString().contains("segreta"))
    }
}
