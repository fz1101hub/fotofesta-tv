package it.fotofesta.tv.files

import it.fotofesta.tv.data.NaturalOrder
import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import java.io.File
import java.io.IOException

/** Foto di un disco letta dal suo percorso, come elemento dello slideshow. */
fun File.toUsbPhotoItem(): PhotoItem = PhotoItem(
    id = "usb:file:$path",
    title = nameWithoutExtension.ifBlank { name },
    ref = PhotoRef.LocalFile(path),
    source = SourceType.USB,
)

/**
 * Lettura di cartelle e file per il browser interno dell'app, che sostituisce il selettore di
 * documenti di Android: molte TV non lo hanno. Funziona con i percorsi dei dischi montati dal
 * sistema (ad esempio `/storage/1234-ABCD`) e i permessi di lettura delle foto e della musica.
 */
class FileBrowser(
    private val imageExtensions: Set<String>,
    private val audioExtensions: Set<String> = AUDIO_EXTENSIONS,
) {
    /** Contenuto di una cartella: sottocartelle e file del tipo cercato, in ordine naturale. */
    data class Listing(val folders: List<File>, val files: List<File>)

    fun list(directory: File, kind: BrowserKind): Listing {
        val entries = directory.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
        val folders = entries.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
        val files = entries.filter { it.isFile && matches(it, kind) }.sortedWith(compareBy(NaturalOrder) { it.name })
        return Listing(folders, files)
    }

    /**
     * Foto della cartella e delle sottocartelle, al massimo [limit], ordinate per nome come prima.
     * [checkCancelled] viene chiamato a ogni cartella e può lanciare un'eccezione per interrompere.
     * I collegamenti simbolici che riportano a una cartella già vista vengono ignorati.
     */
    fun scanPhotos(directory: File, limit: Int = 10_000, checkCancelled: () -> Unit = {}): List<File> {
        val queue = ArrayDeque<File>().apply { add(directory) }
        val visited = HashSet<String>()
        val found = mutableListOf<File>()
        while (queue.isNotEmpty() && found.size < limit) {
            checkCancelled()
            val current = queue.removeFirst()
            val key = try {
                current.canonicalPath
            } catch (_: IOException) {
                current.absolutePath
            }
            if (!visited.add(key)) continue
            for (entry in current.listFiles().orEmpty()) {
                if (found.size >= limit) break
                when {
                    // Esclude file nascosti, come i "._IMG.jpg" creati da macOS sui dischi esterni.
                    entry.name.startsWith(".") -> Unit
                    entry.isDirectory -> queue.addLast(entry)
                    entry.isFile && matches(entry, BrowserKind.PHOTOS) -> found += entry
                }
            }
        }
        return found.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    /** Foto direttamente nella cartella (senza sottocartelle), per l'anteprima del conteggio. */
    fun countPhotos(directory: File): Int =
        directory.listFiles().orEmpty().count { !it.name.startsWith(".") && it.isFile && matches(it, BrowserKind.PHOTOS) }

    private fun matches(file: File, kind: BrowserKind): Boolean {
        val extension = file.extension.lowercase()
        return when (kind) {
            BrowserKind.PHOTOS -> extension in imageExtensions
            BrowserKind.MUSIC -> extension in audioExtensions
        }
    }

    companion object {
        /** Formati audio che il lettore di Android riproduce su tutte le versioni supportate. */
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "mid", "midi", "amr", "3gp")

        /**
         * Formati di immagine decodificabili dalla versione di Android indicata: RAW, TIFF e PSD
         * apparirebbero come foto vuote. HEIC/HEIF si decodificano da Android 9, AVIF da Android 12.
         */
        fun imageExtensions(sdk: Int): Set<String> = buildSet {
            addAll(listOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp"))
            if (sdk >= 28) addAll(listOf("heic", "heif"))
            if (sdk >= 31) add("avif")
        }
    }
}
