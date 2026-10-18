package it.fotofesta.tv.google

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GooglePhotosPickerRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = FakeGoogleServer()

    private fun repository() = GooglePhotosPickerRepository(server.client, server.apiBase)

    @After
    fun tearDown() = server.close()

    @Test
    fun `session is polled until photos are selected`() = runTest {
        server.enqueue("POST", "/v1/sessions", json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","pollingConfig":{"pollInterval":"1s","timeoutIn":"1800s"}}"""))
        server.enqueue(
            "GET", "/v1/sessions/s1",
            json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":false,"pollingConfig":{"pollInterval":"1s","timeoutIn":"1700s"}}"""),
            json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true,"expireTime":"2026-10-22T18:30:00.123456Z"}"""),
        )
        val picker = repository()
        val session = picker.createSession("tok")
        assertEquals("https://photos.google.com/picker/s1", session.pickerUri)
        assertNull(session.expiresAtMillis)

        val done = picker.awaitSelection({ "tok" }, session)

        assertTrue(done.mediaItemsSet)
        assertEquals(java.time.Instant.parse("2026-10-22T18:30:00.123Z").toEpochMilli(), done.expiresAtMillis)
        assertEquals(2, server.requestsTo("GET", "/v1/sessions/s1").size)
        val create = server.requestsTo("POST", "/v1/sessions").single()
        assertEquals("Bearer tok", create.getHeader("Authorization"))
        assertTrue(create.requestUrl!!.queryParameter("requestId")!!.isNotBlank())
    }

    @Test
    fun `media list follows pages, encodes tokens and marks photos`() = runTest {
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

        assertEquals(listOf("a", "v", "b"), picked.map { it.id })
        assertEquals(listOf(true, false, true), picked.map { it.isPhoto })
        assertEquals("https://lh3/b", picked.last().baseUrl)
        val pages = server.requestsTo("GET", "/v1/mediaItems")
        assertEquals("s 1", pages[0].requestUrl!!.queryParameter("sessionId"))
        assertEquals("p+2/=&x", pages[1].requestUrl!!.queryParameter("pageToken"))
    }

    @Test
    fun `session deletion is best effort`() = runTest {
        server.always("DELETE", "/v1/sessions/s1", json("""{"error":{"code":500}}""", 500))

        repository().deleteSession({ "tok" }, "s1")

        assertEquals("Bearer tok", server.requestsTo("DELETE", "/v1/sessions/s1").single().getHeader("Authorization"))
    }

    @Test
    fun `photos downloaded by older versions are deleted`() {
        val noBackup = temp.newFolder("no_backup")
        val cache = temp.newFolder("cache")
        File(noBackup, "google_photos").mkdirs()
        File(noBackup, "google_photos/00000.jpg").writeText("foto")
        File(noBackup, "google_photos.staging-1").mkdirs()
        File(cache, "google_photos.old-2").mkdirs()
        File(noBackup, "google_selection.json").writeText("{}")
        File(cache, "image_cache").mkdirs()

        GooglePhotosPickerRepository.deleteLegacyDownloads(noBackup, cache)

        assertEquals(listOf("google_selection.json"), noBackup.list()!!.toList())
        assertEquals(listOf("image_cache"), cache.list()!!.toList())
        assertFalse(File(noBackup, "google_photos").exists())
    }

    @Test
    fun `timestamps from Google are parsed`() {
        assertEquals(0L, parseTimestampMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_000L, parseTimestampMillis("1970-01-01T01:00:01+01:00"))
        assertNull(parseTimestampMillis(""))
        assertNull(parseTimestampMillis("domani"))
    }
}
