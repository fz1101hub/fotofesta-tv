package it.fotofesta.tv.model

import java.util.Locale

/** Chi ha mandato più foto: nome, quante e l'ultima arrivata, da mostrare sulla scheda. */
data class TopPhotographer(val name: String, val count: Int, val photo: PhotoItem)

/** Foto con più reazioni dai telefoni e quante ne ha avute. */
data class FavoritePhoto(val photo: PhotoItem, val reactions: Int)

/**
 * Premi di fine festa, calcolati dalle foto degli invitati in mostra: "Obiettivo d'oro" a chi ne ha
 * mandate di più, la prima e l'ultima foto della festa e la "Foto della serata", quella con più
 * reazioni dai telefoni (tra tutte le foto in mostra).
 */
data class PartyAwards(
    val photos: Int,
    /** Invitati diversi che hanno firmato almeno una foto. */
    val guests: Int,
    val topPhotographer: TopPhotographer?,
    val firstPhoto: PhotoItem?,
    /** Ultima foto arrivata; null se è anche la prima. */
    val lastPhoto: PhotoItem?,
    val favorite: FavoritePhoto? = null,
) {
    companion object {
        /** [reactions]: reazioni ricevute da ogni foto, per identificativo. */
        fun from(all: List<PhotoItem>, reactions: Map<String, Int> = emptyMap()): PartyAwards {
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
            // A parità di reazioni vince la foto che compare prima nella raccolta.
            val favorite = all.filter { (reactions[it.id] ?: 0) > 0 }
                .maxWithOrNull(compareBy<PhotoItem> { reactions.getValue(it.id) }.thenByDescending { all.indexOf(it) })
                ?.let { FavoritePhoto(it, reactions.getValue(it.id)) }
            return PartyAwards(guestPhotos.size, byGuest.size, top, first, last, favorite)
        }
    }
}
