package it.fotofesta.tv.model

/**
 * Raccolta mostrata: quella scelta più le foto approvate degli invitati, in coda. Con le sole foto
 * demo gli invitati le sostituiscono, così alla festa compaiono solo le foto vere; durante la festa
 * la raccolta può quindi essere vuota, e lo slideshow mostra il QR in attesa della prima foto.
 */
fun combineWithGuests(
    base: List<PhotoItem>,
    baseIsDemo: Boolean,
    guests: List<PhotoItem>,
    partyActive: Boolean,
): List<PhotoItem> = when {
    baseIsDemo && (partyActive || guests.isNotEmpty()) -> guests
    else -> base + guests
}

/**
 * Foto appena arrivate dagli invitati: lo slideshow le mostra subito dopo quella corrente, così chi
 * le ha mandate le vede entro pochi secondi, poi riprende dal punto in cui si era fermato.
 */
class FreshPhotos(private val limit: Int = 30) {
    private val queue = ArrayDeque<String>()
    private var resumeAfter: String? = null
    /** Foto nuove già mostrate da quando si è lasciato l'ordine normale. */
    private val shownInRun = mutableSetOf<String>()

    fun add(ids: Collection<String>) {
        ids.forEach { id -> if (id !in queue) queue.addLast(id) }
        // Con un'ondata di foto, le più vecchie della coda tornano nell'ordine normale.
        while (queue.size > limit) queue.removeFirst()
    }

    fun clear() {
        queue.clear()
        resumeAfter = null
        shownInRun.clear()
    }

    /** Indice della prossima foto nuova in [photos], oppure null se non ce ne sono. */
    fun take(photos: List<PhotoItem>, currentIndex: Int): Int? {
        while (queue.isNotEmpty()) {
            val index = photos.indexOfFirst { it.id == queue.first() }
            queue.removeFirst()
            if (index >= 0 && index != currentIndex) {
                if (resumeAfter == null) resumeAfter = photos.getOrNull(currentIndex)?.id
                shownInRun += photos[index].id
                return index
            }
        }
        return null
    }

    /** Prossima foto nuova senza toglierla dalla coda, per il precaricamento. */
    fun peek(photos: List<PhotoItem>, currentIndex: Int): Int? =
        queue.asSequence().map { id -> photos.indexOfFirst { it.id == id } }.firstOrNull { it >= 0 && it != currentIndex }

    /** Foto da cui riprendere l'ordine normale dopo quelle nuove (per "Precedente"); la dimentica. */
    fun takeResume(photos: List<PhotoItem>): Int? {
        val id = resumeAfter ?: return null
        resumeAfter = null
        shownInRun.clear()
        return photos.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /**
     * Foto successiva nell'ordine normale dopo una serie di foto nuove: riparte da quella mostrata
     * prima e salta le foto nuove appena viste, che altrimenti ricomparirebbero subito. [next] è il
     * passo dell'ordine di riproduzione. Null se non si stava mostrando una serie di foto nuove.
     */
    fun resume(photos: List<PhotoItem>, next: (Int) -> Int): Int? {
        val id = resumeAfter ?: return null
        resumeAfter = null
        val shown = shownInRun.toSet()
        shownInRun.clear()
        val start = photos.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        var index = next(start)
        repeat(photos.size) {
            if (photos.getOrNull(index)?.id !in shown) return index
            index = next(index)
        }
        return index
    }

    fun peekResume(photos: List<PhotoItem>): Int? =
        resumeAfter?.let { id -> photos.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
}
