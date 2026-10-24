package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HostRemoteTest {
    private var now = 5_000_000L
    private val host = HostRemote({ now })
    private val phone = "192.168.1.20"

    private fun token(pin: String = host.pin()!!, client: String = phone): String =
        (host.login(pin, client) as HostRemote.Login.Accepted).token

    @Test
    fun `the pin opens the host page and a new party changes it`() {
        val pin = host.pin()!!
        assertEquals(HostRemote.PIN_LENGTH, pin.length)
        assertTrue(pin.all(Char::isDigit))
        val token = token(pin)
        assertTrue(host.isHost(token))
        assertFalse(host.isHost("altro"))
        assertFalse(host.isHost(null))
        host.reset()
        assertFalse(host.isHost(token))
    }

    @Test
    fun `wrong pins are limited per phone and lock the host page after many errors`() {
        val wrong = if (host.pin() == "000000") "111111" else "000000"
        repeat(HostRemote.MAX_ATTEMPTS_PER_CLIENT) { assertEquals(HostRemote.Login.Wrong, host.login(wrong, phone)) }
        assertEquals(HostRemote.Login.RateLimited, host.login(host.pin(), phone))
        now += HostRemote.ATTEMPT_WINDOW_MS
        // Da altri indirizzi i tentativi continuano, finché il totale degli errori blocca tutti.
        var client = 0
        repeat(HostRemote.MAX_FAILURES - HostRemote.MAX_ATTEMPTS_PER_CLIENT) {
            if (it % HostRemote.MAX_ATTEMPTS_PER_CLIENT == 0) client++
            host.login(wrong, "10.0.0.$client")
        }
        assertEquals(HostRemote.Login.Locked, host.login(host.pin(), "10.0.0.99"))
        now += HostRemote.LOCK_MS
        assertTrue(host.login(host.pin(), "10.0.0.99") is HostRemote.Login.Accepted)
    }

    @Test
    fun `the pin can be typed with spaces`() {
        val spaced = host.pin()!!.chunked(3).joinToString(" ")
        assertTrue(host.login(spaced, phone) is HostRemote.Login.Accepted)
    }

    @Test
    fun `disabled host page refuses logins and hides the pin`() {
        val token = token()
        host.setEnabled(false)
        assertNull(host.pin())
        assertFalse(host.isHost(token))
        assertEquals(HostRemote.Login.Disabled, host.login("123456", phone))
    }

    @Test
    fun `commands reach the TV only with a valid token`() {
        val received = mutableListOf<PartyCommand>()
        host.onCommand = { received += it }
        val token = token()
        assertEquals(401, host.handle("sbagliato", mapOf("command" to "next")).first)
        assertEquals(200, host.handle(token, mapOf("command" to "next")).first)
        assertEquals(200, host.handle(token, mapOf("command" to "approve_photo", "id" to "abc-123")).first)
        assertEquals(400, host.handle(token, mapOf("command" to "approve_photo", "id" to "../etc")).first)
        assertEquals(200, host.handle(token, mapOf("command" to "announce", "text" to "  Il buffet\nè aperto!  ")).first)
        assertEquals(400, host.handle(token, mapOf("command" to "announce", "text" to "  ")).first)
        assertEquals(400, host.handle(token, mapOf("command" to "boh")).first)
        assertEquals(
            listOf(PartyCommand.Next, PartyCommand.ApprovePhoto("abc-123"), PartyCommand.Announce("Il buffet è aperto!")),
            received,
        )
    }

    @Test
    fun `status lists what waits for approval`() {
        val hub = PartyHub { now }
        hub.setFeatures(PartyHub.Features(moderateMessages = true))
        hub.message("player-giulia", "Auguri \"sposi\"", "Giulia")
        host.updateStatus(HostRemote.Status(slideshow = true, playing = true, title = "Torta", photos = 12, pendingPhotos = listOf(HostRemote.PendingPhoto("p1", "Marco"))))
        val json = JSONObject(host.statusJson(hub))
        assertTrue(json.getBoolean("playing"))
        assertEquals("Torta", json.getString("title"))
        assertEquals("p1", json.getJSONArray("pendingPhotos").getJSONObject(0).getString("id"))
        assertEquals("Auguri \"sposi\"", json.getJSONArray("pendingMessages").getJSONObject(0).getString("text"))
        assertTrue(json.isNull("director"))
        assertTrue(host.canShow("p1"))
        assertFalse(host.canShow("p2"))
    }

    @Test
    fun `announcements and the director appear on phones`() {
        val hub = PartyHub { now }
        hub.react("player-giulia", 0, "Giulia")
        assertTrue(hub.announce("Si taglia la torta!"))
        assertFalse(hub.announce("   "))
        val director = hub.startDirector(Random(1))!!
        assertEquals("Giulia", director.name)
        val forGiulia = JSONObject(hub.partyJson("player-giulia"))
        assertEquals("Si taglia la torta!", forGiulia.getJSONObject("announcement").getString("text"))
        assertTrue(forGiulia.getJSONObject("director").getBoolean("you"))
        assertFalse(JSONObject(hub.partyJson("player-marco")).getJSONObject("director").getBoolean("you"))

        val commands = mutableListOf<PartyCommand>()
        hub.onDirectorCommand = { commands += it }
        assertEquals(200, hub.handle(mapOf("type" to "director", "player" to "player-giulia", "command" to "next")).first)
        assertEquals(403, hub.handle(mapOf("type" to "director", "player" to "player-marco", "command" to "next")).first)
        assertEquals(listOf<PartyCommand>(PartyCommand.Next), commands)

        now += PartyHub.DIRECTOR_MS
        assertNull(hub.director())
        assertEquals(403, hub.handle(mapOf("type" to "director", "player" to "player-giulia", "command" to "next")).first)
        now += PartyHub.ANNOUNCEMENT_PHONE_MS
        assertNull(hub.announcement())
    }

    @Test
    fun `no director without names`() {
        val hub = PartyHub { now }
        hub.react("player-anonimo", 0, null)
        assertNull(hub.startDirector())
    }
}
