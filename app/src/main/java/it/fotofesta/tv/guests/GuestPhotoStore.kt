package it.fotofesta.tv.guests

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/**
 * Foto mandate dagli invitati durante la festa.
 *
 * Restano in RAM finché c'è posto. Quando la memoria assegnata non basta, quando il sistema chiede
 * di liberarne o quando l'app va in background, le più vecchie passano in [directory], una cartella
 * temporanea con un limite ([maxDiskBytes] e al massimo [freeShare] dello spazio libero). Se anche
 * quella è piena, le foto più vecchie fanno posto alle nuove: prima quelle ancora da approvare, così
 * un invitato che manda molte foto non cancella quelle già in mostra. Le foto in attesa non possono
 * superare [maxPending]. Alla fine della festa ([clear]) tutto viene cancellato.
 *
 * I dati di ogni foto su disco stanno nel nome del file, così dopo un riavvio dell'app la festa può
 * riprendere senza un indice separato da tenere allineato.
 */
class GuestPhotoStore(
    private val directory: File,
    private val memoryBudgetBytes: Long,
    private val maxDiskBytes: Long,
    private val freeSpace: () -> Long = { usableSpaceOf(directory) },
    private val freeShare: Double = 0.10,
    private val maxPhotos: Int = 1_000,
    private val maxPending: Int = 100,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Photo(
        val id: String,
        val sender: String?,
        val receivedAtMillis: Long,
        val sizeBytes: Int,
        val approved: Boolean,
    )

    /** Esito di [add]. */
    sealed interface AddResult {
        data class Added(val photo: Photo) : AddResult
        /** Né RAM né cartella hanno posto per la foto. */
        data object Full : AddResult
        /** Troppe foto in attesa di approvazione. */
        data object TooManyPending : AddResult
    }

    private class Entry(var photo: Photo, var bytes: ByteArray?, var file: File?)

    // In ordine di arrivo: la prima è la più vecchia.
    private val entries = LinkedHashMap<String, Entry>()
    private var memoryUsed = 0L
    private var diskUsed = 0L
    private val random = SecureRandom()

    /**
     * Ogni foto va subito anche su disco, così un arresto improvviso dell'app non la perde e la
     * festa riprende al riavvio ("Riprendi la festa").
     */
    @Volatile var durable = false

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())

    /** Tutte le foto, approvate e da approvare, dalla più vecchia alla più recente. */
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    @Synchronized
    fun add(bytes: ByteArray, sender: String?, approved: Boolean): AddResult {
        require(bytes.isNotEmpty()) { "Foto vuota" }
        if (!approved && entries.values.count { !it.photo.approved } >= maxPending) return AddResult.TooManyPending
        val photo = Photo(newId(), GuestSender.clean(sender), clock(), bytes.size, approved)
        val entry = Entry(photo, bytes, null)
        entries[photo.id] = entry
        memoryUsed += bytes.size
        while (entries.size > maxPhotos) drop(victim(entries.values))
        // Una foto in attesa non può far cancellare foto già approvate: piuttosto viene rifiutata.
        fitInMemory(allowApprovedEviction = approved)
        // Se la scrittura non riesce la foto resta in RAM: si mostra lo stesso.
        if (durable && entries[photo.id] === entry) spill(entry, evictOthers = true, allowApprovedEviction = approved)
        publish()
        return if (entries[photo.id] === entry) AddResult.Added(photo) else AddResult.Full
    }

    /** Byte della foto, letti con il lock: un'approvazione non può rinominare il file a metà lettura. */
    @Synchronized
    fun read(id: String): ByteArray? {
        val entry = entries[id] ?: return null
        entry.bytes?.let { return it }
        return entry.file?.let { file -> runCatching { file.readBytes() }.getOrNull() }
    }

    /** Dove si trova la foto (per i test). */
    @Synchronized
    internal fun isOnDisk(id: String): Boolean? = entries[id]?.let { it.file != null }

    @Synchronized
    fun approve(id: String): Boolean {
        val entry = entries[id] ?: return false
        if (entry.photo.approved) return true
        val approved = entry.photo.copy(approved = true)
        entry.file?.let { file ->
            val renamed = File(directory, fileName(approved))
            if (!file.renameTo(renamed)) {
                // Senza il nuovo nome, dopo un riavvio la foto tornerebbe da approvare: meglio in RAM.
                entry.bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
                memoryUsed += entry.photo.sizeBytes
                file.delete()
                diskUsed -= entry.photo.sizeBytes
                entry.file = null
            } else {
                entry.file = renamed
            }
        }
        entry.photo = approved
        fitInMemory()
        publish()
        return true
    }

    @Synchronized
    fun remove(id: String) {
        entries[id]?.let(::drop)
        publish()
    }

    /** Sposta tutte le foto in RAM nella cartella di riserva, finché c'è posto. */
    @Synchronized
    fun spillAll() {
        entries.values.filter { it.bytes != null }.forEach { entry ->
            if (entries.containsKey(entry.photo.id)) spill(entry, evictOthers = false)
        }
        publish()
    }

    /** Cancella tutte le foto, in RAM e su disco, compresi file rimasti da sessioni precedenti. */
    @Synchronized
    fun clear() {
        entries.clear()
        memoryUsed = 0
        diskUsed = 0
        directory.listFiles()?.forEach { it.delete() }
        publish()
    }

    /**
     * Riprende le foto rimaste nella cartella di riserva, se la più recente è arrivata da meno di
     * [maxAgeMillis]; altrimenti la festa è finita da tempo e la cartella viene svuotata.
     */
    @Synchronized
    fun restore(maxAgeMillis: Long): Int {
        entries.clear()
        memoryUsed = 0
        diskUsed = 0
        val restored = directory.listFiles().orEmpty().mapNotNull { file ->
            val photo = if (file.isFile) parse(file) else null
            if (photo == null) file.delete()
            photo?.let { it to file }
        }.sortedBy { it.first.receivedAtMillis }
        val newest = restored.maxOfOrNull { it.first.receivedAtMillis }
        if (newest == null || clock() - newest > maxAgeMillis) {
            clear()
            return 0
        }
        restored.forEach { (photo, file) ->
            entries[photo.id] = Entry(photo, null, file)
            diskUsed += photo.sizeBytes
        }
        while (entries.size > maxPhotos) drop(victim(entries.values))
        while (diskUsed > diskBudget() && entries.isNotEmpty()) drop(victim(entries.values))
        publish()
        return entries.size
    }

    @Synchronized
    fun memoryUsedBytes(): Long = memoryUsed

    @Synchronized
    fun diskUsedBytes(): Long = diskUsed

    /** Prima da sacrificare: la foto in attesa più vecchia, altrimenti la più vecchia in assoluto. */
    private fun victim(candidates: Collection<Entry>): Entry =
        candidates.firstOrNull { !it.photo.approved } ?: candidates.first()

    private fun fitInMemory(allowApprovedEviction: Boolean = true) {
        while (memoryUsed > memoryBudgetBytes) {
            val inMemory = entries.values.filter { it.bytes != null }
            val oldest = inMemory.firstOrNull() ?: return
            if (!spill(oldest, evictOthers = true, allowApprovedEviction)) drop(victim(inMemory))
        }
    }

    /**
     * Scrive la foto su disco e la toglie dalla RAM. Con [evictOthers] fa posto cancellando le foto
     * più vecchie già su disco: prima quelle in attesa, e quelle approvate solo con
     * [allowApprovedEviction] e se anche la foto da spostare è approvata. Restituisce false se non
     * c'è posto o la scrittura non riesce.
     */
    private fun spill(entry: Entry, evictOthers: Boolean, allowApprovedEviction: Boolean = true): Boolean {
        val bytes = entry.bytes ?: return true
        val size = bytes.size.toLong()
        if (size > diskBudget()) return false
        while (diskUsed + size > diskBudget()) {
            if (!evictOthers) return false
            val onDisk = entries.values.filter { it.file != null && it !== entry }
            val evict = onDisk.firstOrNull { !it.photo.approved }
                ?: onDisk.firstOrNull()?.takeIf { allowApprovedEviction && entry.photo.approved }
                ?: return false
            drop(evict)
        }
        val target = File(directory, fileName(entry.photo))
        val temp = File(directory, "${entry.photo.id}.tmp")
        try {
            directory.mkdirs()
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) throw IOException("Rinomina non riuscita")
        } catch (failure: IOException) {
            temp.delete()
            target.delete()
            return false
        }
        entry.file = target
        entry.bytes = null
        memoryUsed -= size
        diskUsed += size
        return true
    }

    private fun drop(entry: Entry) {
        entries.remove(entry.photo.id)
        if (entry.bytes != null) memoryUsed -= entry.photo.sizeBytes
        entry.file?.let { file ->
            file.delete()
            diskUsed -= entry.photo.sizeBytes
        }
        entry.bytes = null
        entry.file = null
    }

    /** Limite della cartella: il minore tra il massimo fisso e una quota dello spazio libero. */
    private fun diskBudget(): Long {
        val free = runCatching(freeSpace).getOrDefault(0L).coerceAtLeast(0L)
        return minOf(maxDiskBytes, ((free + diskUsed) * freeShare).toLong())
    }

    private fun publish() {
        _photos.value = entries.values.map { it.photo }
    }

    private fun newId(): String {
        val bytes = ByteArray(ID_LENGTH / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fileName(photo: Photo): String {
        val sender = photo.sender?.let { URL_ENCODER.encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        val state = if (photo.approved) "a" else "p"
        return "${photo.receivedAtMillis}_${photo.id}_${state}_$sender$EXTENSION"
    }

    private fun parse(file: File): Photo? {
        val name = file.name
        if (!name.endsWith(EXTENSION)) return null
        // Il nome del mittente (ultimo campo) può contenere '_': si divide in quattro parti al massimo.
        val parts = name.removeSuffix(EXTENSION).split('_', limit = 4)
        if (parts.size != 4) return null
        val receivedAt = parts[0].toLongOrNull() ?: return null
        val id = parts[1].takeIf { it.length == ID_LENGTH && it.all(::isHexDigit) } ?: return null
        val approved = when (parts[2]) {
            "a" -> true
            "p" -> false
            else -> return null
        }
        val sender = parts[3].takeIf(String::isNotEmpty)?.let {
            runCatching { String(URL_DECODER.decode(it), Charsets.UTF_8) }.getOrNull() ?: return null
        }
        val size = file.length().takeIf { it in 1..Int.MAX_VALUE } ?: return null
        return Photo(id, GuestSender.clean(sender), receivedAt, size.toInt(), approved)
    }

    private fun isHexDigit(char: Char) = char in '0'..'9' || char in 'a'..'f'

    internal companion object {
        const val EXTENSION = ".img"
        const val ID_LENGTH = 16
        val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()

        /**
         * Spazio libero sul volume della cartella. `File.usableSpace` vale 0 per un percorso che non
         * esiste ancora: si misura la prima cartella esistente risalendo il percorso.
         */
        fun usableSpaceOf(directory: File): Long =
            generateSequence(directory.absoluteFile) { it.parentFile }.firstOrNull(File::exists)?.usableSpace ?: 0L
    }
}
