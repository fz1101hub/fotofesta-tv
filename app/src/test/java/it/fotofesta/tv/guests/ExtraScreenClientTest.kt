package it.fotofesta.tv.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtraScreenClientTest {
    @Test
    fun `addresses get the party port`() {
        assertEquals("http://192.168.1.20:8765", ExtraScreenClient.base("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000", ExtraScreenClient.base(" http://192.168.1.20:9000/ "))
        assertEquals("http://192.168.1.20:8765/?k=abc", ExtraScreenClient.Party("http://192.168.1.20:8765", "abc").guestUrl)
    }

    @Test
    fun `wall state is read with names and without broken items`() {
        val wall = ExtraScreenClient.parseWall(
            """{"title":"Buon compleanno!","count":3,"photos":[{"id":"a","sender":"Giulia"},{"id":"b","sender":null},{"sender":"x"}],
               "messages":[{"text":"Auguri!","name":"Marco"},{"text":"Evviva","name":null},{"text":" "}],"announcement":null}""",
        )
        assertEquals("Buon compleanno!", wall.title)
        assertEquals(3, wall.count)
        assertEquals(listOf(ExtraScreenClient.WallPhoto("a", "Giulia"), ExtraScreenClient.WallPhoto("b", null)), wall.photos)
        assertEquals(listOf("Marco: Auguri!", "Evviva"), wall.messages)
        assertNull(wall.announcement)
        assertEquals("Torta!", ExtraScreenClient.parseWall("""{"photos":[],"announcement":"Torta!"}""").announcement)
    }
}
