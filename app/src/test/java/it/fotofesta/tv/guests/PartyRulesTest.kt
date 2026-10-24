package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyRulesTest {
    private var now = 9_000_000L
    private val giulia = "player-giulia"
    private val marco = "player-marco"

    private fun hub() = PartyHub { now }

    @Test
    fun `without a limit every photo is allowed`() {
        val hub = hub()
        repeat(50) { assertEquals(PartyHub.PhotoTake.ALLOWED, hub.takePhoto(null)) }
        assertNull(hub.photosLeft(giulia))
    }

    @Test
    fun `one photo each`() {
        val hub = hub()
        hub.setPhotoRules(PartyHub.PhotoRules(perGuest = 1))
        assertEquals(1, hub.photosLeft(giulia))
        assertEquals(PartyHub.PhotoTake.ALLOWED, hub.takePhoto(giulia))
        assertEquals(PartyHub.PhotoTake.LIMIT, hub.takePhoto(giulia))
        assertEquals(PartyHub.PhotoTake.ALLOWED, hub.takePhoto(marco))
        // Senza identificativo il limite non si potrebbe contare.
        assertEquals(PartyHub.PhotoTake.NO_PLAYER, hub.takePhoto(null))
        assertEquals(0, hub.photosLeft(giulia))
        // Una foto rifiutata dalla TV (memoria piena) restituisce lo scatto.
        hub.returnPhoto(giulia)
        assertEquals(PartyHub.PhotoTake.ALLOWED, hub.takePhoto(giulia))
    }

    @Test
    fun `the disposable camera shows photos only after developing`() {
        val hub = hub()
        assertTrue(hub.isDeveloped())
        hub.setPhotoRules(PartyHub.PhotoRules(perGuest = PartyHub.DISPOSABLE_SHOTS, disposable = true))
        assertFalse(hub.isDeveloped())
        repeat(3) { hub.takePhoto(giulia) }
        assertEquals(3, hub.photosTaken())
        val shots = JSONObject(hub.partyJson(giulia)).getJSONObject("shots")
        assertEquals(PartyHub.DISPOSABLE_SHOTS - 3, shots.getInt("left"))
        assertTrue(shots.getBoolean("disposable"))
        assertFalse(shots.getBoolean("developed"))
        hub.develop()
        assertTrue(hub.isDeveloped())
        hub.clear()
        assertFalse(hub.isDeveloped())
        assertEquals(0, hub.photosTaken())
    }

    @Test
    fun `three minutes without phones refuse photos, reactions and messages`() {
        val hub = hub()
        hub.startQuiet()
        assertEquals(PartyHub.QUIET_MS, hub.quietLeftMillis())
        assertEquals(PartyHub.PhotoTake.QUIET, hub.takePhoto(giulia))
        assertEquals(423, hub.handle(mapOf("type" to "react", "player" to giulia, "reaction" to "0")).first)
        assertEquals(180, JSONObject(hub.partyJson(giulia)).getInt("quiet"))
        now += PartyHub.QUIET_MS
        assertEquals(0, hub.quietLeftMillis())
        assertEquals(200, hub.handle(mapOf("type" to "react", "player" to giulia, "reaction" to "0")).first)
        hub.startQuiet()
        hub.stopQuiet()
        assertEquals(PartyHub.PhotoTake.ALLOWED, hub.takePhoto(giulia))
    }

    @Test
    fun `a snapshot brings back messages, names, reactions and shots`() {
        val hub = hub()
        hub.setPhotoRules(PartyHub.PhotoRules(perGuest = 27, disposable = true))
        hub.showing("foto-1")
        hub.react(giulia, 0, "Giulia")
        hub.message(marco, "Auguri \"a tutti\"", "Marco")
        hub.takePhoto(giulia)
        hub.develop()
        val restored = hub()
        restored.setPhotoRules(PartyHub.PhotoRules(perGuest = 27, disposable = true))
        restored.restore(hub.snapshot())
        assertEquals(listOf("Auguri \"a tutti\""), restored.messages().map { it.text })
        assertEquals(listOf("Giulia", "Marco"), restored.participants().map { it.name })
        assertEquals(1, restored.reactionCount("foto-1"))
        assertEquals(26, restored.photosLeft(giulia))
        assertTrue(restored.isDeveloped())
        // Un salvataggio rovinato non cancella nulla.
        restored.restore("{non è json")
        assertEquals(1, restored.messages().size)
    }
}
