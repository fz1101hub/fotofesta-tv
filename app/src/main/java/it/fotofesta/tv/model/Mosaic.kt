package it.fotofesta.tv.model

/**
 * Foto accanto a quella grande nel mosaico: prima le ultime mostrate (dalla più recente), poi, se
 * non bastano, quelle che seguono nella raccolta. Mai la foto grande, mai due volte la stessa.
 */
fun mosaicSidePhotos(photos: List<PhotoItem>, currentIndex: Int, recentIds: List<String>, count: Int = MOSAIC_SIDE_PHOTOS): List<PhotoItem> {
    val current = photos.getOrNull(currentIndex) ?: return emptyList()
    val byId = photos.associateBy { it.id }
    val chosen = LinkedHashMap<String, PhotoItem>()
    recentIds.asSequence()
        .filter { it != current.id }
        .mapNotNull { byId[it] }
        .forEach { if (chosen.size < count) chosen.putIfAbsent(it.id, it) }
    var offset = 1
    while (chosen.size < count && offset < photos.size) {
        val next = photos[(currentIndex + offset) % photos.size]
        if (next.id != current.id) chosen.putIfAbsent(next.id, next)
        offset++
    }
    return chosen.values.toList()
}

/** Foto piccole accanto a quella grande. */
const val MOSAIC_SIDE_PHOTOS = 2
