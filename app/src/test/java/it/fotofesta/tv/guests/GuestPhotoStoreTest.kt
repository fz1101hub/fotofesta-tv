package it.fotofesta.tv.guests

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        maxPending: Int = 100,
    ) = GuestPhotoStore(
        directory = directory,
        memoryBudgetBytes = memory,
        maxDiskBytes = disk,
        freeSpace = { free },
        maxPhotos = maxPhotos,
        maxPending = maxPending,
        clock = { now++ },
    )

    private fun photo(size: Int, fill: Int = size) = ByteArray(size) { fill.toByte() }

    private fun GuestPhotoStore.put(bytes: ByteArray, sender: String? = null, approved: Boolean = true): GuestPhotoStore.Photo =
        (add(bytes, sender, approved) as GuestPhotoStore.AddResult.Added).photo

    private fun files(): List<File> = File(temp.root, "guests").listFiles().orEmpty().toList()

    @Test
    fun `photos stay in memory while the budget allows`() {
        val store = store()
        val first = store.put(photo(100), "Anna")
        store.put(photo(100))

        assertEquals(false, store.isOnDisk(first.id))
        assertEquals(200, store.memoryUsedBytes())
        assertEquals(0, store.diskUsedBytes())
        assertTrue(files().isEmpty())
    }

    @Test
    fun `oldest photos move to disk when memory runs out`() {
        val store = store(memory = 250)
        val first = store.put(photo(100, 1))
        val second = store.put(photo(100, 2))
        val third = store.put(photo(100, 3))

        assertEquals(true, store.isOnDisk(first.id))
        assertArrayEquals(photo(100, 1), store.read(first.id))
        assertEquals(false, store.isOnDisk(second.id))
        assertEquals(false, store.isOnDisk(third.id))
        assertEquals(200, store.memoryUsedBytes())
        assertEquals(100, store.diskUsedBytes())
        assertEquals(listOf(first.id, second.id, third.id), store.photos.value.map { it.id })
    }

    @Test
    fun `the fallback folder works with the real free space even before it exists`() {
        // Senza spazio libero simulato: la cartella non esiste ancora e File.usableSpace varrebbe 0.
        val directory = File(temp.root, "not/yet/created")
        val store = GuestPhotoStore(directory = directory, memoryBudgetBytes = 150, maxDiskBytes = 10_000)
        val first = (store.add(photo(100), null, approved = true) as GuestPhotoStore.AddResult.Added).photo
        store.add(photo(100), null, approved = true)

        assertEquals(true, store.isOnDisk(first.id))
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertTrue(GuestPhotoStore.usableSpaceOf(File(temp.root, "missing/dir")) > 0)
    }

    @Test
    fun `when disk is full the oldest photos make room for new ones`() {
        val store = store(memory = 100, disk = 200)
        val ids = (1..5).map { store.put(photo(100, it)).id }

        // Una in RAM, due su disco: le prime due sono state cancellate.
        assertEquals(ids.drop(2), store.photos.value.map { it.id })
        assertNull(store.read(ids[0]))
        assertNull(store.read(ids[1]))
        assertEquals(2, files().size)
        assertEquals(200, store.diskUsedBytes())
        assertEquals(100, store.memoryUsedBytes())
    }

    @Test
    fun `pending photos are sacrificed before approved ones`() {
        val store = store(memory = 100, disk = 200)
        val approved = store.put(photo(100, 1))
        val pending = store.put(photo(100, 2), approved = false)
        store.put(photo(100, 3))
        store.put(photo(100, 4))

        // Disco pieno: è uscita la foto in attesa, non quella approvata più vecchia.
        assertNull(store.read(pending.id))
        assertArrayEquals(photo(100, 1), store.read(approved.id))
    }

    @Test
    fun `a pending photo never pushes out approved ones`() {
        val store = store(memory = 100, disk = 200)
        val approved = (1..3).map { store.put(photo(100, it)).id }
        val result = store.add(photo(100, 9), null, approved = false)

        assertEquals(GuestPhotoStore.AddResult.Full, result)
        assertEquals(approved, store.photos.value.map { it.id })
    }

    @Test
    fun `pending photos are capped`() {
        val store = store(memory = 10_000, maxPending = 2)
        store.put(photo(10), approved = false)
        store.put(photo(10), approved = false)

        assertEquals(GuestPhotoStore.AddResult.TooManyPending, store.add(photo(10), null, approved = false))
        assertTrue(store.add(photo(10), null, approved = true) is GuestPhotoStore.AddResult.Added)
    }

    @Test
    fun `photo count cap removes pending photos first`() {
        val store = store(memory = 10_000, maxPhotos = 3)
        val first = store.put(photo(10))
        val pending = store.put(photo(10), approved = false)
        store.put(photo(10))
        store.put(photo(10))

        assertNull(store.read(pending.id))
        assertTrue(store.photos.value.any { it.id == first.id })
        assertEquals(3, store.photos.value.size)
    }

    @Test
    fun `disk budget never exceeds the share of free space`() {
        free = 1_500 // 10% di (1500 + usati) = circa 150 byte
        val store = store(memory = 100, disk = 10_000)
        val first = store.put(photo(100, 1))
        val second = store.put(photo(100, 2))
        store.put(photo(100, 3))

        // Ci sta una sola foto su disco: la prima è stata cancellata per far posto alla seconda.
        assertNull(store.read(first.id))
        assertEquals(true, store.isOnDisk(second.id))
        assertEquals(1, files().size)
    }

    @Test
    fun `without disk space the oldest photo is dropped`() {
        free = 0
        val store = store(memory = 150)
        val first = store.put(photo(100))
        val second = store.put(photo(100))

        assertNull(store.read(first.id))
        assertArrayEquals(photo(100), store.read(second.id))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a photo that fits nowhere is refused`() {
        free = 0
        val store = store(memory = 50)
        assertEquals(GuestPhotoStore.AddResult.Full, store.add(photo(100), null, approved = true))
        assertTrue(store.photos.value.isEmpty())
        assertEquals(0, store.memoryUsedBytes())
    }

    @Test
    fun `spill all writes memory photos to disk`() {
        val store = store(memory = 1_000)
        val first = store.put(photo(100), "Luca")
        val second = store.put(photo(100), approved = false)

        store.spillAll()

        assertEquals(true, store.isOnDisk(first.id))
        assertEquals(true, store.isOnDisk(second.id))
        assertEquals(0, store.memoryUsedBytes())
        assertEquals(200, store.diskUsedBytes())
    }

    @Test
    fun `spill all keeps photos in memory when disk has no room`() {
        val store = store(memory = 1_000, disk = 150)
        val first = store.put(photo(100))
        val second = store.put(photo(100))

        store.spillAll()

        assertEquals(true, store.isOnDisk(first.id))
        assertEquals(false, store.isOnDisk(second.id))
        assertEquals(2, store.photos.value.size)
    }

    @Test
    fun `restore brings back photos with sender and approval`() {
        val directory = File(temp.root, "guests")
        val first = store(directory).run {
            val added = put(photo(100, 7), "Zia_Maria 🎉", approved = false)
            put(photo(50, 8))
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
        assertArrayEquals(photo(100, 7), restored.read(first.id))
        assertEquals(150, restored.diskUsedBytes())
    }

    @Test
    fun `approving a photo on disk survives a restart and stays readable`() {
        val directory = File(temp.root, "guests")
        val original = store(directory)
        val added = original.put(photo(100, 5), "Anna", approved = false)
        original.spillAll()

        assertTrue(original.approve(added.id))
        assertArrayEquals(photo(100, 5), original.read(added.id))

        val restored = store(directory)
        restored.restore(maxAgeMillis = 60_000)
        assertTrue(restored.photos.value.single().approved)
    }

    @Test
    fun `restore after a long pause deletes everything`() {
        val directory = File(temp.root, "guests")
        store(directory).run {
            put(photo(100))
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
        val first = store.put(photo(100))
        val second = store.put(photo(100))

        store.remove(first.id)
        assertNull(store.read(first.id))
        assertEquals(0, store.diskUsedBytes())

        store.clear()
        assertNull(store.read(second.id))
        assertTrue(store.photos.value.isEmpty())
        assertTrue(files().isEmpty())
        assertEquals(0, store.memoryUsedBytes())
    }

    @Test
    fun `photo count is capped`() {
        val store = store(memory = 10_000, maxPhotos = 3)
        val ids = (1..5).map { store.put(photo(10)).id }
        assertEquals(ids.takeLast(3), store.photos.value.map { it.id })
        assertEquals(30, store.memoryUsedBytes())
    }

    @Test
    fun `sender names are cleaned before storing`() {
        val store = store()
        val photo = store.put(photo(10), "  Anna‮\n  Rossi  ")
        assertEquals("Anna Rossi", photo.sender)
    }
}
