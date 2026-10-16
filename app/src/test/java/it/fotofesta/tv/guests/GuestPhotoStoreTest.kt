package it.fotofesta.tv.guests

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestPhotoStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000_000L
    private var free = 1_000_000_000L

    private fun store(
        directory: File = File(temp.root, "guests"),
        memory: Long = 300,
        disk: Long = 1_000,
        maxPhotos: Int = 1_000,
    ) = GuestPhotoStore(
        directory = directory,
        memoryBudgetBytes = memory,
        maxDiskBytes = disk,
        freeSpace = { free },
        maxPhotos = maxPhotos,
        clock = { now++ },
    )

    private fun photo(size: Int, fill: Int = size) = ByteArray(size) { fill.toByte() }

    private fun GuestPhotoStore.files(): List<File> = File(temp.root, "guests").listFiles().orEmpty().toList()

    @Test
    fun `photos stay in memory while the budget allows`() {
        val store = store()
        val first = store.add(photo(100), "Anna", approved = true)!!
        store.add(photo(100), null, approved = true)

        assertTrue(store.open(first.id) is GuestPhotoStore.Content.Bytes)
        assertEquals(200, store.memoryUsedBytes())
        assertEquals(0, store.diskUsedBytes())
        assertTrue(store.files().isEmpty())
    }

    @Test
    fun `oldest photos move to disk when memory runs out`() {
        val store = store(memory = 250)
        val first = store.add(photo(100, 1), null, approved = true)!!
        val second = store.add(photo(100, 2), null, approved = true)!!
        val third = store.add(photo(100, 3), null, approved = true)!!

        val spilled = store.open(first.id) as GuestPhotoStore.Content.OnDisk
        assertArrayEquals(photo(100, 1), spilled.file.readBytes())
        assertTrue(store.open(second.id) is GuestPhotoStore.Content.Bytes)
        assertTrue(store.open(third.id) is GuestPhotoStore.Content.Bytes)
        assertEquals(200, store.memoryUsedBytes())
        assertEquals(100, store.diskUsedBytes())
        assertEquals(listOf(first.id, second.id, third.id), store.photos.value.map { it.id })
    }

    @Test
    fun `when disk is full the oldest photos make room for new ones`() {
        val store = store(memory = 100, disk = 200)
        val ids = (1..5).map { store.add(photo(100, it), null, approved = true)!!.id }

        // Una in RAM, due su disco: le prime due sono state cancellate.
        assertEquals(ids.drop(2), store.photos.value.map { it.id })
        assertNull(store.open(ids[0]))
        assertNull(store.open(ids[1]))
        assertEquals(2, store.files().size)
        assertEquals(200, store.diskUsedBytes())
        assertEquals(100, store.memoryUsedBytes())
    }

    @Test
    fun `disk budget never exceeds the share of free space`() {
        free = 1_500 // 10% di (1500 + usati) = circa 150 byte
        val store = store(memory = 100, disk = 10_000)
        val first = store.add(photo(100, 1), null, approved = true)!!
        val second = store.add(photo(100, 2), null, approved = true)!!
        store.add(photo(100, 3), null, approved = true)!!

        // Ci sta una sola foto su disco: la prima è stata cancellata per far posto alla seconda.
        assertNull(store.open(first.id))
        assertTrue(store.open(second.id) is GuestPhotoStore.Content.OnDisk)
        assertEquals(1, store.files().size)
    }

    @Test
    fun `without disk space the oldest photo is dropped`() {
        free = 0
        val store = store(memory = 150)
        val first = store.add(photo(100), null, approved = true)!!
        val second = store.add(photo(100), null, approved = true)!!

        assertNull(store.open(first.id))
        assertNotNull(store.open(second.id))
        assertTrue(store.files().isEmpty())
    }

    @Test
    fun `a photo that fits nowhere is refused`() {
        free = 0
        val store = store(memory = 50)
        assertNull(store.add(photo(100), null, approved = true))
        assertTrue(store.photos.value.isEmpty())
        assertEquals(0, store.memoryUsedBytes())
    }

    @Test
    fun `spill all writes memory photos to disk`() {
        val store = store(memory = 1_000)
        val first = store.add(photo(100), "Luca", approved = true)!!
        val second = store.add(photo(100), null, approved = false)!!

        store.spillAll()

        assertTrue(store.open(first.id) is GuestPhotoStore.Content.OnDisk)
        assertTrue(store.open(second.id) is GuestPhotoStore.Content.OnDisk)
        assertEquals(0, store.memoryUsedBytes())
        assertEquals(200, store.diskUsedBytes())
    }

    @Test
    fun `spill all keeps photos in memory when disk has no room`() {
        val store = store(memory = 1_000, disk = 150)
        val first = store.add(photo(100), null, approved = true)!!
        val second = store.add(photo(100), null, approved = true)!!

        store.spillAll()

        assertTrue(store.open(first.id) is GuestPhotoStore.Content.OnDisk)
        assertTrue(store.open(second.id) is GuestPhotoStore.Content.Bytes)
        assertEquals(2, store.photos.value.size)
    }

    @Test
    fun `restore brings back photos with sender and approval`() {
        val directory = File(temp.root, "guests")
        val first = store(directory).run {
            val added = add(photo(100, 7), "Zia_Maria 🎉", approved = false)!!
            add(photo(50, 8), null, approved = true)
            spillAll()
            added
        }

        val restored = store(directory)
        assertEquals(2, restored.restore(maxAgeMillis = 60_000))
        val photos = restored.photos.value
        assertEquals(first.id, photos[0].id)
        assertEquals("Zia_Maria 🎉", photos[0].sender)
        assertFalse(photos[0].approved)
        assertNull(photos[1].sender)
        assertTrue(photos[1].approved)
        assertArrayEquals(photo(100, 7), (restored.open(first.id) as GuestPhotoStore.Content.OnDisk).file.readBytes())
        assertEquals(150, restored.diskUsedBytes())
    }

    @Test
    fun `approving a photo on disk survives a restart`() {
        val directory = File(temp.root, "guests")
        val original = store(directory)
        val photo = original.add(photo(100), "Anna", approved = false)!!
        original.spillAll()

        assertTrue(original.approve(photo.id))

        val restored = store(directory)
        restored.restore(maxAgeMillis = 60_000)
        assertTrue(restored.photos.value.single().approved)
    }

    @Test
    fun `restore after a long pause deletes everything`() {
        val directory = File(temp.root, "guests")
        store(directory).run {
            add(photo(100), null, approved = true)
            spillAll()
        }
        now += 13 * 60 * 60 * 1000L

        val restored = store(directory)
        assertEquals(0, restored.restore(maxAgeMillis = 12 * 60 * 60 * 1000L))
        assertTrue(restored.photos.value.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `restore deletes unknown and partial files`() {
        val directory = File(temp.root, "guests").apply { mkdirs() }
        File(directory, "abc.tmp").writeText("x")
        File(directory, "not-a-photo.img").writeText("x")
        File(directory, "123_zzzzzzzzzzzzzzzz_a_.img").writeText("x")
        File(directory, "${now}_0123456789abcdef_q_.img").writeText("x")

        val store = store(directory)
        assertEquals(0, store.restore(maxAgeMillis = 60_000))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `remove and clear delete files`() {
        val store = store(memory = 100)
        val first = store.add(photo(100), null, approved = true)!!
        val second = store.add(photo(100), null, approved = true)!!

        store.remove(first.id)
        assertNull(store.open(first.id))
        assertEquals(0, store.diskUsedBytes())

        store.clear()
        assertNull(store.open(second.id))
        assertTrue(store.photos.value.isEmpty())
        assertTrue(store.files().isEmpty())
        assertEquals(0, store.memoryUsedBytes())
    }

    @Test
    fun `photo count is capped`() {
        val store = store(memory = 10_000, maxPhotos = 3)
        val ids = (1..5).map { store.add(photo(10), null, approved = true)!!.id }
        assertEquals(ids.takeLast(3), store.photos.value.map { it.id })
        assertEquals(30, store.memoryUsedBytes())
    }

    @Test
    fun `sender names are cleaned before storing`() {
        val store = store()
        val photo = store.add(photo(10), "  Anna‮\n  Rossi  ", approved = true)!!
        assertEquals("Anna Rossi", photo.sender)
    }
}
