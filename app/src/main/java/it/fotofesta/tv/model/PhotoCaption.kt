package it.fotofesta.tv.model

/**
 * Didascalia dello slideshow. I nomi dati dalle fotocamere e dai telefoni ("IMG_20240512_153422",
 * "PXL_…", "WhatsApp Image …") non dicono nulla a chi guarda: al loro posto si usa il nome della
 * cartella, oppure nessun titolo.
 */
object PhotoCaption {
    private val CAMERA_PREFIXES = listOf(
        "img", "dsc", "dscn", "dscf", "pxl", "mvimg", "pano", "burst", "photo", "pic", "image", "cimg",
        "screenshot", "whatsapp image", "signal-", "telegram", "gopr", "dji", "sam_", "wp_", "imag",
    )

    fun looksLikeCameraName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase()
        val digits = trimmed.count(Char::isDigit)
        val letters = trimmed.count(Char::isLetter)
        // Molte cifre e poche lettere: data e ora scritte dal telefono o dalla fotocamera.
        if (digits >= 6 && letters <= 4) return true
        return digits >= 2 && CAMERA_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Titolo da mostrare per una foto di una cartella: il nome del file se è significativo,
     * altrimenti il nome della cartella che la contiene, se anche quello lo è.
     */
    fun title(fileTitle: String, folderName: String?): String? = when {
        !looksLikeCameraName(fileTitle) -> fileTitle
        folderName != null && isMeaningfulFolder(folderName) -> folderName
        else -> null
    }

    /** Le cartelle create dal telefono o dalla fotocamera ("DCIM", "Camera", "100CANON") non sono un titolo. */
    private fun isMeaningfulFolder(name: String): Boolean =
        !looksLikeCameraName(name) && name.trim().lowercase() !in GENERIC_FOLDERS && !DCF_FOLDER.matches(name.trim())

    private val GENERIC_FOLDERS = setOf(
        "dcim", "camera", "cameras", "pictures", "photos", "foto", "fotos", "immagini", "images", "bilder", "images",
        "download", "downloads", "screenshots", "whatsapp images", "whatsapp", "telegram", "sent", "misc", "storage",
    )

    /** Cartelle dello standard DCF delle fotocamere: tre cifre e cinque caratteri, es. 100CANON. */
    private val DCF_FOLDER = Regex("""\d{3}[A-Za-z0-9_]{5}""")

    /**
     * Data di scatto dal valore EXIF "aaaa:mm:gg hh:mm:ss" come (anno, mese 1-12, giorno), o null se
     * il valore manca o non è valido (alcune fotocamere scrivono zeri o spazi).
     */
    fun parseExifDate(value: String?): Triple<Int, Int, Int>? {
        val match = EXIF_DATE.matchEntire(value?.trim() ?: return null) ?: return null
        val (year, month, day) = match.destructured
        val y = year.toInt()
        val m = month.toInt()
        val d = day.toInt()
        return if (y in 1900..2200 && m in 1..12 && d in 1..31) Triple(y, m, d) else null
    }

    private val EXIF_DATE = Regex("""(\d{4})[:\-](\d{2})[:\-](\d{2})(?:[ T].*)?""")
}
