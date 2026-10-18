package it.fotofesta.tv.guests

import java.util.UUID

/**
 * Gioco con i telefoni: la TV pubblica una domanda con le risposte possibili, gli invitati rispondono
 * dalla pagina della festa e la TV, quando vuole, mostra la risposta giusta e assegna un punto a chi
 * ha indovinato. Vale una risposta per telefono e per domanda: la prima.
 *
 * Thread-safe: il server lo usa dai suoi thread, l'interfaccia dal thread principale.
 */
class PhoneGame {
    /** Domanda come la vedono i telefoni; [answer] è noto solo dopo [reveal]. */
    data class Snapshot(val round: String, val question: String, val choices: List<String>, val open: Boolean, val answer: Int?)

    data class Score(val name: String, val points: Int)

    enum class AnswerResult { ACCEPTED, CLOSED, INVALID }

    private class Player(var name: String, val order: Int, var points: Int = 0)

    private var snapshot: Snapshot? = null
    private var correct = -1
    private val answers = LinkedHashMap<String, Int>()
    private val players = LinkedHashMap<String, Player>()

    /** Chiamato a ogni risposta e a ogni cambio di domanda, da qualunque thread. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Nuova domanda; [correct] è l'indice della risposta giusta, che i telefoni vedono solo dopo [reveal]. */
    fun ask(question: String, choices: List<String>, correct: Int) {
        require(correct in choices.indices)
        synchronized(this) {
            snapshot = Snapshot(UUID.randomUUID().toString().take(12), question, choices, open = true, answer = null)
            this.correct = correct
            answers.clear()
        }
        onChange?.invoke()
    }

    /** Chiude le risposte, mostra quella giusta ai telefoni e dà un punto a chi l'ha scelta. */
    fun reveal() {
        synchronized(this) {
            val current = snapshot ?: return
            if (!current.open) return
            snapshot = current.copy(open = false, answer = correct)
            answers.forEach { (player, choice) -> if (choice == correct) players[player]?.let { it.points++ } }
        }
        onChange?.invoke()
    }

    /** Nessuna domanda in corso (il gioco è stato chiuso); i punti restano. */
    fun stop() {
        synchronized(this) {
            snapshot = null
            answers.clear()
        }
        onChange?.invoke()
    }

    /** Nuova partita: si azzerano i punti di tutti. */
    fun resetScores() {
        synchronized(this) { players.clear() }
        onChange?.invoke()
    }

    @Synchronized
    fun current(): Snapshot? = snapshot

    @Synchronized
    fun answerCount(): Int = answers.size

    @Synchronized
    fun pointsOf(player: String): Int = players[player]?.points ?: 0

    /** Classifica: più punti prima; a parità, chi ha cominciato a giocare prima. */
    @Synchronized
    fun leaderboard(limit: Int = 5): List<Score> =
        players.values.filter { it.points > 0 }
            .sortedWith(compareByDescending<Player> { it.points }.thenBy { it.order })
            .take(limit)
            .map { Score(it.name, it.points) }

    /** Risposta di un telefono: [player] è un identificativo casuale scelto dalla pagina. */
    fun answer(round: String, choice: Int, player: String, name: String?): AnswerResult {
        val result = synchronized(this) {
            val current = snapshot
            when {
                current == null || current.round != round || !current.open -> AnswerResult.CLOSED
                choice !in current.choices.indices || !validPlayer(player) -> AnswerResult.INVALID
                else -> {
                    val known = players[player]
                    val displayName = name?.takeIf { it.isNotBlank() }
                    if (known == null) {
                        players[player] = Player(displayName ?: DEFAULT_NAME, order = players.size)
                    } else if (displayName != null) {
                        known.name = displayName
                    }
                    // Vale la prima risposta: un secondo tocco sullo stesso telefono non cambia nulla.
                    answers.putIfAbsent(player, choice)
                    AnswerResult.ACCEPTED
                }
            }
        }
        if (result == AnswerResult.ACCEPTED) onChange?.invoke()
        return result
    }

    companion object {
        /** Nome di chi non l'ha scritto; la pagina manda già quello tradotto, questo è di riserva. */
        const val DEFAULT_NAME = "?"

        fun validPlayer(player: String): Boolean =
            player.length in 8..64 && player.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
