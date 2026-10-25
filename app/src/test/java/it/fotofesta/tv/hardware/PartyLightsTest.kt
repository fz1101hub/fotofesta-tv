package it.fotofesta.tv.hardware

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyLightsTest {
    private val hue = PartyLights.Device(PartyLights.Kind.HUE, "192.168.1.20", 80, token = "abc123")
    private val wled = PartyLights.Device(PartyLights.Kind.WLED, "192.168.1.21", 80)
    private val nanoleaf = PartyLights.Device(PartyLights.Kind.NANOLEAF, "192.168.1.22", 16021, token = "tok")

    @Test
    fun `pairing reads the key only when the button was pressed`() {
        assertEquals("abc-1", PartyLights.tokenFrom(PartyLights.Kind.HUE, """[{"success":{"username":"abc-1"}}]"""))
        assertNull(PartyLights.tokenFrom(PartyLights.Kind.HUE, """[{"error":{"type":101,"description":"link button not pressed"}}]"""))
        assertEquals("xyz", PartyLights.tokenFrom(PartyLights.Kind.NANOLEAF, """{"auth_token":"xyz"}"""))
        assertNull(PartyLights.tokenFrom(PartyLights.Kind.NANOLEAF, """{"auth_token":"../x"}"""))
        assertNull(PartyLights.pairCall(wled))
    }

    @Test
    fun `colour calls use the local api of each light`() {
        val hueCall = PartyLights.colorCall(hue, 0xFF0000)!!
        assertEquals("PUT", hueCall.method)
        assertEquals("http://192.168.1.20:80/api/abc123/groups/0/action", hueCall.url)
        val xy = JSONObject(hueCall.body).getJSONArray("xy")
        assertEquals(0.7006, xy.getDouble(0), 0.001)
        assertEquals(0.2993, xy.getDouble(1), 0.001)

        val wledCall = PartyLights.colorCall(wled, 0x10FF20, 0.5f)!!
        assertEquals("http://192.168.1.21:80/json/state", wledCall.url)
        val color = JSONObject(wledCall.body).getJSONArray("seg").getJSONObject(0).getJSONArray("col").getJSONArray(0)
        assertEquals(listOf(16, 255, 32), (0 until 3).map(color::getInt))
        assertEquals(128, JSONObject(wledCall.body).getInt("bri"))

        val leafCall = PartyLights.colorCall(nanoleaf, 0x0000FF)!!
        assertEquals("http://192.168.1.22:16021/api/v1/tok/state", leafCall.url)
        assertEquals(240, JSONObject(leafCall.body).getJSONObject("hue").getInt("value"))
        assertNull(PartyLights.colorCall(hue.copy(token = null), 0xFF0000))
    }

    @Test
    fun `photo colours become lively and greys a warm white`() {
        assertEquals(0xFFC98A, PartyLights.partyColor(0x808080))
        val (_, saturation, value) = PartyLights.hsv(PartyLights.partyColor(0x806050))
        assertTrue(saturation > 0.5f)
        assertTrue(value >= 0.85f)
        assertEquals(0x00FF00, PartyLights.fromHsv(120f, 1f, 1f))
    }

    @Test
    fun `devices survive a round trip to json`() {
        assertEquals(hue, PartyLights.Device.fromJson(hue.toJson()))
        assertEquals(wled, PartyLights.Device.fromJson(wled.toJson()))
        assertNull(PartyLights.Device.fromJson(JSONObject().put("kind", "LAVA")))
    }
}
