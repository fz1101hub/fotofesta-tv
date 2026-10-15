package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GooglePhotosStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = FakeGoogleServer()
    private val tokens = MemoryTokenStore(accessToken = "tok", refreshToken = "refresh")
    @Volatile
    private var now = 1_800_000_000_000L

    private val store by lazy { GoogleSelectionStore(File(temp.root, "selection.json")) }

    private val stream by lazy {
        GooglePhotosStream(
            GooglePhotosPickerRepository(server.client, server.apiBase) { now },
            GoogleDeviceAuthRepository(server.client, tokens, "client", "secret", server.oauthBase) { now },
            store,
        ) { now }
    }

    @After
    fun tearDown() = server.close()

    private fun select(vararg ids: String, expiresAt: Long? = now + 7 * 24 * 3_600_000L) {
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_600
        store.save(GoogleSelection("s1", expiresAt, ids.map { GoogleSelection.Item(it, "$it.jpg") }))
    }

    private fun listing(suffix: String, vararg ids: String) =
        json("""{"mediaItems":[${ids.joinToString(",") { """{"id":"$it","mediaFile":{"baseUrl":"https://lh3.example/$it-$suffix","mimeType":"image/jpeg","filename":"$it.jpg"}}""" }}]}""")

    @Test
    fun `addresses come from the session and are reused for fifty minutes`() = runTest {
        select("a", "b")
        server.enqueue("GET", "/v1/mediaItems", listing("1", "a", "b"), listing("2", "a", "b"))

        val first = stream.access("a")
        val second = stream.access("b")
        now += 49 * 60_000L
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_600
        val stillCached = stream.access("a")
        now += 2 * 60_000L
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_600
        val renewed = stream.access("a")

        assertEquals(GooglePhotosStream.Access("https://lh3.example/a-1", "tok"), first)
        assertEquals("https://lh3.example/b-1", second.baseUrl)
        assertEquals("https://lh3.example/a-1", stillCached.baseUrl)
        assertEquals("https://lh3.example/a-2", renewed.baseUrl)
        val lists = server.requestsTo("GET", "/v1/mediaItems")
        assertEquals(2, lists.size)
        assertEquals("s1", lists[0].requestUrl!!.queryParameter("sessionId"))
        assertEquals("Bearer tok", lists[0].getHeader("Authorization"))
    }

    @Test
    fun `parallel requests share one listing`() = runTest {
        select("a", "b", "c")
        server.enqueue("GET", "/v1/mediaItems", listing("1", "a", "b", "c"))

        val urls = listOf("a", "b", "c").map { id -> async { stream.access(id).baseUrl } }.awaitAll()

        assertEquals(listOf("https://lh3.example/a-1", "https://lh3.example/b-1", "https://lh3.example/c-1"), urls)
        assertEquals(1, server.requestsTo("GET", "/v1/mediaItems").size)
    }

    @Test
    fun `a stale address renews token and listing`() = runTest {
        select("a")
        server.enqueue("GET", "/v1/mediaItems", listing("1", "a"), listing("2", "a"))
        server.enqueue("POST", "/oauth/token", json("""{"access_token":"tok2","expires_in":3600}"""))

        stream.access("a")
        val renewed = stream.access("a", stale = true)

        assertEquals(GooglePhotosStream.Access("https://lh3.example/a-2", "tok2"), renewed)
        assertEquals(1, server.requestsTo("POST", "/oauth/token").size)
    }

    @Test
    fun `a missing photo is looked up again only after a minute`() = runTest {
        select("a", "gone")
        server.enqueue("GET", "/v1/mediaItems", listing("1", "a"), listing("2", "a"))

        stream.access("a")
        val first = runCatching { stream.access("gone") }.exceptionOrNull()
        now += 61_000L
        val second = runCatching { stream.access("gone") }.exceptionOrNull()

        assertEquals(R.string.photo_unavailable, (first as UserFacingException).messageRes)
        assertEquals(R.string.photo_unavailable, (second as UserFacingException).messageRes)
        assertEquals(2, server.requestsTo("GET", "/v1/mediaItems").size)
    }

    @Test
    fun `an expired session asks to choose the photos again`() = runTest {
        select("a")
        server.enqueue("GET", "/v1/mediaItems", json("""{"error":{"code":404,"message":"Session not found"}}""", 404))

        try {
            stream.access("a")
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_selection_gone, expected.messageRes)
        }
        assertTrue(stream.selectionGone.value)

        stream.reset()
        assertFalse(stream.selectionGone.value)
    }

    @Test
    fun `a selection past its expiry is not requested from Google`() = runTest {
        select("a", expiresAt = now - 1)

        val failure = runCatching { stream.access("a") }.exceptionOrNull()

        assertEquals(R.string.error_google_selection_gone, (failure as UserFacingException).messageRes)
        assertTrue(stream.selectionGone.value)
        assertTrue(server.requestsTo("GET", "/v1/mediaItems").isEmpty())
    }

    @Test
    fun `a replaced selection is not marked as expired by the old session`() = runTest {
        select("a")
        server.onDispatch = { request ->
            // Mentre l'elenco della vecchia sessione è in corso, arriva una selezione nuova.
            if (request.requestUrl!!.encodedPath == "/v1/mediaItems") {
                store.save(GoogleSelection("s2", null, listOf(GoogleSelection.Item("n", "n.jpg"))))
            }
        }
        server.enqueue("GET", "/v1/mediaItems", json("""{"error":{"code":404}}""", 404))

        runCatching { stream.access("a") }

        assertFalse(stream.selectionGone.value)
    }

    @Test
    fun `the sized address asks Google to fit or fill the frame`() {
        assertEquals("https://lh3/a=w1920-h1080", GooglePhotosStream.sizedUrl("https://lh3/a", 1920, 1080, crop = false))
        assertEquals("https://lh3/a=w400-h400-c", GooglePhotosStream.sizedUrl("https://lh3/a", 400, 400, crop = true))
    }
}

class GoogleSelectionStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `selection survives a restart and becomes photos without copies`() {
        val file = File(temp.root, "selection.json")
        val selection = GoogleSelection("s1", 1_234L, listOf(GoogleSelection.Item("a", "Mare.jpg"), GoogleSelection.Item("b", "Monti \"2\".png")))

        GoogleSelectionStore(file).save(selection)
        val restored = GoogleSelectionStore(file).current()

        assertEquals(selection, restored)
        val photos = restored!!.toPhotos()
        assertEquals(listOf("google:a", "google:b"), photos.map { it.id })
        assertEquals(PhotoRef.GooglePhoto("b"), photos[1].ref)
        assertEquals(listOf("selection.json"), temp.root.list()!!.toList())
    }

    @Test
    fun `missing or damaged files give no selection`() {
        val file = File(temp.root, "selection.json")
        assertEquals(null, GoogleSelectionStore(file).current())
        file.writeText("{non json")
        assertEquals(null, GoogleSelectionStore(file).current())
        file.writeText("""{"sessionId":"s1","items":[]}""")
        assertEquals(null, GoogleSelectionStore(file).current())
    }

    @Test
    fun `clear removes the file and the copy in memory`() {
        val file = File(temp.root, "selection.json")
        val store = GoogleSelectionStore(file)
        store.save(GoogleSelection("s1", null, listOf(GoogleSelection.Item("a", "a.jpg"))))

        store.clear()

        assertEquals(null, store.current())
        assertFalse(file.exists())
    }

    @Test
    fun `expiry is checked against the clock`() {
        val selection = GoogleSelection("s1", 1_000L, emptyList())
        assertFalse(selection.isExpired(999L))
        assertTrue(selection.isExpired(1_000L))
        assertFalse(selection.copy(expiresAtMillis = null).isExpired(Long.MAX_VALUE))
    }
}
