package it.fotofesta.tv.google

import it.fotofesta.tv.model.PhotoRef
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GooglePhotosPickerRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = FakeGoogleServer()

    private fun repository(minFreeBytes: Long = 0L) =
        GooglePhotosPickerRepository(temp.root, server.client, server.apiBase, minFreeBytes)

    @After
    fun tearDown() = server.close()

    private fun image(bytes: String) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().writeUtf8(bytes))

    private fun media(id: String, name: String, mime: String = "image/jpeg") =
        GooglePhotosPickerRepository.PickedMedia(id, name, server.url("/media/$id"), mime)

    @Test
    fun `session is polled until photos are selected`() = runTest {
        server.enqueue("POST", "/v1/sessions", json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","pollingConfig":{"pollInterval":"1s","timeoutIn":"1800s"}}"""))
        server.enqueue(
            "GET", "/v1/sessions/s1",
            json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":false,"pollingConfig":{"pollInterval":"1s","timeoutIn":"1700s"}}"""),
            json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true}"""),
        )
        val picker = repository()
        val session = picker.createSession("tok")
        assertEquals("https://photos.google.com/picker/s1", session.pickerUri)

        val done = picker.awaitSelection({ "tok" }, session)

        assertTrue(done.mediaItemsSet)
        assertEquals(2, server.requestsTo("GET", "/v1/sessions/s1").size)
        val create = server.requestsTo("POST", "/v1/sessions").single()
        assertEquals("Bearer tok", create.getHeader("Authorization"))
        assertTrue(create.requestUrl!!.queryParameter("requestId")!!.isNotBlank())
    }

    @Test
    fun `media list follows pages, encodes tokens and keeps only images`() = runTest {
        server.enqueue(
            "GET", "/v1/mediaItems",
            json("""{"mediaItems":[
                {"id":"a","mediaFile":{"baseUrl":"https://lh3/a","mimeType":"image/jpeg","filename":"Mare.jpg"}},
                {"id":"v","mediaFile":{"baseUrl":"https://lh3/v","mimeType":"video/mp4","filename":"Video.mp4"}},
                {"id":"x","mediaFile":{"mimeType":"image/png","filename":"SenzaUrl.png"}}
              ],"nextPageToken":"p+2/=&x"}"""),
            json("""{"mediaItems":[{"id":"b","mediaFile":{"baseUrl":"https://lh3/b","mimeType":"image/png","filename":"Monti.png"}}]}"""),
        )

        val picked = repository().listPickedMedia({ "tok" }, "s 1")

        assertEquals(listOf("a", "b"), picked.map { it.id })
        assertEquals(listOf("Mare.jpg", "Monti.png"), picked.map { it.filename })
        val pages = server.requestsTo("GET", "/v1/mediaItems")
        assertEquals("s 1", pages[0].requestUrl!!.queryParameter("sessionId"))
        assertEquals("p+2/=&x", pages[1].requestUrl!!.queryParameter("pageToken"))
    }

    @Test
    fun `download replaces the cache and skips unavailable photos`() = runTest {
        val picker = repository()
        server.enqueue("GET", "/media/old=w3840-h2160", image("old"))
        picker.downloadToPrivateCache({ "tok" }, listOf(media("old", "Vecchia.jpg"))) { _, _ -> }

        server.enqueue("GET", "/media/1=w3840-h2160", image("uno"))
        server.enqueue("GET", "/media/2=w3840-h2160", MockResponse().setResponseCode(500))
        server.enqueue("GET", "/media/3=w3840-h2160", image("tre"))
        val progress = mutableListOf<Int>()

        val photos = picker.downloadToPrivateCache({ "tok" }, listOf(media("1", "Uno.jpg"), media("2", "Due.jpg"), media("3", "Tre.png", "image/png"))) { done, total ->
            assertEquals(3, total)
            synchronized(progress) { progress += done }
        }

        assertEquals(listOf("Uno.jpg", "Tre.png"), photos.map { it.title })
        assertEquals(listOf(1, 2, 3), progress.sorted())
        photos.forEach { assertTrue(File((it.ref as PhotoRef.LocalFile).path).isFile) }
        assertEquals("tre", File((photos[1].ref as PhotoRef.LocalFile).path).readText())
        // Dopo un riavvio la cache restituisce titoli e ordine originali, senza la raccolta precedente.
        assertEquals(listOf("Uno.jpg", "Tre.png"), picker.cachedPhotos().map { it.title })
        assertEquals(listOf("google_photos"), temp.root.list()!!.toList())
        assertEquals("Bearer tok", server.requestsTo("GET", "/media/1=w3840-h2160").single().getHeader("Authorization"))
    }

    @Test
    fun `failed download keeps the previous collection`() = runTest {
        val picker = repository()
        server.enqueue("GET", "/media/keep=w3840-h2160", image("keep"))
        picker.downloadToPrivateCache({ "tok" }, listOf(media("keep", "Tenuta.jpg"))) { _, _ -> }
        server.always("GET", "/media/broken=w3840-h2160", MockResponse().setResponseCode(403))

        val photos = picker.downloadToPrivateCache({ "tok" }, listOf(media("broken", "Rotta.jpg"))) { _, _ -> }

        assertTrue(photos.isEmpty())
        assertEquals(listOf("Tenuta.jpg"), picker.cachedPhotos().map { it.title })
        assertEquals(listOf("google_photos"), temp.root.list()!!.toList())
    }

    @Test
    fun `download stops when free space is too low`() = runTest {
        val photos = repository(minFreeBytes = Long.MAX_VALUE)
            .downloadToPrivateCache({ "tok" }, listOf(media("1", "Uno.jpg"))) { _, _ -> }

        assertTrue(photos.isEmpty())
        assertTrue(server.requests.isEmpty())
        assertFalse(File(temp.root, "google_photos").exists())
    }

    @Test
    fun `clear cache removes photos and leftovers`() = runTest {
        val picker = repository()
        server.enqueue("GET", "/media/1=w3840-h2160", image("uno"))
        picker.downloadToPrivateCache({ "tok" }, listOf(media("1", "Uno.jpg"))) { _, _ -> }
        File(temp.root, "google_photos.staging-orfana").mkdirs()
        File(temp.root, "altro").mkdirs()

        picker.clearCache()

        assertEquals(listOf("altro"), temp.root.list()!!.toList())
        assertTrue(picker.cachedPhotos().isEmpty())
    }

    @Test
    fun `collection from the old cache location is adopted and leftovers removed`() = runTest {
        val legacyRoot = temp.newFolder("legacy")
        val legacy = GooglePhotosPickerRepository(legacyRoot, server.client, server.apiBase, 0L)
        server.enqueue("GET", "/media/1=w3840-h2160", image("uno"))
        legacy.downloadToPrivateCache({ "tok" }, listOf(media("1", "Uno.jpg"))) { _, _ -> }
        val storeRoot = temp.newFolder("store")
        File(storeRoot, "google_photos.staging-orfana").mkdirs()
        File(storeRoot, "google_photos.old-orfana").mkdirs()
        val store = GooglePhotosPickerRepository(storeRoot, server.client, server.apiBase, 0L)

        store.adoptFrom(legacy)
        store.deleteLeftovers()

        assertEquals(listOf("Uno.jpg"), store.cachedPhotos().map { it.title })
        assertTrue(File((store.cachedPhotos().single().ref as PhotoRef.LocalFile).path).startsWith(storeRoot))
        assertEquals(listOf("google_photos"), storeRoot.list()!!.toList())
        assertTrue(legacyRoot.list()!!.isEmpty())
    }

    @Test
    fun `failed migration does not erase the only cache`() = runTest {
        val root = temp.newFolder("legacy-failure")
        val legacy = GooglePhotosPickerRepository(root, server.client, server.apiBase, 0L)
        server.enqueue("GET", "/media/1=w3840-h2160", image("uno"))
        legacy.downloadToPrivateCache({ "tok" }, listOf(media("1", "Uno.jpg"))) { _, _ -> }
        val destination = GooglePhotosPickerRepository(temp.newFolder("dest-failure"),
            server.client, server.apiBase, 0L, renameDirectory = { _, _ -> false })
        destination.adoptFrom(legacy)
        assertEquals(listOf("Uno.jpg"), legacy.cachedPhotos().map { it.title })
        assertTrue(destination.cachedPhotos().isEmpty())
    }

    @Test
    fun `interrupted swap restores previous cache before clearing leftovers`() = runTest {
        val root = temp.newFolder("restore-old")
        val picker = GooglePhotosPickerRepository(root, server.client, server.apiBase, 0L)
        server.enqueue("GET", "/media/1=w3840-h2160", image("uno"))
        picker.downloadToPrivateCache({ "tok" }, listOf(media("1", "Uno.jpg"))) { _, _ -> }
        val backup = File(root, "google_photos.old-interrupted")
        assertTrue(File(root, "google_photos").renameTo(backup))
        File(root, "google_photos.staging-orphan").mkdirs()
        picker.deleteLeftovers()
        assertEquals(listOf("Uno.jpg"), picker.cachedPhotos().map { it.title })
        assertFalse(backup.exists())
        assertFalse(File(root, "google_photos.staging-orphan").exists())
    }

    @Test
    fun `failed restore keeps backup intact`() {
        val root = temp.newFolder("restore-error")
        val backup = File(root, "google_photos.old-keep").apply { mkdirs() }
        File(backup, "index.json").writeText("[]")
        File(backup, "00000.jpg").writeText("foto")
        val picker = GooglePhotosPickerRepository(root, server.client, server.apiBase, 0L,
            renameDirectory = { _, _ -> false })
        picker.deleteLeftovers()
        assertTrue(backup.isDirectory)
    }

    @Test
    fun `oversized image is rejected and old cache is preserved`() = runTest {
        val picker = repository()
        server.enqueue("GET", "/media/keep=w3840-h2160", image("keep"))
        picker.downloadToPrivateCache({ "tok" }, listOf(media("keep", "Vecchia.jpg"))) { _, _ -> }
        server.enqueue("GET", "/media/large=w3840-h2160",
            MockResponse().setHeader("Content-Type", "image/jpeg")
                .setHeader("Content-Length", (25L * 1024 * 1024).toString()).setBody("x"))
        val newPhotos = picker.downloadToPrivateCache({ "tok" }, listOf(media("large", "Grande.jpg"))) { _, _ -> }
        assertTrue(newPhotos.isEmpty())
        assertEquals(listOf("Vecchia.jpg"), picker.cachedPhotos().map { it.title })
    }

    @Test
    fun `manifest entries cannot point outside the cache`() {
        val directory = File(temp.root, "google_photos").apply { mkdirs() }
        File(temp.root, "segreto.jpg").writeText("x")
        File(directory, "index.json").writeText("""[{"id":"a","title":"Fuori","file":"../segreto.jpg"}]""")

        assertTrue(repository().cachedPhotos().isEmpty())
    }
}
