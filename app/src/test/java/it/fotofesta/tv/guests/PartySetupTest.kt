package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartySetupTest {
    @get:Rule val folder = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test
    fun `schedule lines are parsed and sorted`() {
        val slots = PartySetup.parseSchedule("23:00 Si balla\n20.00 - Aperitivo\nnota senza ora\n25:00 Impossibile\n9:30 Colazione")
        assertEquals(listOf("09:30", "20:00", "23:00"), slots.map { it.time })
        assertEquals("Aperitivo", slots[1].title)
    }

    @Test
    fun `brand, logo, sponsors and guests survive a restart`() {
        val dir = folder.newFolder()
        val setup = PartySetup(dir)
        setup.setName("  Festa   di Giulia ")
        setup.setAccent("#FF4F9A")
        setup.setAccent("rosso")
        assertTrue(setup.setLogo(png))
        assertFalse(setup.setLogo("non è un'immagine".toByteArray()))
        val sponsor = setup.addSponsor(png)!!
        setup.setGuestList("Anna, Luca\nMarco\nAnna")
        setup.setSchedule("21:30 Torta")

        val again = PartySetup(dir).state.value
        assertEquals("Festa di Giulia", again.brand.name)
        assertNull(again.brand.accent)
        assertTrue(again.brand.hasLogo)
        assertEquals(listOf(sponsor), again.sponsors)
        assertEquals(listOf("Anna", "Luca", "Marco"), again.guestList)
        assertEquals("21:30", again.schedule.single().time)
    }

    @Test
    fun `the public json hides the guest list`() {
        val setup = PartySetup(folder.newFolder())
        setup.setGuestList("Anna")
        assertFalse(JSONObject(setup.publicJson()).has("guests"))
        assertEquals("Anna", JSONObject(setup.json()).getJSONArray("guests").getString(0))
    }

    @Test
    fun `export and import carry everything, images included`() {
        val first = PartySetup(folder.newFolder())
        first.setName("Matrimonio")
        first.setAccent("#3da9fc")
        first.setLogo(png)
        first.addSponsor(png)
        first.setSchedule("18:00 Cerimonia")
        val second = PartySetup(folder.newFolder())
        assertTrue(second.importJson(first.exportJson()))
        val state = second.state.value
        assertEquals("Matrimonio", state.brand.name)
        assertEquals("#3da9fc", state.brand.accent)
        assertTrue(second.logo()!!.contentEquals(png))
        assertEquals(1, state.sponsors.size)
        assertFalse(second.importJson("{}"))
    }

    @Test
    fun `sponsors have a limit`() {
        val setup = PartySetup(folder.newFolder())
        repeat(PartySetup.MAX_SPONSORS) { assertTrue(setup.addSponsor(png) != null) }
        assertNull(setup.addSponsor(png))
        setup.removeSponsor(setup.state.value.sponsors.first())
        assertTrue(setup.addSponsor(png) != null)
    }

    @Test
    fun `quiz and childhood photos survive a restart and stay private`() {
        val dir = folder.newFolder()
        val setup = PartySetup(dir)
        setup.setQuiz("Dove? | Roma | Parigi")
        val id = setup.addKid("Giulia", png)!!
        assertNull(setup.addKid(null, png))
        assertNull(setup.addKid("Luca", byteArrayOf(1, 2, 3)))
        val again = PartySetup(dir)
        assertEquals("Dove? | Roma | Parigi", again.state.value.quiz)
        assertEquals(listOf("Giulia"), again.state.value.kids.map { it.name })
        assertTrue(again.kidPhoto(id)!!.contentEquals(png))
        assertFalse(again.publicJson().contains("Giulia"))
        assertFalse(again.publicJson().contains("Parigi"))
    }

    @Test
    fun `only known childhood photos can be removed`() {
        val dir = folder.newFolder()
        val setup = PartySetup(dir)
        setup.setLogo(png)
        val id = setup.addKid("Giulia", png)!!
        setup.removeKid("../logo")
        assertTrue(setup.logo() != null)
        assertNull(setup.kidPhoto("../logo"))
        setup.removeKid(id)
        assertTrue(setup.state.value.kids.isEmpty())
        assertNull(setup.kidPhoto(id))
    }
}
