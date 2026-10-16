package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WhoTookItTest {
    private fun guest(id: String, sender: String?) =
        PhotoItem("guest:$id", "Foto", PhotoRef.GuestPhoto(id), SourceType.GUESTS, sender = sender)

    private val demo = PhotoItem("demo:1", "Picnic", PhotoRef.Resource(1), SourceType.DEMO)

    @Test
    fun `needs photos from at least two named guests`() {
        assertFalse(WhoTookIt.canPlay(listOf(demo, guest("1", "Giulia"), guest("2", "Giulia"), guest("3", null), guest("4", "  "))))
        assertFalse(WhoTookIt.canPlay(listOf(guest("1", "Marco"), guest("2", "marco "))))
        assertTrue(WhoTookIt.canPlay(listOf(guest("1", "Giulia"), guest("2", "Marco"))))
        assertNull(WhoTookIt.round(listOf(guest("1", "Giulia")), null))
    }

    @Test
    fun `round asks about a named guest photo and offers its sender`() {
        val photos = listOf(demo, guest("1", "Giulia"), guest("2", "Marco"), guest("3", "Sara"), guest("4", null))
        repeat(100) { seed ->
            val round = WhoTookIt.round(photos, null, Random(seed))!!
            assertEquals(SourceType.GUESTS, round.photo.source)
            assertEquals(round.photo.sender, round.answer)
            assertTrue(round.answer in round.choices)
            assertEquals(round.choices.size, round.choices.toSet().size)
            assertEquals(3, round.choices.size)
            assertTrue(WhoTookIt.isAnswer(round, round.answer))
        }
    }

    @Test
    fun `choices are at most four names`() {
        val photos = (1..8).map { guest("$it", "Invitato $it") }
        repeat(50) { seed ->
            val round = WhoTookIt.round(photos, null, Random(seed))!!
            assertEquals(WhoTookIt.MAX_CHOICES, round.choices.size)
            assertTrue(round.answer in round.choices)
        }
    }

    @Test
    fun `the same photo is not asked twice in a row`() {
        val photos = listOf(guest("1", "Giulia"), guest("2", "Marco"))
        var previous: String? = null
        repeat(50) { seed ->
            val round = WhoTookIt.round(photos, previous, Random(seed))!!
            assertNotEquals(previous, round.photo.id)
            previous = round.photo.id
        }
    }

    @Test
    fun `names that differ only in case count as the same guest`() {
        val photos = listOf(guest("1", "Giulia"), guest("2", "giulia"), guest("3", "Marco"))
        assertEquals(listOf("Giulia", "Marco"), WhoTookIt.senders(photos))
        repeat(30) { seed ->
            val round = WhoTookIt.round(photos, null, Random(seed))!!
            assertEquals(2, round.choices.size)
            if (round.photo.id == "guest:2") assertEquals("Giulia", round.answer)
            assertTrue(WhoTookIt.isAnswer(round, round.answer.uppercase()))
        }
    }
}
