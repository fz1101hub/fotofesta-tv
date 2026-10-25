package it.fotofesta.tv.model

import java.util.Calendar
import java.util.TimeZone

/**
 * Logica degli spettacoli sullo schermo: la festa in numeri, il fotomosaico, i titoli di coda e i
 * colori del codice a barre. Solo calcoli, senza interfaccia: la stessa logica è in
 * `PartyShows.swift` per Apple TV e Mac.
 */
object PartyShows {
    /** La festa in numeri. [hourly] conta le foto degli invitati arrivate in ogni ora del giorno. */
    data class Stats(
        val photos: Int,
        val guestPhotos: Int,
        val photographers: Int,
        val messages: Int,
        val reactions: Int,
        /** Indice della reazione più usata (cuore, risata...), se ce n'è almeno una. */
        val topReaction: Int?,
        val busiestHour: Int?,
        val hourly: List<Int>,
        val coins: Int,
        /** Foto con più reazioni. */
        val favorite: String?,
    )

    fun stats(
        photos: List<PhotoItem>,
        messages: Int,
        reactionsByPhoto: Map<String, Int>,
        reactionsByKind: List<Int>,
        coins: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): Stats {
        val guests = photos.filter { it.source == SourceType.GUESTS }
        val hourly = IntArray(24)
        val calendar = Calendar.getInstance(zone)
        guests.forEach { photo ->
            val at = photo.receivedAtMillis ?: return@forEach
            calendar.timeInMillis = at
            hourly[calendar.get(Calendar.HOUR_OF_DAY)]++
        }
        val busiest = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 }
        val top = reactionsByKind.indices.maxByOrNull { reactionsByKind[it] }?.takeIf { reactionsByKind[it] > 0 }
        val ids = photos.map { it.id }.toSet()
        val favorite = reactionsByPhoto.filter { it.key in ids && it.value > 0 }.maxByOrNull { it.value }?.key
        return Stats(
            photos = photos.size,
            guestPhotos = guests.size,
            photographers = guests.mapNotNull { it.sender?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }.toSet().size,
            messages = messages,
            reactions = reactionsByKind.sum(),
            topReaction = top,
            busiestHour = busiest,
            hourly = hourly.toList(),
            coins = coins,
            favorite = favorite,
        )
    }

    /**
     * Fotomosaico: per ogni casella (colore medio della foto grande in quel punto) la foto con il
     * colore medio più vicino. Una foto già usata molte volte costa un po' di più, così il mosaico non
     * è fatto di una foto sola. Restituisce l'indice della foto scelta per ogni casella.
     */
    fun mosaic(cells: List<Int>, tiles: List<Int>, reusePenalty: Int = 900): List<Int> {
        if (tiles.isEmpty()) return emptyList()
        val uses = IntArray(tiles.size)
        return cells.map { cell ->
            var best = 0
            var bestCost = Long.MAX_VALUE
            tiles.forEachIndexed { index, tile ->
                val cost = distance(cell, tile) + uses[index].toLong() * reusePenalty
                if (cost < bestCost) {
                    bestCost = cost
                    best = index
                }
            }
            uses[best]++
            best
        }
    }

    /** Distanza tra due colori ARGB, pesata come li vede l'occhio (il verde conta di più). */
    fun distance(a: Int, b: Int): Long {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return 2L * dr * dr + 4L * dg * dg + 3L * db * db
    }

    /** Colore medio di pixel ARGB (codice a barre e fotomosaico); nero se non ce ne sono. */
    fun average(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0xFF000000.toInt()
        var r = 0L
        var g = 0L
        var b = 0L
        pixels.forEach { pixel ->
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
        val n = pixels.size
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Sezione dei titoli di coda: un titolo ("Fotografi") e i nomi, già in ordine. */
    data class CreditSection(val kind: CreditKind, val names: List<String>)

    enum class CreditKind { PHOTOGRAPHERS, MESSAGES, CHAMPIONS, GUESTS }

    /**
     * Titoli di coda: chi ha mandato più foto prima, poi chi ha scritto dediche, i campioni dei giochi
     * e tutti gli altri invitati con il nome. Ogni nome compare una volta per sezione, scritto come la
     * prima volta; le sezioni vuote non ci sono.
     */
    fun credits(
        photoSenders: List<String?>,
        messageNames: List<String?>,
        champions: List<String>,
        participants: List<String>,
    ): List<CreditSection> {
        fun distinct(names: List<String?>): List<String> {
            val seen = HashSet<String>()
            return names.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.filter { seen.add(it.lowercase()) }
        }
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        photoSenders.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.forEach { name ->
            val key = name.lowercase()
            counts[key] = (counts[key]?.first ?: name) to ((counts[key]?.second ?: 0) + 1)
        }
        val photographers = counts.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }.thenBy { it.index })
            .map { it.value.first }
        val shown = (photographers + distinct(messageNames) + distinct(champions)).map { it.lowercase() }.toSet()
        return listOf(
            CreditSection(CreditKind.PHOTOGRAPHERS, photographers),
            CreditSection(CreditKind.MESSAGES, distinct(messageNames)),
            CreditSection(CreditKind.CHAMPIONS, distinct(champions)),
            CreditSection(CreditKind.GUESTS, distinct(participants).filter { it.lowercase() !in shown }),
        ).filter { it.names.isNotEmpty() }
    }
}

/** Filtro sulle foto dello slideshow. */
enum class PhotoFilter(val labelRes: Int) {
    NONE(it.fotofesta.tv.R.string.filter_none),
    BLACK_WHITE(it.fotofesta.tv.R.string.filter_bw),
    SEPIA(it.fotofesta.tv.R.string.filter_sepia),
    WARM(it.fotofesta.tv.R.string.filter_warm),
    COOL(it.fotofesta.tv.R.string.filter_cool),
    VINTAGE(it.fotofesta.tv.R.string.filter_vintage),
}

/**
 * Modelli di festa: un clic sceglie titolo, transizione, disposizione, filtro ed effetti adatti
 * all'occasione. Le altre impostazioni restano come sono.
 */
enum class PartyTemplate(val labelRes: Int, val titleRes: Int) {
    NONE(it.fotofesta.tv.R.string.template_none, it.fotofesta.tv.R.string.template_title_none),
    BIRTHDAY(it.fotofesta.tv.R.string.template_birthday, it.fotofesta.tv.R.string.template_title_birthday),
    WEDDING(it.fotofesta.tv.R.string.template_wedding, it.fotofesta.tv.R.string.template_title_wedding),
    NEW_YEAR(it.fotofesta.tv.R.string.template_new_year, it.fotofesta.tv.R.string.template_title_new_year),
    GRADUATION(it.fotofesta.tv.R.string.template_graduation, it.fotofesta.tv.R.string.template_title_graduation),
    KIDS(it.fotofesta.tv.R.string.template_kids, it.fotofesta.tv.R.string.template_title_kids),
    REUNION(it.fotofesta.tv.R.string.template_reunion, it.fotofesta.tv.R.string.template_title_reunion);

    /** Impostazioni con il modello applicato. */
    fun apply(settings: SlideshowSettings): SlideshowSettings = when (this) {
        NONE -> settings.copy(template = this)
        BIRTHDAY -> settings.copy(template = this, transition = TransitionType.ZOOM, layout = SlideshowLayout.STACK, filter = PhotoFilter.NONE, confetti = true, openingTitles = true)
        WEDDING -> settings.copy(template = this, transition = TransitionType.FADE, layout = SlideshowLayout.SINGLE, filter = PhotoFilter.WARM, confetti = false, openingTitles = true, kenBurns = true)
        NEW_YEAR -> settings.copy(
            template = this,
            transition = TransitionType.ZAPPING,
            layout = SlideshowLayout.MOSAIC,
            confetti = true,
            countdown = true,
            countdownMinutes = 0,
            countdownEvent = CountdownEvent.MIDNIGHT,
        )
        GRADUATION -> settings.copy(template = this, transition = TransitionType.SLIDE, layout = SlideshowLayout.SINGLE, newsMode = true, confetti = true)
        KIDS -> settings.copy(template = this, transition = TransitionType.ZOOM, layout = SlideshowLayout.STACK, filter = PhotoFilter.NONE, confetti = true, newsMode = false)
        REUNION -> settings.copy(template = this, transition = TransitionType.FADE, layout = SlideshowLayout.STACK, filter = PhotoFilter.VINTAGE, newsMode = true)
    }
}
