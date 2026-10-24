package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PartyShowsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun guest(id: String, sender: String?, hour: Int) = PhotoItem(
        id = id,
        title = id,
        ref = PhotoRef.GuestPhoto(id),
        source = SourceType.GUESTS,
        sender = sender,
        receivedAtMillis = hour * 3_600_000L,
    )

    private fun demo(id: String) = PhotoItem(id = id, title = id, ref = PhotoRef.GuestPhoto(id), source = SourceType.DEMO)

    @Test
    fun `the party in numbers`() {
        val photos = listOf(guest("a", "Giulia", 21), guest("b", "giulia ", 21), guest("c", "Marco", 22), guest("d", null, 22), guest("e", "Sara", 22), demo("x"))
        val stats = PartyShows.stats(photos, messages = 4, reactionsByPhoto = mapOf("c" to 5, "a" to 2, "altrove" to 9), reactionsByKind = listOf(1, 0, 6, 0, 0, 0), coins = 12, zone = utc)
        assertEquals(6, stats.photos)
        assertEquals(5, stats.guestPhotos)
        // "Giulia" e "giulia " sono la stessa persona; chi non scrive il nome non conta.
        assertEquals(3, stats.photographers)
        assertEquals(22, stats.busiestHour)
        assertEquals(2, stats.hourly[21])
        assertEquals(7, stats.reactions)
        assertEquals(2, stats.topReaction)
        // Una foto non più in raccolta non può essere la più amata.
        assertEquals("c", stats.favorite)
        assertEquals(12, stats.coins)
    }

    @Test
    fun `numbers without photos or reactions`() {
        val stats = PartyShows.stats(emptyList(), 0, emptyMap(), listOf(0, 0, 0, 0, 0, 0), 0, utc)
        assertNull(stats.busiestHour)
        assertNull(stats.topReaction)
        assertNull(stats.favorite)
    }

    @Test
    fun `the mosaic picks the closest colour but not always the same photo`() {
        val red = 0xFFFF0000.toInt()
        val darkRed = 0xFFCC0000.toInt()
        val blue = 0xFF0000FF.toInt()
        assertEquals(listOf(0, 1), PartyShows.mosaic(listOf(red, blue), listOf(red, blue)))
        // Con tante caselle rosse, dopo un po' anche il rosso scuro trova posto.
        val choice = PartyShows.mosaic(List(40) { red }, listOf(red, darkRed, blue))
        assertTrue(choice.count { it == 0 } > choice.count { it == 1 })
        assertTrue(1 in choice)
        assertTrue(PartyShows.mosaic(listOf(red), emptyList()).isEmpty())
    }

    @Test
    fun `average colour of pixels`() {
        assertEquals(0xFF7F007F.toInt(), PartyShows.average(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())))
        assertEquals(0xFF000000.toInt(), PartyShows.average(IntArray(0)))
    }

    @Test
    fun `credits list photographers by number of photos and nobody twice`() {
        val sections = PartyShows.credits(
            photoSenders = listOf("Marco", "Giulia", "giulia", null, "Giulia", "Marco ", "Sara"),
            messageNames = listOf("Anna", null, "anna", "Luca"),
            champions = listOf("Luca"),
            participants = listOf("Giulia", "Paolo", "Anna", "Elena"),
        )
        assertEquals(listOf("Giulia", "Marco", "Sara"), sections.first { it.kind == PartyShows.CreditKind.PHOTOGRAPHERS }.names)
        assertEquals(listOf("Anna", "Luca"), sections.first { it.kind == PartyShows.CreditKind.MESSAGES }.names)
        // Tra gli altri invitati restano solo quelli non ancora citati.
        assertEquals(listOf("Paolo", "Elena"), sections.first { it.kind == PartyShows.CreditKind.GUESTS }.names)
        assertTrue(PartyShows.credits(emptyList(), emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `party templates change only their settings`() {
        val start = SlideshowSettings(intervalSeconds = 12, shuffle = true)
        val newYear = PartyTemplate.NEW_YEAR.apply(start)
        assertEquals(PartyTemplate.NEW_YEAR, newYear.template)
        assertTrue(newYear.countdown)
        assertEquals(CountdownEvent.MIDNIGHT, newYear.countdownEvent)
        assertEquals(12, newYear.intervalSeconds)
        assertTrue(newYear.shuffle)
        assertEquals(SlideshowLayout.STACK, PartyTemplate.BIRTHDAY.apply(start).layout)
        assertEquals(PartyTemplate.NONE, PartyTemplate.NONE.apply(newYear).template)
    }
}
