package it.fotofesta.tv.model

import java.util.Locale
import kotlin.random.Random

/**
 * Estrazione a premi tra gli invitati che hanno partecipato con un nome: foto firmate, giochi,
 * reazioni e dediche. Lo stesso nome scritto in modi diversi (maiuscole, spazi) conta una volta, e
 * chi ha già vinto non viene estratto di nuovo finché si ricomincia.
 */
object PrizeDraw {
    /** Nomi diversi, nell'ordine in cui compaiono la prima volta, scritti come la prima volta. */
    fun candidates(vararg groups: List<String?>): List<String> {
        val seen = HashSet<String>()
        return groups.asList().flatten().mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .filter { seen.add(key(it)) }
    }

    /** Un vincitore tra [candidates] escluso chi è in [winners]; null se non resta nessuno. */
    fun draw(candidates: List<String>, winners: Collection<String>, random: Random = Random.Default): String? {
        val excluded = winners.mapTo(HashSet(), ::key)
        val remaining = candidates.filterNot { key(it) in excluded }
        return if (remaining.isEmpty()) null else remaining[random.nextInt(remaining.size)]
    }

    private fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
