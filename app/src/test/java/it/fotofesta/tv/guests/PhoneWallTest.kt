package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWallTest {
    private val wall = PhoneWall()
    private fun player(n: Int) = "telefono%04d".format(n)
    private fun join(n: Int) = wall.handle(mapOf("type" to "wall", "command" to "join", "player" to player(n))).first

    @Test
    fun `phones line up in order and get their slot`() {
        assertEquals("null", wall.phoneJson(player(1)))
        assertEquals(403, join(1))
        wall.start()
        assertEquals(200, join(1))
        assertEquals(200, join(2))
        assertEquals(200, join(1))
        val second = JSONObject(wall.phoneJson(player(2)))
        assertEquals("joining", second.getString("phase"))
        assertEquals(1, second.getInt("slot"))
        assertEquals(2, second.getInt("count"))
        assertTrue(JSONObject(wall.phoneJson(player(9))).isNull("slot"))
    }

    @Test
    fun `the photo goes only to the phones already in line`() {
        wall.start()
        assertFalse(wall.show("foto-1"))
        join(1)
        assertTrue(wall.show("foto-1"))
        val json = JSONObject(wall.phoneJson(player(1)))
        assertEquals("showing", json.getString("phase"))
        assertEquals("foto-1", json.getString("photo"))
        assertEquals(409, join(2))
        wall.rearrange()
        assertEquals(200, join(2))
        assertTrue(JSONObject(wall.phoneJson(player(2))).isNull("photo"))
    }

    @Test
    fun `leaving, limits and stop`() {
        wall.start()
        repeat(PhoneWall.MAX_PHONES) { assertEquals(200, join(it)) }
        assertEquals(507, join(99))
        assertEquals(200, wall.handle(mapOf("command" to "leave", "player" to player(0))).first)
        assertEquals(200, join(99))
        assertEquals(400, wall.handle(mapOf("command" to "join", "player" to "x")).first)
        assertEquals(400, wall.handle(mapOf("command" to "dance", "player" to player(5))).first)
        wall.stop()
        assertNull(wall.state())
    }

    @Test
    fun `grid prefers wide rows`() {
        assertEquals(1 to 1, PhoneWall.grid(0))
        assertEquals(3 to 1, PhoneWall.grid(3))
        assertEquals(4 to 1, PhoneWall.grid(4))
        assertEquals(3 to 2, PhoneWall.grid(5))
        assertEquals(4 to 2, PhoneWall.grid(8))
        assertEquals(4 to 3, PhoneWall.grid(12))
    }
}
