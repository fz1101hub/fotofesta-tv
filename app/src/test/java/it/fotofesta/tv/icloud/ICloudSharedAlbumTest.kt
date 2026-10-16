package it.fotofesta.tv.icloud

import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.network.ScreenSize
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class ICloudSharedAlbumTest {
    private val server = MockWebServer()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val host get() = "127.0.0.1:${server.port}"
    private var webstream: (RecordedRequest) -> MockResponse = { MockResponse().setBody(ALBUM) }
    private var urlRequests = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/B0aGWZuqDGKfHtd/sharedstreams/webstream" -> webstream(request)
                    path == "/B0aGWZuqDGKfHtd/sharedstreams/webasseturls" -> {
                        urlRequests++
                        val guids = JSONObject(request.body.readUtf8()).getJSONArray("photoGuids")
                        val items = JSONObject()
                        for (index in 0 until guids.length()) {
                            val guid = guids.getString(index)
                            CHECKSUMS.getValue(guid).forEach { checksum ->
                                items.put(checksum, JSONObject().put("url_location", host).put("url_path", "/file/$checksum?v=$urlRequests"))
                            }
                        }
                        MockResponse().setBody(JSONObject().put("items", items).put("locations", JSONObject()).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun close() = server.shutdown()

    private fun client() = ICloudAlbumClient(
        OkHttpClient(),
        scheme = "http",
        firstHost = { host },
        allowedApiHost = { it == host },
        allowedFileHost = { it == host },
    )

    @Test
    fun linksAndCodesGiveTheAlbumToken() {
        assertEquals("B0aGWZuqDGKfHtd", ICloudLink.token("https://www.icloud.com/sharedalbum/#B0aGWZuqDGKfHtd"))
        assertEquals("B0aGWZuqDGKfHtd", ICloudLink.token("  https://www.icloud.com/sharedalbum/it-it/#B0aGWZuqDGKfHtd \n"))
        assertEquals("B0aGWZuqDGKfHtd", ICloudLink.token("icloud.com/sharedalbum/#B0aGWZuqDGKfHtd;CDEF"))
        assertEquals("B0aGWZuqDGKfHtd", ICloudLink.token("B0aGWZuqDGKfHtd"))
        assertEquals("B0aGWZuqDGKfHtd", ICloudLink.token("#B0aGWZuqDGKfHtd"))
        assertEquals("B0aGWZuqDGKfHtd", ICloudLink.token("Guarda l'album: https://www.icloud.com/sharedalbum/#B0aGWZuqDGKfHtd grazie!"))
        assertNull(ICloudLink.token("https://share.icloud.com/photos/0aBcD"))
        assertNull(ICloudLink.token("https://www.icloud.com/sharedalbum/"))
        assertNull(ICloudLink.token("ciao mondo"))
        assertNull(ICloudLink.token("<script>"))
    }

    @Test
    fun theServerComesFromTheToken() {
        // Primo carattere "B": partizione dai due caratteri successivi in base 62 ("0a" = 36).
        assertEquals("p36-sharedstreams.icloud.com", ICloudLink.partitionHost("B0aGWZuqDGKfHtd"))
        assertEquals("p10-sharedstreams.icloud.com", ICloudLink.partitionHost("B0AGWZuqDGKfHtd"))
        // Primo carattere "A": partizione dal solo carattere successivo ("5" = 5, con lo zero davanti).
        assertEquals("p05-sharedstreams.icloud.com", ICloudLink.partitionHost("A5bcdefghijk"))
    }

    @Test
    fun readsTheAlbumPhotosAndVideosInShootingOrder() = runTest {
        val album = client().album("B0aGWZuqDGKfHtd")
        assertEquals("Compleanno di Giulia", album.name)
        assertEquals("Marco Rossi", album.owner)
        assertEquals(listOf("g2", "g1", "v1"), album.items.map { it.guid })
        assertEquals(2, album.photoCount)
        assertEquals(1, album.videoCount)
        val photos = album.toPhotos()
        assertEquals(PhotoRef.ICloudPhoto("g1"), photos[1].ref)
        assertEquals(listOf("Torta", "", ""), photos.map { it.title })
        assertEquals(listOf("Sara", "Giulia", "Giulia"), photos.map { it.sender })
        assertEquals(listOf(false, false, true), photos.map { it.video })
        assertTrue(photos.all { it.source == SourceType.ICLOUD })
        val request = requests.first()
        assertEquals("POST", request.method)
        assertEquals("https://www.icloud.com", request.getHeader("Origin"))
        assertEquals("""{"streamCtag":null}""", request.body.readUtf8())
    }

    @Test
    fun followsTheServerRedirectOnlyToAppleServers() = runTest {
        var first = true
        webstream = {
            if (first) {
                first = false
                MockResponse().setResponseCode(330).setBody("""{"X-Apple-MMe-Host":"$host"}""")
            } else {
                MockResponse().setBody(ALBUM)
            }
        }
        assertEquals(3, client().album("B0aGWZuqDGKfHtd").items.size)

        webstream = { MockResponse().setResponseCode(330).setBody("""{"X-Apple-MMe-Host":"attacker.example"}""") }
        expect(ICloudAlbumException.Reason.CHANGED) { client().album("B0aGWZuqDGKfHtd") }
    }

    @Test
    fun explainsMissingAlbumsAndChangedResponses() = runTest {
        webstream = { MockResponse().setResponseCode(404) }
        expect(ICloudAlbumException.Reason.NOT_FOUND) { client().album("B0aGWZuqDGKfHtd") }
        webstream = { MockResponse().setBody("""{"pictures":[]}""") }
        expect(ICloudAlbumException.Reason.CHANGED) { client().album("B0aGWZuqDGKfHtd") }
        webstream = { MockResponse().setResponseCode(503) }
        expect(ICloudAlbumException.Reason.UNREACHABLE) { client().album("B0aGWZuqDGKfHtd") }
        server.shutdown()
        expect(ICloudAlbumException.Reason.UNREACHABLE) { client().album("B0aGWZuqDGKfHtd") }
    }

    @Test
    fun picksTheVersionThatFitsTheScreenAndTheVideoFile() = runTest {
        val album = client().album("B0aGWZuqDGKfHtd")
        val photo = album.items.first { it.guid == "g1" }
        assertEquals("c1-big", photo.photoDerivative(ScreenSize(1920, 1080))?.checksum)
        assertEquals("c1-small", photo.photoDerivative(ScreenSize(640, 360))?.checksum)
        assertEquals("c1-big", photo.photoDerivative(ScreenSize(3840, 2160))?.checksum)
        val video = album.items.first { it.guid == "v1" }
        assertEquals("cv-720", video.videoDerivative()?.checksum)
    }

    @Test
    fun fileAddressesAreFetchedInGroupsAndRenewed() = runTest {
        var now = 0L
        val stream = ICloudAlbumStream(client(), ScreenSize(1920, 1080), clock = { now })
        expect(ICloudAlbumException.Reason.NOT_FOUND) { stream.mediaUrl("g1") }
        stream.use(client().album("B0aGWZuqDGKfHtd"))
        val first = stream.mediaUrl("g1")
        assertEquals("http://$host/file/c1-big?v=1", first)
        // Il gruppo comprende le successive: niente nuova richiesta per il video.
        assertEquals("http://$host/file/cv-720?v=1", stream.mediaUrl("v1"))
        assertEquals(1, urlRequests)
        // Indirizzo che non funziona più: si rinnova.
        assertEquals("http://$host/file/c1-big?v=2", stream.mediaUrl("g1", failed = first))
        // Dopo mezz'ora si rinnova comunque.
        now += ICloudAlbumStream.URL_LIFETIME_MS
        assertEquals("http://$host/file/c1-big?v=3", stream.mediaUrl("g1"))
        expect(ICloudAlbumException.Reason.NOT_FOUND) { stream.mediaUrl("sconosciuta") }
    }

    @Test
    fun fileAddressesOutsideAppleAreRefused() = runTest {
        val strict = ICloudAlbumClient(OkHttpClient(), scheme = "http", firstHost = { host }, allowedApiHost = { true })
        val album = strict.album("B0aGWZuqDGKfHtd")
        expect(ICloudAlbumException.Reason.CHANGED) { strict.assetUrls(album, listOf("g1")) }
    }

    private suspend fun expect(reason: ICloudAlbumException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Atteso $reason")
        } catch (failure: ICloudAlbumException) {
            assertEquals(reason, failure.reason)
        }
    }

    private companion object {
        val CHECKSUMS = mapOf(
            "g1" to listOf("c1-small", "c1-big"),
            "g2" to listOf("c2-small", "c2-big"),
            "v1" to listOf("cv-poster", "cv-360", "cv-720"),
        )

        val ALBUM = """
            {"streamName":"Compleanno di Giulia","userFirstName":"Marco","userLastName":"Rossi","streamCtag":"1",
             "itemsReturned":"3","photos":[
              {"photoGuid":"g1","caption":"","contributorFirstName":"Giulia","dateCreated":"2026-10-10T20:05:00Z",
               "derivatives":{"342":{"checksum":"c1-small","fileSize":"20000","width":"640","height":"427"},
                              "2049":{"checksum":"c1-big","fileSize":"900000","width":"2048","height":"1365"}}},
              {"photoGuid":"g2","caption":" Torta ","contributorFullName":"Sara Bianchi","contributorFirstName":"Sara","dateCreated":"2026-10-10T19:00:00Z",
               "derivatives":{"342":{"checksum":"c2-small","fileSize":"20000","width":"342","height":"342"},
                              "2049":{"checksum":"c2-big","fileSize":"900000","width":"1365","height":"2048"}}},
              {"photoGuid":"v1","mediaAssetType":"video","contributorFirstName":"Giulia",
               "derivatives":{"PosterFrame":{"checksum":"cv-poster","fileSize":"50000","width":"1280","height":"720"},
                              "360p":{"checksum":"cv-360","fileSize":"2000000","width":"640","height":"360"},
                              "720p":{"checksum":"cv-720","fileSize":"8000000","width":"1280","height":"720"}}}
             ]}
        """.trimIndent()
    }
}
