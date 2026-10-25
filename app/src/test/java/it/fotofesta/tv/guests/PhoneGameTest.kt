package it.fotofesta.tv.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGameTest {
    private val giulia = "player-giulia"
    private val marco = "player-marco"
    private val sara = "player-sara1"

    @Test
    fun `phones see the question but not the answer until it is revealed`() {
        val game = PhoneGame()
        assertNull(game.current())
        game.ask("Chi ha mandato questa foto?", listOf("Giulia", "Marco"), correct = 1)
        val asked = game.current()!!
        assertTrue(asked.open)
        assertNull(asked.answer)
        game.reveal()
        val revealed = game.current()!!
        assertEquals(asked.round, revealed.round)
        assertEquals(false, revealed.open)
        assertEquals(1, revealed.answer)
    }

    @Test
    fun `only the first answer of each phone counts and right answers score`() {
        val game = PhoneGame()
        game.ask("?", listOf("A", "B", "C"), correct = 2)
        val round = game.current()!!.round
        assertEquals(PhoneGame.AnswerResult.ACCEPTED, game.answer(round, 2, giulia, "Giulia"))
        assertEquals(PhoneGame.AnswerResult.ACCEPTED, game.answer(round, 0, giulia, "Giulia"))
        assertEquals(PhoneGame.AnswerResult.ACCEPTED, game.answer(round, 1, marco, "Marco"))
        assertEquals(2, game.answerCount())
        game.reveal()
        assertEquals(1, game.pointsOf(giulia))
        assertEquals(0, game.pointsOf(marco))
        assertEquals(listOf(PhoneGame.Score("Giulia", 1)), game.leaderboard())
    }

    @Test
    fun `answers to a closed or old question are refused`() {
        val game = PhoneGame()
        assertEquals(PhoneGame.AnswerResult.CLOSED, game.answer("nessuna", 0, giulia, null))
        game.ask("?", listOf("A", "B"), correct = 0)
        val first = game.current()!!.round
        game.reveal()
        assertEquals(PhoneGame.AnswerResult.CLOSED, game.answer(first, 0, giulia, null))
        game.ask("?", listOf("A", "B"), correct = 0)
        assertNotEquals(first, game.current()!!.round)
        assertEquals(PhoneGame.AnswerResult.CLOSED, game.answer(first, 0, giulia, null))
        game.stop()
        assertNull(game.current())
    }

    @Test
    fun `invalid choices and player ids are refused`() {
        val game = PhoneGame()
        game.ask("?", listOf("A", "B"), correct = 0)
        val round = game.current()!!.round
        assertEquals(PhoneGame.AnswerResult.INVALID, game.answer(round, 5, giulia, null))
        assertEquals(PhoneGame.AnswerResult.INVALID, game.answer(round, -1, giulia, null))
        assertEquals(PhoneGame.AnswerResult.INVALID, game.answer(round, 0, "corto", null))
        assertEquals(PhoneGame.AnswerResult.INVALID, game.answer(round, 0, "id con spazi!", null))
        assertEquals(0, game.answerCount())
    }

    @Test
    fun `leaderboard orders by points then by who started first`() {
        val game = PhoneGame()
        repeat(3) { index ->
            game.ask("?", listOf("A", "B"), correct = 0)
            val round = game.current()!!.round
            game.answer(round, if (index == 2) 1 else 0, sara, "Sara")
            game.answer(round, 0, marco, null)
            game.answer(round, 0, giulia, "Giulia")
            game.reveal()
        }
        // Marco non ha scritto il nome: resta quello di riserva.
        assertEquals(
            listOf(PhoneGame.Score(PhoneGame.DEFAULT_NAME, 3), PhoneGame.Score("Giulia", 3), PhoneGame.Score("Sara", 2)),
            game.leaderboard(),
        )
        game.resetScores()
        assertEquals(emptyList<PhoneGame.Score>(), game.leaderboard())
    }

    @Test
    fun `polls show the votes at the end and give no points`() {
        val game = PhoneGame()
        game.askPoll("Chi piangerà per primo?", listOf("Giulia", "Marco", "Sara"))
        val round = game.current()!!.round
        assertTrue(game.current()!!.poll)
        game.answer(round, 1, giulia, "Giulia")
        game.answer(round, 1, marco, "Marco")
        game.answer(round, 2, sara, "Sara")
        assertEquals(listOf(0, 2, 1), game.tally())
        // Mentre il sondaggio è aperto i telefoni non vedono i voti.
        assertNull(game.current()!!.tally)
        game.reveal()
        val closed = game.current()!!
        assertEquals(listOf(0, 2, 1), closed.tally)
        assertNull(closed.answer)
        assertEquals(0, game.pointsOf(marco))
        assertEquals(listOf("Giulia", "Marco", "Sara"), game.names())
    }

    @Test
    fun `changes are reported`() {
        val game = PhoneGame()
        var changes = 0
        game.onChange = { changes++ }
        game.ask("?", listOf("A", "B"), correct = 0)
        game.answer(game.current()!!.round, 1, giulia, null)
        game.reveal()
        game.stop()
        assertEquals(4, changes)
    }
}
