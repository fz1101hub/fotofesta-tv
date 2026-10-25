package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class PartyGamesTest {
    @Test
    fun `who is missing ignores case, accents and surnames`() {
        val roll = PartyGames.roll(listOf("Niccolò", "Marco Rossi", "Anna", "Sara"), listOf("niccolo", "Marco", "ANNA!"))
        assertEquals(listOf("Niccolò", "Marco Rossi", "Anna"), roll.present)
        assertEquals(listOf("Sara"), roll.missing)
    }

    @Test
    fun `quiz lines need a question and at least two answers`() {
        val quiz = PartyGames.parseQuiz("Dove si sono conosciuti? | A Roma | A Milano | A Napoli\nsenza risposte\nColore preferito? | Blu | Rosso")
        assertEquals(2, quiz.size)
        assertEquals("A Roma", quiz[0].right)
        assertEquals(listOf("Rosso"), quiz[1].wrong)
        val (choices, right) = PartyGames.shuffle(quiz[0], Random(3))
        assertEquals("A Roma", choices[right])
        assertEquals(3, choices.size)
    }

    @Test
    fun `name choices include the right one and up to three others`() {
        val (choices, right) = PartyGames.nameChoices("Giulia", listOf("Anna", "giulia", "Luca", "Marco", "Sara"), Random(1))
        assertEquals(4, choices.size)
        assertEquals("Giulia", choices[right])
        assertEquals(1, choices.count { it.equals("giulia", ignoreCase = true) })
    }

    @Test
    fun `song titles come from file names`() {
        assertEquals("Gloria", PartyGames.songTitle("01 - Gloria (Remastered 2011).mp3"))
        assertEquals("Volare", PartyGames.songTitle("/musica/Volare.flac"))
        assertEquals("Hey Jude", PartyGames.songTitle("Hey_Jude.m4a"))
    }

    @Test
    fun `brighter photos play higher notes`() {
        val notes = PartyGames.notes(listOf(0xFF101010.toInt(), 0xFFF0F0F0.toInt()))
        assertTrue(notes[1].frequency > notes[0].frequency)
        assertEquals(220.0, notes[0].frequency, 0.01)
    }

    @Test
    fun `differences are spread out and repeatable`() {
        val first = PartyGames.differences(42)
        assertEquals(5, first.size)
        assertEquals(first, PartyGames.differences(42))
        for (a in first) for (b in first) if (a !== b) assertTrue((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) >= 0.04f)
    }

    @Test
    fun `artwork covers the whole canvas`() {
        val blocks = PartyGames.artwork(listOf(1, 2, 3), seed = 7)
        val area = blocks.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }
        assertEquals(1.0, area, 0.0001)
    }

    @Test
    fun `on this day`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0) }.timeInMillis
        val past = Calendar.getInstance().apply { set(2019, Calendar.OCTOBER, 25, 20, 0) }.timeInMillis
        val other = Calendar.getInstance().apply { set(2019, Calendar.OCTOBER, 24, 20, 0) }.timeInMillis
        assertTrue(PartyGames.sameDayPastYear(past, now))
        assertFalse(PartyGames.sameDayPastYear(other, now))
        assertFalse(PartyGames.sameDayPastYear(now, now))
        assertEquals(7, PartyGames.yearsAgo(past, now))
    }
}
