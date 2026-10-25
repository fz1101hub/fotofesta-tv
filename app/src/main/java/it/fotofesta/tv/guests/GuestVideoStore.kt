package it.fotofesta.tv.guests

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Video brevi mandati dagli invitati: vanno direttamente su disco, in una cartella con un limite
 * ([maxTotalBytes] e al massimo [freeShare] dello spazio libero), mai interi in RAM. Come per le
 * foto, i dati di ogni video stanno nel nome del file, così la festa riprende dopo un riavvio.
 * A fine festa ([clear]) tutto viene cancellato.
 */
class GuestVideoStore(
    private val directory: File,
    private val maxTotalBytes: Long,
    private val maxVideos: Int = 100,
    private val freeSpace: () -> Long = { runCatching { directory.usableSpace }.getOrDefault(0L) },
    private val freeShare: Double = 0.10,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Video(val id: String, val sender: String?, val receivedAtMillis: Long, val sizeBytes: Long, val file: File)

    sealed interface AddResult {
        data class Added(val video: Video) : AddResult
        data object Full : AddResult
        data object Invalid : AddResult
    }

    private val random = SecureRandom()
    private val _videos = MutableStateFlow<List<Video>>(emptyList())

    /** Video della festa, dal più vecchio. */
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    /**
     * Copia [length] byte da [input] in un file nuovo. Se la copia non finisce (connessione caduta,
     * troppo lenta) o il contenuto non è un video, il file si cancella.
     */
    fun add(input: InputStream, length: Long, sender: String?, check: (ByteArray) -> Boolean): AddResult {
        synchronized(this) {
            val used = _videos.value.sumOf { it.sizeBytes }
            if (_videos.value.size >= maxVideos || used + length > maxTotalBytes || length > freeSpace() * freeShare) return AddResult.Full
        }
        directory.mkdirs()
        val id = newId()
        val cleanSender = GuestSender.clean(sender)
        val temp = File(directory, "$id.part")
        try {
            // L'inizio del file dice se è davvero un video, prima di scrivere il resto.
            val head = ByteArray(minOf(HEAD_BYTES.toLong(), length).toInt())
            java.io.DataInputStream(input).readFully(head)
            if (!check(head)) return AddResult.Invalid
            temp.outputStream().use { output ->
                output.write(head)
                val buffer = ByteArray(64 * 1024)
                var left = length - head.size
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("Video incompleto")
                    output.write(buffer, 0, read)
                    left -= read
                }
            }
        } catch (failure: IOException) {
            temp.delete()
            throw failure
        }
        val at = clock()
        val video = Video(id, cleanSender, at, length, File(directory, fileName(id, at, cleanSender)))
        synchronized(this) {
            if (!temp.renameTo(video.file)) {
                temp.delete()
                return AddResult.Full
            }
            _videos.value = _videos.value + video
        }
        return AddResult.Added(video)
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val video = _videos.value.firstOrNull { it.id == id } ?: return false
        video.file.delete()
        _videos.value = _videos.value - video
        return true
    }

    /** Video di una festa interrotta da poco; quelli più vecchi di [maxAgeMillis] si cancellano. */
    @Synchronized
    fun restore(maxAgeMillis: Long) {
        val now = clock()
        val found = directory.listFiles().orEmpty().mapNotNull { file ->
            if (file.name.endsWith(".part")) {
                file.delete()
                return@mapNotNull null
            }
            val video = parse(file.name)?.copy(file = file, sizeBytes = file.length())
            if (video == null || now - video.receivedAtMillis > maxAgeMillis) {
                file.delete()
                null
            } else video
        }
        _videos.value = found.sortedBy { it.receivedAtMillis }
    }

    @Synchronized
    fun clear() {
        directory.listFiles().orEmpty().forEach { it.delete() }
        _videos.value = emptyList()
    }

    private fun newId(): String {
        val bytes = ByteArray(ID_LENGTH / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fileName(id: String, at: Long, sender: String?): String {
        val encoded = sender?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        return "v_${id}_${at}_$encoded.mp4"
    }

    private fun parse(name: String): Video? {
        if (!name.startsWith("v_") || !name.endsWith(".mp4")) return null
        val parts = name.removePrefix("v_").removeSuffix(".mp4").split('_')
        if (parts.size != 3) return null
        val id = parts[0].takeIf { it.length == ID_LENGTH && it.all { char -> char.isDigit() || char in 'a'..'f' } } ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        val sender = parts[2].takeIf(String::isNotEmpty)?.let {
            runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
        }
        return Video(id, sender, at, 0, File(directory, name))
    }

    companion object {
        const val ID_LENGTH = 16
        private const val HEAD_BYTES = 12

        /** Inizio di un video MP4/MOV ("ftyp") o WebM (EBML): il resto lo decide il lettore della TV. */
        fun looksLikeVideo(head: ByteArray): Boolean {
            if (head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
            ) return true
            return head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        }
    }
}
