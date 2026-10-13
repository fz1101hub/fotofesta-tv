package it.fotofesta.tv.cast

import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastBridgeTest {
    @Test
    fun `parses playback commands`() {
        assertEquals(CastCommand.Play, CastBridge.parse("{\"v\":1,\"type\":\"PLAY\"}"))
        assertEquals(CastCommand.Next, CastBridge.parse("{\"v\":1,\"type\":\"NEXT\"}"))
    }

    @Test
    fun `parses and bounds settings`() {
        val result = CastBridge.parse(
            """{"v":1,"type":"SETTINGS","settings":{"intervalSeconds":999,"transition":"ZOOM","fitMode":"CROP","musicVolume":2}}"""
        ) as CastCommand.ChangeSettings
        assertEquals(120, result.settings.intervalSeconds)
        assertEquals(TransitionType.ZOOM, result.settings.transition)
        assertEquals(FitMode.CROP, result.settings.fitMode)
        assertEquals(1f, result.settings.musicVolume)
    }

    @Test
    fun `partial settings keep current values`() {
        val result = CastBridge.parse(
            """{"v":1,"type":"SETTINGS","settings":{"shuffle":true,"transition":"NOPE","intervalSeconds":"x"}}"""
        ) as CastCommand.ChangeSettings
        val current = SlideshowSettings(intervalSeconds = 20, transition = TransitionType.SLIDE, showClock = false)
        assertEquals(current.copy(shuffle = true), result.settings.applyTo(current))
        assertNull(result.settings.transition)
    }

    @Test
    fun `parses motion and sleep timer settings`() {
        val result = CastBridge.parse(
            """{"v":1,"type":"SETTINGS","settings":{"kenBurns":true,"sleepTimerMinutes":9999}}"""
        ) as CastCommand.ChangeSettings
        val applied = result.settings.applyTo(SlideshowSettings())
        assertEquals(true, applied.kenBurns)
        assertEquals(240, applied.sleepTimerMinutes)
    }

    @Test
    fun `photo ids are unique even when sender repeats them`() {
        val load = CastBridge.parse(
            """{"v":1,"type":"LOAD","photos":[{"id":"x","url":"https://example.org/a.jpg"},{"id":"x","url":"https://example.org/b.jpg"},{"url":"https://example.org/c.jpg"}]}"""
        ) as CastCommand.Load
        assertEquals(3, load.photos.map { it.id }.toSet().size)
        assertNotEquals(load.photos[0].id, load.photos[1].id)
        assertNull(load.settings)
    }

    @Test
    fun `accepts only https photo urls`() {
        val valid = CastBridge.parse(
            """{"v":1,"type":"LOAD","photos":[{"id":"1","title":"Test","url":"https://example.org/a.jpg"}]}"""
        ) as CastCommand.Load
        assertEquals(1, valid.photos.size)
        assertTrue(valid.photos.single().ref is PhotoRef.Remote)

        val invalid = runCatching {
            CastBridge.parse(
                """{"v":1,"type":"LOAD","photos":[{"id":"1","url":"http://example.org/a.jpg"}]}"""
            )
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun `rejects local hosts and embedded credentials`() {
        val urls = listOf(
            "https://localhost/photo.jpg",
            "https://127.0.0.1/photo.jpg",
            "https://192.168.1.10/photo.jpg",
            "https://user:password@example.org/photo.jpg",
            "https://[::1]/photo.jpg",
            "https://[::ffff:127.0.0.1]/photo.jpg",
            "https://[fd12:3456::1]/photo.jpg",
            "https://[fe80::1]/photo.jpg",
            "https://0177.0.0.1/photo.jpg",
            "https://2130706433/photo.jpg",
            "https://0x7f.0.0.1/photo.jpg",
            "https://100.64.0.1/photo.jpg",
            "https://10.0.0.1/photo.jpg",
            "https://nas.local/photo.jpg",
            "https://example.org:8443/photo.jpg",
        )
        urls.forEach { url ->
            val result = runCatching {
                CastBridge.parse("""{"v":1,"type":"LOAD","photos":[{"url":"$url"}]}""")
            }
            assertTrue("URL should be rejected: $url", result.isFailure)
        }
    }

    @Test
    fun `rejects unsupported protocol version`() {
        val result = runCatching { CastBridge.parse("{\"v\":2,\"type\":\"PLAY\"}") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `accepts public hosts that only look like private prefixes`() {
        listOf(
            "https://fd.example.org/photo.jpg",
            "https://feature.example.com/photo.jpg",
            "https://fcbarcelona.com/photo.jpg",
            "https://8.8.8.8/photo.jpg",
            "https://[2001:4860:4860::8888]/photo.jpg",
            "https://cdn.example.org:443/p.jpg",
        ).forEach { url -> assertTrue("URL should be accepted: $url", RemoteUrlPolicy.isAllowed(url)) }
        assertFalse(RemoteUrlPolicy.isAllowed("https://[::]/photo.jpg"))
    }
}
