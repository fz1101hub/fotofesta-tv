package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyAwardsTest {
    private fun guest(id: String, sender: String?, at: Long) =
        PhotoItem("guest:$id", "Foto", PhotoRef.GuestPhoto(id), SourceType.GUESTS, sender = sender, receivedAtMillis = at)

    @Test
    fun `no guest photos means no awards`() {
        val awards = PartyAwards.from(listOf(PhotoItem("demo:1", "Picnic", PhotoRef.Resource(1), SourceType.DEMO)))
        assertEquals(0, awards.photos)
        assertEquals(0, awards.guests)
        assertNull(awards.topPhotographer)
        assertNull(awards.firstPhoto)
        assertNull(awards.lastPhoto)
    }

    @Test
    fun `golden lens goes to whoever sent the most photos`() {
        val photos = listOf(
            guest("1", "Giulia", 100), guest("2", "Marco", 200), guest("3", "marco ", 300),
            guest("4", null, 400), guest("5", "Marco", 500), guest("6", "Giulia", 600),
        )
        val awards = PartyAwards.from(photos)
        assertEquals(6, awards.photos)
        assertEquals(2, awards.guests)
        val top = awards.topPhotographer!!
        assertEquals("Marco", top.name)
        assertEquals(3, top.count)
        assertEquals("guest:5", top.photo.id)
    }

    @Test
    fun `on a tie the guest who started first wins`() {
        val awards = PartyAwards.from(listOf(guest("1", "Sara", 300), guest("2", "Luca", 100), guest("3", "Sara", 400), guest("4", "Luca", 500)))
        assertEquals("Luca", awards.topPhotographer!!.name)
    }

    @Test
    fun `first and last photo follow arrival time`() {
        val awards = PartyAwards.from(listOf(guest("b", "Marco", 200), guest("a", null, 100), guest("c", "Sara", 300)))
        assertEquals("guest:a", awards.firstPhoto!!.id)
        assertEquals("guest:c", awards.lastPhoto!!.id)
    }

    @Test
    fun `a single photo is first but not also last`() {
        val awards = PartyAwards.from(listOf(guest("1", "Giulia", 100)))
        assertEquals("guest:1", awards.firstPhoto!!.id)
        assertNull(awards.lastPhoto)
        assertEquals(1, awards.topPhotographer!!.count)
    }
}
