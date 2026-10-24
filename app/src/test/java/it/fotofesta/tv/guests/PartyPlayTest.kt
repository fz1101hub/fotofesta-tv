package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PartyPlayTest {
    private var now = 20_000_000L
    private val hub = PartyHub { now }
    private val play = PartyPlay(hub, { now }, Random(7))
    private val giulia = "player-giulia"
    private val marco = "player-marco"
    private val sara = "player-sara"

    private fun join() {
        hub.join(giulia, "Giulia")
        hub.join(marco, "Marco")
        hub.join(sara, "Sara")
    }

    private fun send(player: String, vararg fields: Pair<String, String>): Pair<Int, String> {
        now += 3_000
        return play.handle(mapOf("type" to "play", "player" to player, "round" to (play.current()?.round ?: "")) + fields)
    }

    private fun phone(player: String) = JSONObject(play.phoneJson(player))

    @Test
    fun `games that need a guest wait for names`() {
        assertNull(play.start(PartyPlay.Kind.DRAW_GUESS, "Disegna", listOf("gatto")))
        assertNull(play.start(PartyPlay.Kind.MOLE, "Talpa", listOf("ananas")))
        hub.join(giulia, "Giulia")
        assertNull(play.start(PartyPlay.Kind.COMPLIMENTS, "Complimenti"))
        assertNotNull(play.start(PartyPlay.Kind.DRAW_GUESS, "Disegna", listOf("gatto")))
    }

    @Test
    fun `draw and guess`() {
        join()
        val state = play.start(PartyPlay.Kind.DRAW_GUESS, "Indovina il disegno", listOf("gatto"))!!
        val drawer = state.special!!.player
        val guesser = listOf(giulia, marco, sara).first { it != drawer }
        // Solo chi disegna vede la parola.
        assertEquals("gatto", phone(drawer).getJSONObject("game").getString("secret"))
        assertEquals("draw", phone(drawer).getJSONObject("game").getString("input"))
        assertFalse(phone(guesser).getJSONObject("game").has("secret"))
        assertEquals("text", phone(guesser).getJSONObject("game").getString("input"))

        assertEquals(200, send(drawer, "drawing" to "0,100,100,500,500;2,10,10,20,20").first)
        assertEquals(400, send(drawer, "drawing" to "0,100,1200").first)
        assertEquals(1, play.current()!!.entries.count { it.drawing != null })
        assertEquals("""{"status":"wrong"}""", send(guesser, "text" to "cane").second)
        assertEquals("""{"status":"correct"}""", send(guesser, "text" to "È un GATTO!").second)
        val done = play.current()!!
        assertTrue(done.revealed)
        assertEquals(guesser, done.winner!!.player)
        assertEquals(3, play.coinsOf(guesser))
        assertEquals(2, play.coinsOf(drawer))
        assertEquals(409, send(guesser, "text" to "gatto").first)
        assertEquals("gatto", phone(guesser).getJSONObject("game").getString("answer"))
    }

    @Test
    fun `guesses ignore case, accents and punctuation`() {
        assertTrue(PartyPlay.guessMatches("  il CAFFÈ!! ", "caffe"))
        assertTrue(PartyPlay.guessMatches("torta", "Torta"))
        assertFalse(PartyPlay.guessMatches("tortellini", "torta"))
        assertFalse(PartyPlay.guessMatches("qualcosa", ""))
    }

    @Test
    fun `drawings are checked and parsed`() {
        assertTrue(PartyPlay.validDrawing("1,0,0,1000,1000"))
        assertFalse(PartyPlay.validDrawing(""))
        assertFalse(PartyPlay.validDrawing("9,0,0"))
        assertFalse(PartyPlay.validDrawing("1,0"))
        assertFalse(PartyPlay.validDrawing("1,0,0,<b>"))
        assertFalse(PartyPlay.validDrawing("1," + "5,".repeat(11_000) + "5"))
        val strokes = PartyPlay.strokes("3,0,500,1000,250")
        assertEquals(3, strokes.single().first)
        assertEquals(0.5f, strokes.single().second[1], 0.001f)
    }

    @Test
    fun `the story shows only the last sentence and nobody writes twice in a row`() {
        join()
        play.start(PartyPlay.Kind.STORY, "Romanzo", listOf("C'era una volta una festa."))
        assertEquals("C'era una volta una festa.", phone(giulia).getJSONObject("game").getString("previous"))
        assertEquals(200, send(giulia, "text" to "Arrivò un drago.").first)
        assertEquals(409, send(giulia, "text" to "E ballò.").first)
        assertEquals("Arrivò un drago.", phone(marco).getJSONObject("game").getString("previous"))
        assertEquals(200, send(marco, "text" to "Il drago aveva fame.").first)
        play.reveal()
        assertEquals(listOf("Arrivò un drago.", "Il drago aveva fame."), play.current()!!.entries.map { it.text })
    }

    @Test
    fun `whisper passes the turn one phone at a time and alternates drawing and text`() {
        join()
        play.start(PartyPlay.Kind.WHISPER, "Telefono senza fili", listOf("Un elefante in bicicletta"))
        val first = phone(giulia).getJSONObject("game")
        assertTrue(first.getBoolean("turn"))
        assertEquals("draw", first.getString("input"))
        assertEquals("Un elefante in bicicletta", first.getString("previous"))
        assertFalse(phone(marco).getJSONObject("game").getBoolean("turn"))
        assertEquals(200, send(giulia, "drawing" to "0,1,1,2,2").first)
        val second = phone(marco).getJSONObject("game")
        assertTrue(second.getBoolean("turn"))
        assertEquals("text", second.getString("input"))
        assertEquals("0,1,1,2,2", second.getString("previousDrawing"))
        assertEquals(409, send(sara, "text" to "Non è il mio turno").first)
        // Chi non risponde in tempo perde il turno.
        now += PartyPlay.WHISPER_TURN_MS + 1
        assertTrue(phone(sara).getJSONObject("game").getBoolean("turn"))
    }

    @Test
    fun `secret compliments become anonymous messages`() {
        join()
        play.start(PartyPlay.Kind.COMPLIMENTS, "Complimenti segreti")
        val choices = phone(giulia).getJSONObject("game").getJSONArray("choices")
        assertEquals(listOf("Marco", "Sara"), (0 until choices.length()).map(choices::getString))
        assertEquals(400, send(giulia, "target" to "Giulia", "text" to "Sono bravissima").first)
        assertEquals(200, send(giulia, "target" to "Marco", "text" to "Balli benissimo").first)
        val message = hub.messages().single()
        assertEquals("💌 Marco: Balli benissimo", message.text)
        assertNull(message.name)
        assertEquals(1, play.coinsOf(giulia))
    }

    @Test
    fun `missions tag the photos that complete them`() {
        join()
        play.start(PartyPlay.Kind.MISSIONS, "Missioni", listOf("Una foto con gli occhiali", "Un brindisi"))
        val missionGiulia = phone(giulia).getJSONObject("game").getString("secret")
        val missionMarco = phone(marco).getJSONObject("game").getString("secret")
        assertTrue(missionGiulia != missionMarco)
        assertEquals(missionGiulia, play.photoArrived(giulia, "foto-1")!!.text)
        assertEquals(missionGiulia, play.tag("foto-1")!!.text)
        assertNull(play.photoArrived(null, "foto-2"))
        assertEquals(2, play.coinsOf(giulia))
        play.photoArrived(giulia, "foto-3")
        assertEquals(2, play.coinsOf(giulia))
        assertEquals(listOf("foto-1", "foto-3"), play.taggedPhotos(play.current()!!.round))
    }

    @Test
    fun `paparazzi reward the first three photographers but not the star`() {
        join()
        hub.join("player-luca", "Luca")
        hub.join("player-anna", "Anna")
        val star = play.start(PartyPlay.Kind.PAPARAZZI, "Paparazzi")!!.special!!
        val others = listOf(giulia, marco, sara, "player-luca", "player-anna").filter { it != star.player }
        assertNull(play.photoArrived(star.player, "selfie"))
        others.forEachIndexed { index, player -> play.photoArrived(player, "foto-$index") }
        assertEquals(listOf(3, 2, 1, 0), others.map(play::coinsOf))
        assertEquals(star.name, play.tag("foto-0")!!.text)
    }

    @Test
    fun `the same moment counts only photos in the minute after the signal`() {
        join()
        val state = play.start(PartyPlay.Kind.SAME_MOMENT, "Lo stesso istante")!!
        assertEquals(state.momentAtMillis, phone(giulia).getJSONObject("game").getLong("moment"))
        assertNull(play.photoArrived(giulia, "presto"))
        now = state.momentAtMillis + 5_000
        assertNotNull(play.photoArrived(giulia, "giusto"))
        now = state.momentAtMillis + PartyPlay.MOMENT_WINDOW_MS + 1
        assertNull(play.photoArrived(marco, "tardi"))
    }

    @Test
    fun `the mole wins unless most guests vote for them`() {
        join()
        val mole = play.start(PartyPlay.Kind.MOLE, "Chi è la talpa?", listOf("Fai dire «ananas» a tre persone"))!!.special!!
        assertEquals("Fai dire «ananas» a tre persone", phone(mole.player).getJSONObject("game").getString("secret"))
        val others = listOf(giulia, marco, sara).filter { it != mole.player }
        assertFalse(phone(others[0]).getJSONObject("game").has("secret"))
        assertEquals(409, send(others[0], "command" to "vote", "target" to mole.name).first)
        play.startVoting()
        assertEquals("choice", phone(others[0]).getJSONObject("game").getString("input"))
        others.forEach { assertEquals(200, send(it, "command" to "vote", "target" to mole.name).first) }
        play.reveal()
        assertTrue(play.moleCaught())
        assertTrue(phone(others[0]).getJSONObject("game").getBoolean("caught"))
        others.forEach { assertEquals(2, play.coinsOf(it)) }
        assertEquals(0, play.coinsOf(mole.player))
    }

    @Test
    fun `the time capsule stays hidden until it is opened`() {
        join()
        play.start(PartyPlay.Kind.CAPSULE, "Capsula del tempo")
        assertEquals(200, send(giulia, "text" to "Imparerò a ballare").first)
        assertFalse(phone(marco).getJSONObject("game").has("entries"))
        play.reveal()
        assertEquals("Giulia: Imparerò a ballare", phone(marco).getJSONObject("game").getJSONArray("entries").getString(0))
    }

    @Test
    fun `the QR treasure goes to the first three phones`() {
        join()
        hub.join("player-luca", "Luca")
        val token = play.startTreasure()
        assertEquals(400, send(giulia, "command" to "treasure", "token" to "sbagliato").first)
        assertEquals(200, send(giulia, "command" to "treasure", "token" to token).first)
        assertEquals(409, send(giulia, "command" to "treasure", "token" to token).first)
        assertEquals(200, send(marco, "command" to "treasure", "token" to token).first)
        assertEquals(200, send(sara, "command" to "treasure", "token" to token).first)
        assertNull(play.treasure())
        assertEquals(410, send("player-luca", "command" to "treasure", "token" to token).first)
        assertEquals(listOf(3, 2, 1), listOf(giulia, marco, sara).map(play::coinsOf))
        assertEquals(listOf("Giulia", "Marco", "Sara"), play.treasureFinders())
    }

    @Test
    fun `coins make a leaderboard and lights reach every phone`() {
        join()
        play.award(marco, 5)
        play.award(giulia, 5)
        play.award(sara, 1)
        assertEquals(listOf("Marco", "Giulia", "Sara"), play.leaderboard().map { it.name })
        assertEquals(5, phone(marco).getInt("coins"))
        play.setLights(PartyPlay.Lights.WAVE)
        assertEquals("wave", phone(sara).getJSONObject("lights").getString("pattern"))
        play.clear()
        assertTrue(play.leaderboard().isEmpty())
        assertFalse(phone(sara).has("lights"))
    }
}
