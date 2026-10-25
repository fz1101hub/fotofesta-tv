package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PrizeDrawTest {
    @Test
    fun `candidates are unique names in order of appearance`() {
        // Vale la prima grafia: "marco" compare prima di "Marco".
        assertEquals(
            listOf("Giulia", "marco", "Sara"),
            PrizeDraw.candidates(listOf("Giulia", null, " "), listOf("marco", "GIULIA "), listOf("Marco", "Sara")),
        )
        assertEquals(listOf("Anna Maria"), PrizeDraw.candidates(listOf("Anna Maria", "anna   maria")))
    }

    @Test
    fun `winners are not drawn again`() {
        val names = listOf("Giulia", "Marco", "Sara")
        val winners = mutableListOf<String>()
        repeat(3) { winners += PrizeDraw.draw(names, winners, Random(7))!! }
        assertEquals(names.toSet(), winners.toSet())
        assertNull(PrizeDraw.draw(names, winners))
        assertNull(PrizeDraw.draw(emptyList(), emptyList()))
        assertEquals("Sara", PrizeDraw.draw(names, listOf("giulia", "MARCO")))
    }
}
