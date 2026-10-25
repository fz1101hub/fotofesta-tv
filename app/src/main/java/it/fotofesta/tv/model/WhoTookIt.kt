package it.fotofesta.tv.model

import java.util.Locale
import kotlin.random.Random

/** Una domanda di "Chi l'ha scattata?": la foto, i nomi tra cui scegliere e quello giusto. */
data class WhoTookItRound(val photo: PhotoItem, val choices: List<String>, val answer: String)

/**
 * "Chi l'ha scattata?": si indovina quale invitato ha mandato la foto. Si gioca con le foto della
 * festa firmate con un nome, e servono almeno due nomi diversi.
 */
object WhoTookIt {
    /** Nomi proposti a ogni domanda, compreso quello giusto. */
    const val MAX_CHOICES = 4

    /** Foto degli invitati firmate con un nome: le sole che hanno una risposta. */
    fun photos(all: List<PhotoItem>): List<PhotoItem> =
        all.filter { it.source == SourceType.GUESTS && !it.sender.isNullOrBlank() }

    /** Nomi di chi ha mandato le foto, senza doppioni: "Marco" e "marco " sono la stessa persona. */
    fun senders(all: List<PhotoItem>): List<String> =
        photos(all).map { it.sender.orEmpty().trim() }.distinctBy(::key)

    fun canPlay(all: List<PhotoItem>): Boolean = senders(all).size >= 2

    /** Nuova domanda, se possibile con una foto diversa dalla precedente. */
    fun round(all: List<PhotoItem>, previousPhotoId: String?, random: Random = Random.Default): WhoTookItRound? {
        val senders = senders(all)
        if (senders.size < 2) return null
        val candidates = photos(all)
        val photo = candidates.filter { it.id != previousPhotoId }.ifEmpty { candidates }.random(random)
        val answerKey = key(photo.sender.orEmpty())
        val answer = senders.first { key(it) == answerKey }
        val others = senders.filter { key(it) != answerKey }.shuffled(random).take(MAX_CHOICES - 1)
        return WhoTookItRound(photo, (others + answer).shuffled(random), answer)
    }

    fun isAnswer(round: WhoTookItRound, choice: String): Boolean = key(choice) == key(round.answer)

    private fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
}
