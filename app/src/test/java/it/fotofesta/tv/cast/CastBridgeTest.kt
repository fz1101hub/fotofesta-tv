package it.fotofesta.tv.cast

import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.TransitionType
import org.junit.Assert.assertEquals
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
}
