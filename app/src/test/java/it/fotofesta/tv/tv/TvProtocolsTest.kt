package it.fotofesta.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TvProtocolsTest {
    private fun jpeg(vararg body: Int) = (listOf(0xFF, 0xD8) + body.toList() + listOf(0xFF, 0xD9)).map(Int::toByte).toByteArray()

    @Test
    fun `mjpeg frames are found between the jpeg markers even when split`() {
        val frames = mutableListOf<ByteArray>()
        val parser = MjpegParser { frames += it }
        val first = jpeg(1, 2, 3)
        val second = jpeg(4, 5)
        val stream = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + first + "\r\n--frame\r\n\r\n".toByteArray() + second
        // A pezzi di tre byte, come arrivano dalla rete.
        stream.toList().chunked(3).forEach { parser.feed(it.toByteArray()) }
        assertEquals(2, frames.size)
        assertTrue(frames[0].contentEquals(first))
        assertTrue(frames[1].contentEquals(second))
    }

    @Test
    fun `an endless frame is dropped`() {
        val frames = mutableListOf<ByteArray>()
        val parser = MjpegParser(maxFrameBytes = 10) { frames += it }
        parser.feed(jpeg(*IntArray(50) { 7 }))
        parser.feed(jpeg(1))
        assertEquals(1, frames.size)
        assertEquals(5, frames[0].size)
    }

    private fun wav(samples: ShortArray): ByteArray {
        val header = ByteArray(WavMeter.WAV_HEADER)
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            data[index * 2] = (sample.toInt() and 0xFF).toByte()
            data[index * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
        }
        return header + data
    }

    @Test
    fun `applause meter gets louder with louder sound`() {
        val levels = mutableListOf<Float>()
        val meter = WavMeter(windowSamples = 100) { levels += it }
        val quiet = ShortArray(100) { (300 * sin(it * 2 * PI / 20)).toInt().toShort() }
        val loud = ShortArray(100) { (20_000 * sin(it * 2 * PI / 20)).toInt().toShort() }
        val bytes = wav(quiet + loud)
        bytes.toList().chunked(7).forEach { meter.feed(it.toByteArray()) }
        assertEquals(2, levels.size)
        assertTrue(levels[1] > levels[0])
        assertEquals(0f, WavMeter.level(0.0))
        assertEquals(1f, WavMeter.level(Short.MAX_VALUE.toDouble()))
    }

    @Test
    fun `the first buzzer after the signal wins`() {
        val round = BuzzerRound(maxTeams = 2)
        assertFalse(round.press(10))
        assertFalse(round.press(20))
        // Una terza squadra non c'è posto.
        assertFalse(round.press(30))
        assertEquals(listOf(10, 20), round.teams())
        round.arm()
        assertTrue(round.press(20))
        assertFalse(round.press(10))
        assertEquals(20, round.winner)
        round.award()
        assertEquals(1, round.score(20))
        round.arm()
        assertNull(round.winner)
    }

    @Test
    fun `ssdp answers only searches for renderers`() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nMX: 2\r\n\r\n"
        assertEquals(Upnp.RENDERER, Upnp.searchTarget(search))
        assertEquals(Upnp.RENDERER, Upnp.searchTarget(search.replace(Upnp.RENDERER, "ssdp:all")))
        assertNull(Upnp.searchTarget(search.replace(Upnp.RENDERER, "urn:schemas-upnp-org:device:MediaServer:1")))
        assertNull(Upnp.searchTarget("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n"))
        val response = Upnp.searchResponse(Upnp.RENDERER, "http://192.168.1.5:49152/upnp/description.xml", "1234")
        assertEquals("http://192.168.1.5:49152/upnp/description.xml", Upnp.header(response, "location"))
        assertEquals("uuid:1234::${Upnp.RENDERER}", Upnp.header(response, "USN"))
    }

    @Test
    fun `camera addresses get the ip webcam defaults`() {
        assertEquals("http://192.168.1.20:8080/video", IpCamera.url("192.168.1.20", "video"))
        assertEquals("http://192.168.1.20:4747/audio.wav", IpCamera.url(" http://192.168.1.20:4747/ ", "audio.wav"))
        assertEquals("https://cam.local:8443/video", IpCamera.url("https://cam.local:8443", "video"))
    }

    @Test
    fun `soap actions and arguments are read`() {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>
            <CurrentURI>http://192.168.1.20:8080/foto.jpg?a=1&amp;b=2</CurrentURI><CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>
            </u:SetAVTransportURI></s:Body></s:Envelope>"""
        assertEquals("SetAVTransportURI", Upnp.soapAction("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""))
        assertNull(Upnp.soapAction(null))
        assertEquals("http://192.168.1.20:8080/foto.jpg?a=1&b=2", Upnp.argument(body, "CurrentURI"))
        assertEquals("<DIDL-Lite/>", Upnp.argument(body, "CurrentURIMetaData"))
        assertNull(Upnp.argument(body, "NextURI"))
        assertTrue(Upnp.response(Upnp.AV_TRANSPORT, "GetTransportInfo", mapOf("CurrentTransportState" to "PLAYING")).contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))
        assertTrue(Upnp.description("TV di <Casa>", "abc").contains("<friendlyName>TV di &lt;Casa&gt;</friendlyName>"))
    }
}
