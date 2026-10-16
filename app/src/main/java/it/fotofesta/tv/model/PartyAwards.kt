package it.fotofesta.tv.model

import java.util.Locale

/** Chi ha mandato più foto: nome, quante e l'ultima arrivata, da mostrare sulla scheda. */
data class TopPhotographer(val name: String, val count: Int, val photo: PhotoItem)

/**
 * Premi di fine festa, calcolati dalle foto degli invitati in mostra: "Obiettivo d'oro" a chi ne ha
 * mandate di più, la prima e l'ultima foto della festa.
 */
data class PartyAwards(
    val photos: Int,
    /** Invitati diversi che hanno firmato almeno una foto. */
    val guests: Int,
    val topPhotographer: TopPhotographer?,
    val firstPhoto: PhotoItem?,
    /** Ultima foto arrivata; null se è anche la prima. */
    val lastPhoto: PhotoItem?,
) {
    companion object {
        fun from(all: List<PhotoItem>): PartyAwards {
            val guestPhotos = all.filter { it.source == SourceType.GUESTS }
                .sortedWith(compareBy<PhotoItem> { it.receivedAtMillis ?: Long.MAX_VALUE }.thenBy { it.id })
            val named = guestPhotos.filter { !it.sender.isNullOrBlank() }
            // A parità di foto vince chi ha cominciato prima: l'ordine è già quello di arrivo.
            val byGuest = named.groupBy { it.sender.orEmpty().trim().lowercase(Locale.ROOT) }
            val top = byGuest.values.maxWithOrNull(
                compareBy<List<PhotoItem>> { it.size }.thenByDescending { guestPhotos.indexOf(it.first()) },
            )?.let { photos -> TopPhotographer(photos.first().sender.orEmpty().trim(), photos.size, photos.last()) }
            val first = guestPhotos.firstOrNull()
            val last = guestPhotos.lastOrNull()?.takeIf { it != first }
            return PartyAwards(guestPhotos.size, byGuest.size, top, first, last)
        }
    }
}
