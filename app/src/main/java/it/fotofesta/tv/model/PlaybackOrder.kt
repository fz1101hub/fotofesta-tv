package it.fotofesta.tv.model

import kotlin.random.Random

/**
 * Ordine di riproduzione dello slideshow.
 *
 * In modalità casuale usa una permutazione completa: ogni foto compare una volta per giro,
 * "Precedente" torna davvero alla foto vista prima e a fine giro la sequenza viene rimescolata
 * evitando di ripetere subito l'ultima foto mostrata.
 */
class PlaybackOrder(private val random: Random = Random.Default) {
    private var order: List<Int> = emptyList()
    private var shuffle = false
    /** Ultima foto del giro precedente: "Precedente" dalla prima foto di un nuovo giro torna lì. */
    private var lastOfPreviousCycle: Int? = null

    fun reset(size: Int, shuffle: Boolean, current: Int = 0) {
        this.shuffle = shuffle
        lastOfPreviousCycle = null
        order = when {
            size <= 0 -> emptyList()
            !shuffle -> List(size) { it }
            else -> {
                val first = current.coerceIn(0, size - 1)
                listOf(first) + (0 until size).filter { it != first }.shuffled(random)
            }
        }
    }

    /**
     * Aggiorna l'ordine quando la raccolta cambia senza ripartire (foto degli invitati che arrivano o
     * spariscono). [oldToNew] traduce un indice della raccolta precedente in quello nuovo, o null se
     * la foto non c'è più. In ordine casuale le foto restano dove erano nel giro corrente e quelle
     * nuove vengono inserite a caso tra quelle non ancora mostrate, così il giro non ricomincia.
     */
    fun remap(newSize: Int, oldToNew: (Int) -> Int?, current: Int) {
        if (!shuffle || newSize <= 0 || order.isEmpty()) {
            reset(newSize, shuffle, current)
            return
        }
        val list = order.mapNotNull(oldToNew).filter { it in 0 until newSize }.distinct().toMutableList()
        val present = list.toSet()
        val added = (0 until newSize).filterNot(present::contains).toMutableList()
        if (current !in present) {
            added.remove(current)
            list.add(0, current.coerceIn(0, newSize - 1))
        }
        val position = list.indexOf(current).coerceAtLeast(0)
        added.shuffled(random).forEach { index ->
            list.add(random.nextInt(position + 1, list.size + 1), index)
        }
        order = list
        lastOfPreviousCycle = lastOfPreviousCycle?.let(oldToNew)
    }

    fun next(current: Int): Int {
        if (order.size <= 1) return order.firstOrNull() ?: 0
        val position = positionOf(current)
        if (position == order.lastIndex && shuffle) {
            reshuffleAvoiding(current)
            lastOfPreviousCycle = current
            return order.first()
        }
        lastOfPreviousCycle = null
        return order[(position + 1) % order.size]
    }

    fun previous(current: Int): Int {
        if (order.size <= 1) return order.firstOrNull() ?: 0
        val position = positionOf(current)
        lastOfPreviousCycle?.let { previousCycle ->
            if (position == 0) {
                lastOfPreviousCycle = null
                return previousCycle
            }
        }
        return order[(position - 1 + order.size) % order.size]
    }

    /** Foto che verrà mostrata dopo [current] senza modificare l'ordine (usata per il prefetch). */
    fun peekNext(current: Int): Int? {
        if (order.size <= 1) return null
        val position = order.indexOf(current)
        if (position < 0 || (position == order.lastIndex && shuffle)) return null
        return order[(position + 1) % order.size]
    }

    private fun positionOf(current: Int): Int {
        val position = order.indexOf(current)
        if (position >= 0) return position
        reset(order.size, shuffle, current)
        return order.indexOf(current).coerceAtLeast(0)
    }

    private fun reshuffleAvoiding(last: Int) {
        val shuffled = order.shuffled(random)
        order = if (shuffled.first() == last) shuffled.drop(1) + last else shuffled
    }
}
