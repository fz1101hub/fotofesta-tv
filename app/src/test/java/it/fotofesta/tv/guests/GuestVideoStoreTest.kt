package it.fotofesta.tv.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class GuestVideoStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val mp4 = ByteArray(5_000) { (it % 200).toByte() }.also {
        "ftypisom".toByteArray().copyInto(it, 4)
    }

    private fun store(max: Long = 20_000, now: Long = 1_000) =
        GuestVideoStore(folder.newFolder(), maxTotalBytes = max, freeSpace = { Long.MAX_VALUE }, clock = { now })

    @Test
    fun `videos go to disk and come back after a restart`() {
        val store = store()
        val added = store.add(mp4.inputStream(), mp4.size.toLong(), "Giulia", GuestVideoStore::looksLikeVideo)
        assertTrue(added is GuestVideoStore.AddResult.Added)
        val video = (added as GuestVideoStore.AddResult.Added).video
        assertEquals("Giulia", video.sender)
        assertTrue(video.file.readBytes().contentEquals(mp4))
        val again = GuestVideoStore(video.file.parentFile!!, 20_000, freeSpace = { Long.MAX_VALUE }, clock = { 2_000 })
        again.restore(maxAgeMillis = 10_000)
        assertEquals(listOf(video.id), again.videos.value.map { it.id })
        assertEquals("Giulia", again.videos.value.single().sender)
        assertTrue(again.remove(video.id))
        assertFalse(video.file.exists())
    }

    @Test
    fun `not a video, too big or cut short`() {
        val store = store()
        val text = "ciao a tutti, questo non è un video".toByteArray()
        assertEquals(GuestVideoStore.AddResult.Invalid, store.add(text.inputStream(), text.size.toLong(), null, GuestVideoStore::looksLikeVideo))
        assertEquals(GuestVideoStore.AddResult.Full, store.add(mp4.inputStream(), 30_000, null, GuestVideoStore::looksLikeVideo))
        try {
            store.add(mp4.inputStream(), 9_000, null, GuestVideoStore::looksLikeVideo)
            throw AssertionError("doveva fallire")
        } catch (_: IOException) {
        }
        assertTrue(store.videos.value.isEmpty())
        assertTrue(folder.root.walk().none { it.name.endsWith(".part") })
    }

    @Test
    fun `old videos are deleted on restore and everything on clear`() {
        val store = store(now = 1_000)
        store.add(mp4.inputStream(), mp4.size.toLong(), null, GuestVideoStore::looksLikeVideo)
        val dir = store.videos.value.single().file.parentFile!!
        val later = GuestVideoStore(dir, 20_000, freeSpace = { Long.MAX_VALUE }, clock = { 100_000 })
        later.restore(maxAgeMillis = 10_000)
        assertTrue(later.videos.value.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        store.clear()
    }

    @Test
    fun `recognises mp4, mov and webm`() {
        assertTrue(GuestVideoStore.looksLikeVideo(mp4.copyOf(12)))
        assertTrue(GuestVideoStore.looksLikeVideo(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0)))
        assertFalse(GuestVideoStore.looksLikeVideo(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)))
    }
}
