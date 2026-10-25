package it.fotofesta.tv.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyHubTest {
    private var now = 1_000_000L
    private val giulia = "player-giulia"
    private val marco = "player-marco"

    private fun hub() = PartyHub { now }

    @Test
    fun `reactions count for the photo on screen and are reported in order`() {
        val hub = hub()
        val seen = mutableListOf<PartyHub.ReactionEvent>()
        hub.onReaction = { seen += it }
        hub.showing("foto-1")
        assertEquals(PartyHub.Result.OK, hub.react(giulia, 0, "Giulia"))
        assertEquals(PartyHub.Result.OK, hub.react(marco, 2, null))
        hub.showing("foto-2")
        assertEquals(PartyHub.Result.OK, hub.react(giulia, 5, null))
        assertEquals(2, hub.reactionCount("foto-1"))
        assertEquals(1, hub.reactionCount("foto-2"))
        assertEquals(listOf(PartyHub.Reaction.HEART, PartyHub.Reaction.PARTY, PartyHub.Reaction.FIRE), seen.map { it.reaction })
        assertEquals(seen.drop(1), hub.reactionsAfter(seen.first().serial))
        // Il nome scritto una volta resta per le reazioni successive.
        assertEquals(listOf(PartyHub.Participant(giulia, "Giulia")), hub.participants())
    }

    @Test
    fun `reactions from one phone are limited`() {
        val hub = hub()
        repeat(PartyHub.REACTION_BURST) { assertEquals(PartyHub.Result.OK, hub.react(giulia, 0, null)) }
        assertEquals(PartyHub.Result.RATE_LIMITED, hub.react(giulia, 0, null))
        // Un altro telefono non è bloccato.
        assertEquals(PartyHub.Result.OK, hub.react(marco, 0, null))
        now += PartyHub.REACTION_WINDOW_MS
        assertEquals(PartyHub.Result.OK, hub.react(giulia, 0, null))
    }

    @Test
    fun `invalid reactions and disabled features are refused`() {
        val hub = hub()
        assertEquals(PartyHub.Result.INVALID, hub.react(giulia, 6, null))
        assertEquals(PartyHub.Result.INVALID, hub.react(giulia, -1, null))
        assertEquals(PartyHub.Result.INVALID, hub.react("corto", 0, null))
        hub.setFeatures(PartyHub.Features(reactions = false, messages = false))
        assertEquals(PartyHub.Result.DISABLED, hub.react(giulia, 0, null))
        assertEquals(PartyHub.Result.DISABLED, hub.message(giulia, "Auguri!", null))
        assertEquals("""{"reactions":false,"messages":false,"moderated":false,"arrivals":true,"wall":true,"songs":true,"album":true,"video":false,"delete":true}""", hub.featuresJson())
    }

    @Test
    fun `song requests are kept for the music, with limits`() {
        val hub = hub()
        var heard: PartyHub.SongRequest? = null
        hub.onSong = { heard = it }
        assertEquals(PartyHub.Result.OK, hub.songRequest(giulia, "  Gloria - Umberto Tozzi ", "Giulia"))
        assertEquals("Gloria - Umberto Tozzi", heard?.text)
        assertEquals("Giulia", hub.songRequests().single().name)
        assertEquals(PartyHub.Result.INVALID, hub.songRequest(giulia, "   ", null))
        repeat(2) { hub.songRequest(giulia, "Canzone $it", null) }
        assertEquals(PartyHub.Result.RATE_LIMITED, hub.songRequest(giulia, "Ancora una", null))
        hub.removeSong(hub.songRequests().first().id)
        assertEquals(2, hub.songRequests().size)
        hub.setFeatures(PartyHub.Features(songs = false))
        assertEquals(PartyHub.Result.DISABLED, hub.songRequest("telefono-marco", "Volare", null))
        hub.clear()
        assertTrue(hub.songRequests().isEmpty())
    }

    @Test
    fun `messages are cleaned, signed and limited`() {
        val hub = hub()
        var changes = 0
        hub.onChange = { changes++ }
        assertEquals(PartyHub.Result.OK, hub.message(giulia, "  Tanti\n\nauguri‮!  ", "Giulia"))
        assertEquals(PartyHub.Result.INVALID, hub.message(giulia, "   ", "Giulia"))
        val message = hub.messages().single()
        assertEquals("Tanti auguri!", message.text)
        assertEquals("Giulia", message.name)
        assertEquals(1, changes)
        assertEquals(PartyHub.MAX_MESSAGE_LENGTH, hub.message(marco, "x".repeat(500), null).let { hub.messages().last().text.length })
        hub.message(giulia, "due", null)
        hub.message(giulia, "tre", null)
        assertEquals(PartyHub.Result.RATE_LIMITED, hub.message(giulia, "quattro", null))
        // Senza nome nella seconda dedica resta quello scritto prima.
        assertEquals("Giulia", hub.messages().last().name)
    }

    @Test
    fun `moderated messages wait for approval`() {
        val hub = hub()
        hub.setFeatures(PartyHub.Features(moderateMessages = true))
        assertEquals(PartyHub.Result.PENDING, hub.message(giulia, "Uno", null))
        assertEquals(PartyHub.Result.PENDING, hub.message(marco, "Due", null))
        assertTrue(hub.messages().isEmpty())
        val (first, second) = hub.pendingMessages()
        hub.approveMessage(first.id)
        hub.removeMessage(second.id)
        assertEquals(listOf("Uno"), hub.messages().map { it.text })
        assertTrue(hub.pendingMessages().isEmpty())
        hub.message(giulia, "Tre", null)
        // Togliendo la moderazione le dediche in attesa compaiono.
        hub.setFeatures(PartyHub.Features(moderateMessages = false))
        assertEquals(listOf("Uno", "Tre"), hub.messages().map { it.text })
    }

    @Test
    fun `form requests map to http answers`() {
        val hub = hub()
        assertEquals(200, hub.handle(mapOf("type" to "react", "player" to giulia, "reaction" to "1")).first)
        assertEquals(400, hub.handle(mapOf("type" to "react", "player" to giulia)).first)
        assertEquals(200 to """{"status":"ok"}""", hub.handle(mapOf("type" to "message", "player" to giulia, "text" to "Ciao", "name" to "Giulia")))
        assertEquals(400, hub.handle(mapOf("type" to "boh", "player" to giulia)).first)
        hub.setFeatures(PartyHub.Features(moderateMessages = true))
        assertEquals(200 to """{"status":"pending"}""", hub.handle(mapOf("type" to "message", "player" to marco, "text" to "Ehi")))
        repeat(PartyHub.REACTION_BURST) { hub.react(marco, 0, null) }
        assertEquals(429, hub.handle(mapOf("type" to "react", "player" to marco, "reaction" to "0")).first)
    }

    @Test
    fun `clear forgets everything`() {
        val hub = hub()
        hub.showing("foto")
        hub.react(giulia, 0, "Giulia")
        hub.message(giulia, "Ciao", null)
        hub.clear()
        assertTrue(hub.messages().isEmpty())
        assertTrue(hub.participants().isEmpty())
        assertEquals(0, hub.reactionCount("foto"))
        assertTrue(hub.reactionCounts().isEmpty())
    }

    @Test
    fun `arrivals greet each phone once, in order, and survive a restart`() {
        val hub = hub()
        val greeted = mutableListOf<PartyHub.Arrival>()
        hub.onArrival = { greeted += it }
        assertEquals(PartyHub.Result.INVALID, hub.arrive(giulia, null))
        assertEquals(PartyHub.Result.INVALID, hub.arrive("corto", "Giulia"))
        assertEquals(200 to """{"status":"ok"}""", hub.handle(mapOf("type" to "arrive", "player" to giulia, "name" to "Giulia")))
        now += 60_000
        assertEquals(PartyHub.Result.OK, hub.arrive(marco, "Marco"))
        // Di nuovo dal QR: nessun secondo benvenuto, ma il nome si aggiorna.
        assertEquals(PartyHub.Result.OK, hub.arrive(giulia, "Giulia B."))
        assertEquals(listOf("Giulia", "Marco"), greeted.map { it.name })
        assertEquals(listOf(1, 2), greeted.map { it.order })
        assertEquals(listOf("Giulia B.", "Marco"), hub.arrivals().map { it.name })
        // Anche nei tre minuti senza telefoni si può arrivare.
        hub.startQuiet()
        assertEquals(200, hub.handle(mapOf("type" to "arrive", "player" to "player-sara", "name" to "Sara")).first)
        val restored = hub()
        restored.restore(hub.snapshot())
        assertEquals(listOf("Giulia B.", "Marco", "Sara"), restored.arrivals().map { it.name })
        assertEquals(listOf(1, 2, 3), restored.arrivals().map { it.order })
        hub.clear()
        assertTrue(hub.arrivals().isEmpty())
    }
}
