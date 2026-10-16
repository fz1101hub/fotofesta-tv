package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestPlaybackTest {
    private fun item(id: String, source: SourceType = SourceType.USB) =
        PhotoItem(id, id, PhotoRef.LocalFile("/$id"), source)

    private val base = listOf(item("a"), item("b"), item("c"))
    private val guests = listOf(item("g1", SourceType.GUESTS), item("g2", SourceType.GUESTS))

    @Test
    fun `guests are appended to a real collection`() {
        assertEquals(base + guests, combineWithGuests(base, baseIsDemo = false, guests, partyActive = true))
        assertEquals(base, combineWithGuests(base, baseIsDemo = false, emptyList(), partyActive = true))
    }

    @Test
    fun `guests replace the demo photos`() {
        assertEquals(guests, combineWithGuests(base, baseIsDemo = true, guests, partyActive = false))
        // Durante la festa la demo non compare, nemmeno prima della prima foto.
        assertEquals(emptyList<PhotoItem>(), combineWithGuests(base, baseIsDemo = true, emptyList(), partyActive = true))
        assertEquals(base, combineWithGuests(base, baseIsDemo = true, emptyList(), partyActive = false))
    }

    @Test
    fun `fresh photos come next and playback resumes where it was`() {
        val photos = base + guests
        val fresh = FreshPhotos()
        fresh.add(listOf("g2", "g1"))

        assertEquals(4, fresh.peek(photos, currentIndex = 1))
        assertEquals(4, fresh.take(photos, currentIndex = 1))
        assertEquals(1, fresh.peekResume(photos))
        assertEquals(3, fresh.take(photos, currentIndex = 4))
        assertNull(fresh.take(photos, currentIndex = 3))
        // Si riprende dalla foto mostrata prima delle nuove.
        assertEquals(1, fresh.takeResume(photos))
        assertNull(fresh.takeResume(photos))
    }

    @Test
    fun `photos no longer in the collection are skipped`() {
        val fresh = FreshPhotos()
        fresh.add(listOf("gone", "g1"))
        assertEquals(3, fresh.take(base + guests, currentIndex = 0))
    }

    @Test
    fun `the photo on screen is not shown again`() {
        val fresh = FreshPhotos()
        fresh.add(listOf("g1"))
        assertNull(fresh.peek(base + guests, currentIndex = 3))
        assertNull(fresh.take(base + guests, currentIndex = 3))
        assertNull(fresh.peekResume(base + guests))
    }

    @Test
    fun `queue keeps only the newest photos`() {
        val fresh = FreshPhotos(limit = 2)
        fresh.add(listOf("a", "b", "c"))
        assertEquals(1, fresh.take(base, currentIndex = 0))
        assertEquals(2, fresh.take(base, currentIndex = 1))
        assertNull(fresh.take(base, currentIndex = 2))
    }

    @Test
    fun `clear forgets queue and resume point`() {
        val fresh = FreshPhotos()
        fresh.add(listOf("g1"))
        fresh.take(base + guests, currentIndex = 0)
        fresh.clear()
        assertNull(fresh.peekResume(base + guests))
        assertNull(fresh.take(base + guests, currentIndex = 0))
    }
}
