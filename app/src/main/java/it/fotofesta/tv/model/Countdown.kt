package it.fotofesta.tv.model

import androidx.annotation.StringRes
import it.fotofesta.tv.R

/** Momento del conto alla rovescia: nome nell'etichetta e messaggio quando arriva. */
enum class CountdownEvent(@StringRes val labelRes: Int, @StringRes val messageRes: Int) {
    MIDNIGHT(R.string.countdown_midnight, R.string.countdown_midnight_message),
    CAKE(R.string.countdown_cake, R.string.countdown_cake_message),
    TOAST(R.string.countdown_toast, R.string.countdown_toast_message),
    SURPRISE(R.string.countdown_surprise, R.string.countdown_surprise_message),
}

/** Cosa mostra il conto alla rovescia in un dato istante. */
sealed interface CountdownPhase {
    /** Manca più di un'ora: non si mostra nulla. */
    data object Hidden : CountdownPhase

    /** Nell'ultima ora: etichetta piccola con il tempo che manca. */
    data class Showing(val seconds: Long) : CountdownPhase

    /** Negli ultimi secondi: numeri grandi al centro. */
    data class Final(val seconds: Long) : CountdownPhase

    /** Appena arrivato il momento: il messaggio di festa. */
    data object Celebrate : CountdownPhase
}

object Countdown {
    const val SHOW_BEFORE_SECONDS = 3_600L
    const val FINAL_SECONDS = 10L
    const val CELEBRATE_SECONDS = 20L

    /** Orari tra cui scegliere, in minuti dalla mezzanotte: ogni quarto d'ora. */
    val TIME_OPTIONS: List<Int> = List(24 * 4) { it * 15 }

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Fase all'istante [nowMillis] per un momento che torna ogni giorno a [minutesOfDay] (ora locale,
     * con [utcOffsetMillis] di differenza da UTC).
     */
    fun phase(nowMillis: Long, minutesOfDay: Int, utcOffsetMillis: Int): CountdownPhase {
        val sinceMidnight = Math.floorMod(nowMillis + utcOffsetMillis, DAY_MILLIS)
        val target = minutesOfDay * 60_000L
        val untilTarget = Math.floorMod(target - sinceMidnight, DAY_MILLIS)
        val sinceTarget = Math.floorMod(sinceMidnight - target, DAY_MILLIS)
        val seconds = (untilTarget + 999) / 1_000
        return when {
            sinceTarget < CELEBRATE_SECONDS * 1_000 -> CountdownPhase.Celebrate
            seconds <= FINAL_SECONDS -> CountdownPhase.Final(seconds)
            seconds <= SHOW_BEFORE_SECONDS -> CountdownPhase.Showing(seconds)
            else -> CountdownPhase.Hidden
        }
    }

    /** "12:34" o "1:02:03": tempo che manca. */
    fun format(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val rest = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
    }
}
