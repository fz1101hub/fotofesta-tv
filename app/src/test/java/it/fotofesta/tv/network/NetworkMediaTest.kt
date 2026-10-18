package it.fotofesta.tv.network

import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class NetworkMediaTest {
    private val mimeTypes = NetworkMedia.imageMimeTypes(34)
    private val photosAndVideos: (String) -> Boolean = { it in mimeTypes || it.startsWith("video/") }

    private fun description(controlUrl: String, udn: String = "uuid:nas-1", name: String = "NAS di casa") = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>$name</friendlyName>
            <UDN>$udn</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/cm</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <controlURL>$controlUrl</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun browseResponse(didl: String, returned: Int, total: Int) = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
        <Result>${escape(didl)}</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches><UpdateID>1</UpdateID>
        </u:BrowseResponse></s:Body></s:Envelope>
    """.trimIndent()

    private fun didl(content: String) =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">$content</DIDL-Lite>"""

    private fun container(id: String, title: String) = """<container id="$id" parentID="0" restricted="1"><dc:title>$title</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>"""

    private fun photo(id: String, title: String, url: String) =
        """<item id="$id" parentID="x" restricted="1"><dc:title>$title</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>""" +
            """<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="160x120">$url?tn</res>""" +
            """<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="4000x3000">$url</res>""" +
            """<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED" resolution="1024x768">$url?med</res></item>"""

    @Test
    fun parsesTheDeviceAndResolvesTheControlAddress() {
        val server = UpnpXml.parseDevice(description("ctl/content").toByteArray(), "http://192.168.1.10:8200/rootDesc.xml")!!
        assertEquals("uuid:nas-1", server.udn)
        assertEquals("NAS di casa", server.name)
        assertEquals("http://192.168.1.10:8200/ctl/content", server.controlUrl)
        // Un router senza ContentDirectory non è un server multimediale.
        val router = description("/x").replace("ContentDirectory", "WANIPConnection")
        assertNull(UpnpXml.parseDevice(router.toByteArray(), "http://192.168.1.1/desc.xml"))
    }

    @Test
    fun choosesTheOriginalPhotoAndSkipsOtherFiles() {
        val content = didl(
            container("10", "Vacanze &amp; mare") + photo("p1", "IMG_0001.jpg", "/media/1.jpg") +
                """<item id="a1"><dc:title>Canzone</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>""" +
                """<res protocolInfo="http-get:*:audio/mpeg:*">/media/a.mp3</res><res protocolInfo="http-get:*:image/jpeg:*">/media/cover.jpg</res></item>""" +
                """<item id="r1"><dc:title>Raw</dc:title><res protocolInfo="http-get:*:image/x-canon-cr2:*">/media/r.cr2</res></item>""" +
                """<item id="p2"><dc:title>Senza risoluzione</dc:title><res protocolInfo="http-get:*:image/png:*">http://192.168.1.10:8200/media/2.png</res></item>""",
        )
        val page = UpnpXml.parseBrowseResponse(browseResponse(content, 5, 5).toByteArray(), "http://192.168.1.10:8200/ctl", mimeTypes::contains)
        assertEquals(listOf(MediaContainer("10", "Vacanze & mare")), page.containers)
        assertEquals(
            listOf(
                MediaFile("p1", "IMG_0001.jpg", "http://192.168.1.10:8200/media/1.jpg", "image/jpeg"),
                MediaFile("p2", "Senza risoluzione", "http://192.168.1.10:8200/media/2.png", "image/png"),
            ),
            page.files,
        )
        assertEquals(5, page.total)
    }

    @Test
    fun photosUseTheLightestVersionThatCoversTheScreen() {
        val xml = browseResponse(didl(photo("p1", "IMG.jpg", "/media/1.jpg")), 1, 1).toByteArray()
        val base = "http://192.168.1.10:8200/ctl"
        // Schermo Full HD: basta la versione grande 1024×768? No, serve almeno 1920×1080: si prende
        // l'originale. Con uno schermo piccolo basta quella media.
        assertEquals("http://192.168.1.10:8200/media/1.jpg", UpnpXml.parseBrowseResponse(xml, base, mimeTypes::contains, ScreenSize(1920, 1080)).files.single().url)
        assertEquals("http://192.168.1.10:8200/media/1.jpg?med", UpnpXml.parseBrowseResponse(xml, base, mimeTypes::contains, ScreenSize(1024, 600)).files.single().url)
        val sized = didl(
            """<item id="p2"><dc:title>Grande</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>""" +
                """<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="6000x4000">/o.jpg</res>""" +
                """<res protocolInfo="http-get:*:image/jpeg:*" resolution="2560x1707">/l.jpg</res>""" +
                """<res protocolInfo="http-get:*:image/jpeg:*" resolution="1920x1280">/m.jpg</res>""" +
                """<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="4000x3000">/t.jpg</res></item>""",
        )
        val page = browseResponse(sized, 1, 1).toByteArray()
        assertEquals("http://192.168.1.10:8200/m.jpg", UpnpXml.parseBrowseResponse(page, base, mimeTypes::contains, ScreenSize(1920, 1080)).files.single().url)
        assertEquals("http://192.168.1.10:8200/o.jpg", UpnpXml.parseBrowseResponse(page, base, mimeTypes::contains, ScreenSize(3840, 2160)).files.single().url)
        // Senza dimensioni dello schermo resta l'originale.
        assertEquals("http://192.168.1.10:8200/o.jpg", UpnpXml.parseBrowseResponse(page, base, mimeTypes::contains).files.single().url)
    }

    @Test
    fun videosKeepTheirVideoVersionAndCoversAreNotPhotos() {
        val content = didl(
            """<item id="v1"><dc:title>Torta.mp4</dc:title><upnp:class>object.item.videoItem</upnp:class>""" +
                """<res protocolInfo="http-get:*:image/jpeg:*" resolution="3840x2160">/media/v.jpg</res>""" +
                """<res protocolInfo="http-get:*:video/mp4:*" resolution="1920x1080">/media/v.mp4</res></item>""" +
                """<item id="a1"><dc:title>Canzone</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>""" +
                """<res protocolInfo="http-get:*:image/jpeg:*">/media/cover.jpg</res></item>""",
        )
        val page = UpnpXml.parseBrowseResponse(browseResponse(content, 2, 2).toByteArray(), "http://192.168.1.10:8200/ctl", photosAndVideos)
        assertEquals(listOf(MediaFile("v1", "Torta.mp4", "http://192.168.1.10:8200/media/v.mp4", "video/mp4")), page.files)
        // Senza video la stessa voce non diventa una foto.
        assertEquals(emptyList<MediaFile>(), UpnpXml.parseBrowseResponse(browseResponse(content, 2, 2).toByteArray(), "http://192.168.1.10:8200/ctl", mimeTypes::contains).files)
    }

    @Test
    fun refusesDoctypesAndReportsSoapFaults() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><root><device><UDN>&x;</UDN></device></root>"""
        try {
            UpnpXml.parseDevice(evil.toByteArray(), "http://192.168.1.10/d.xml")
            fail("DOCTYPE va rifiutato")
        } catch (_: IOException) {
        }
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        try {
            UpnpXml.parseBrowseResponse(fault.toByteArray(), "http://192.168.1.10/ctl", mimeTypes::contains)
            fail("Fault va segnalato")
        } catch (failure: IOException) {
            assertTrue(failure.message!!.contains("701"))
        }
    }

    @Test
    fun browseRequestEscapesTheObjectId() {
        val request = UpnpXml.browseRequest("64\$1<&>", 500, 250)
        assertTrue(request.contains("<ObjectID>64\$1&lt;&amp;&gt;</ObjectID>"))
        assertTrue(request.contains("<StartingIndex>500</StartingIndex><RequestedCount>250</RequestedCount>"))
    }

    @Test
    fun parsesSsdpAnswers() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.10:8200/rootDesc.xml\r\n" +
            "ST: urn:schemas-upnp-org:service:ContentDirectory:1\r\nUSN: uuid:nas-1::urn:schemas-upnp-org:service:ContentDirectory:1\r\n\r\n"
        assertEquals(Ssdp.Answer("http://192.168.1.10:8200/rootDesc.xml", "uuid:nas-1"), Ssdp.parseAnswer(answer))
        assertNull(Ssdp.parseAnswer("NOTIFY * HTTP/1.1\r\nLOCATION: http://x/\r\nUSN: uuid:a\r\n\r\n"))
        assertNull(Ssdp.parseAnswer("HTTP/1.1 200 OK\r\nUSN: uuid:a\r\n\r\n"))
        assertTrue(Ssdp.searchMessage(Ssdp.CONTENT_DIRECTORY).startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
    }

    @Test
    fun networkPathsRoundTrip() {
        val path = NetworkPath.of("uuid:nas-1", "64\$1#2")
        assertEquals("uuid:nas-1" to "64\$1#2", NetworkPath.parse(path))
        assertNull(NetworkPath.parse("/storage/1234-ABCD/DCIM"))
        assertNull(NetworkPath.parse("upnp:#0"))
    }

    /** Server DLNA finto: cartella principale con due sottocartelle, una delle quali a più pagine. */
    private fun fakeNas(failing: Set<String> = emptySet()): FakeLanServer {
        lateinit var server: FakeLanServer
        server = FakeLanServer { request ->
            when {
                request.path == "/rootDesc.xml" -> FakeResponse(body = description("/ctl").toByteArray())
                request.path == "/ctl" -> {
                    val id = Regex("<ObjectID>(.*?)</ObjectID>").find(request.text)!!.groupValues[1]
                    val start = Regex("<StartingIndex>(\\d+)</StartingIndex>").find(request.text)!!.groupValues[1].toInt()
                    if (id in failing) return@FakeLanServer FakeResponse(500, browseResponse("", 0, 0).replace("<Result></Result>", "<s:Fault><errorCode>720</errorCode></s:Fault>").toByteArray())
                    val all = when (id) {
                        "0" -> listOf(
                            container("A", "Compleanno"),
                            container("B", "Mare"),
                            photo("p0", "copertina.jpg", "/m/0.jpg"),
                            container("M", "Musica").replace("object.container.storageFolder", "object.container.album.musicAlbum"),
                        )
                        // Un album di musica non si apre mai durante la ricerca delle foto.
                        "M" -> error("Album di musica aperto")
                        "A" -> (1..3).map { photo("a$it", "a$it.jpg", "/m/a$it.jpg") } +
                            """<item id="va"><dc:title>festa.mov</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/quicktime:*">/m/festa.mov</res></item>"""
                        // 600 elementi: due pagine da 500.
                        "B" -> (1..600).map { photo("b$it", "b$it.jpg", "/m/b$it.jpg") }
                        else -> emptyList()
                    }
                    val page = all.drop(start).take(500)
                    FakeResponse(body = browseResponse(didl(page.joinToString("")), page.size, all.size).toByteArray(), chunked = id == "A")
                }
                else -> FakeResponse(404)
            }
        }
        return server
    }

    @Test
    fun scansFoldersWithPagesAndSubfolders() {
        fakeNas().use { nas ->
            val location = "${nas.base}/rootDesc.xml"
            val media = NetworkMedia(LanHttp(allowLoopback = true), mimeTypes, search = { listOf(Ssdp.Answer(location, "uuid:nas-1"), Ssdp.Answer(location, "uuid:nas-1")) })
            val servers = media.discover()
            assertEquals(listOf("NAS di casa"), servers.map { it.name })
            val root = media.list(servers[0], NetworkMedia.ROOT)
            assertEquals(listOf("Compleanno", "Mare", "Musica"), root.containers.map { it.title })
            assertEquals(listOf(false, false, true), root.containers.map { it.musicOrVideo })
            assertEquals(1, root.files.size)
            assertEquals(600, media.list(servers[0], "B").files.size)

            val photos = media.scanPhotos(servers[0], NetworkMedia.ROOT)
            assertEquals(605, photos.size)
            assertEquals(listOf("copertina", "a1", "a2", "a3", "festa", "b1"), photos.take(6).map { it.title })
            assertEquals(listOf(false, false, false, false, true, false), photos.take(6).map { it.video })
            assertEquals(PhotoRef.NetworkPhoto("${nas.base}/m/a1.jpg"), photos[1].ref)
            assertEquals("net:uuid:nas-1:a1", photos[1].id)
            assertTrue(photos.all { it.source == SourceType.NETWORK })
            assertEquals(5, media.scanPhotos(servers[0], NetworkMedia.ROOT, limit = 5).size)
            assertTrue(synchronized(nas.requests) { nas.requests.none { it.text.contains("<ObjectID>M</ObjectID>") } })
        }
    }

    @Test
    fun unreadableSubfoldersAreSkippedButTheChosenFolderMustRead() {
        fakeNas(failing = setOf("B")).use { nas ->
            val media = NetworkMedia(LanHttp(allowLoopback = true), mimeTypes, search = { emptyList() })
            val server = media.find("uuid:nas-1", "${nas.base}/rootDesc.xml")!!
            assertEquals(5, media.scanPhotos(server, NetworkMedia.ROOT).size)
            try {
                media.scanPhotos(server, "B")
                fail("La cartella scelta deve leggersi")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun findsTheServerAgainWhenItsAddressChanges() {
        fakeNas().use { nas ->
            val moved = "${nas.base}/rootDesc.xml"
            var searches = 0
            val media = NetworkMedia(LanHttp(allowLoopback = true), mimeTypes, search = {
                searches++
                listOf(Ssdp.Answer(moved, "uuid:nas-1"))
            })
            // Indirizzo salvato non più valido (porta chiusa): si cerca in rete per UDN.
            val server = media.find("uuid:nas-1", "http://127.0.0.1:9/rootDesc.xml")
            assertEquals(moved, server?.location)
            assertEquals(1, searches)
            assertNull(NetworkMedia(LanHttp(allowLoopback = true), mimeTypes, search = { emptyList() }).find("uuid:altro", moved))
        }
    }
}
