package it.fotofesta.tv.guests

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Come si presenta la festa, preparato dalla regia sul telefono o importato da un file: nome e
 * colore dell'evento con il logo, scaletta della serata, sponsor da mostrare tra le foto e
 * invitati attesi. Resta sulla TV tra una festa e l'altra (un locale lo usa ogni sera), in
 * [directory]: un file JSON e le immagini accanto. Thread-safe.
 */
class PartySetup(private val directory: File) {
    /** [logoStamp] cambia a ogni logo nuovo, così chi lo mostra sa di doverlo rileggere. */
    data class Brand(val name: String = "", val accent: String? = null, val hasLogo: Boolean = false, val logoStamp: Long = 0)

    /** Momento della scaletta: minuti dalla mezzanotte e titolo ("21:30 Taglio della torta"). */
    data class Slot(val minuteOfDay: Int, val title: String) {
        val time: String get() = String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
    }

    /** Foto di un invitato da piccolo, per il gioco "Chi è da piccolo?". */
    data class Kid(val id: String, val name: String)

    data class State(
        val brand: Brand = Brand(),
        val schedule: List<Slot> = emptyList(),
        val sponsors: List<String> = emptyList(),
        val guestList: List<String> = emptyList(),
        /** Quiz sui festeggiati, una domanda per riga ("Domanda? | giusta | sbagliata"). */
        val quiz: String = "",
        val kids: List<Kid> = emptyList(),
    )

    private val random = SecureRandom()
    private val file get() = File(directory, "setup.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setName(name: String?) = update { it.copy(brand = it.brand.copy(name = GuestSender.clean(name, MAX_NAME).orEmpty())) }

    fun setAccent(color: String?) = update { it.copy(brand = it.brand.copy(accent = color?.takeIf(::validColor))) }

    /** Logo già ridotto dal telefono: al massimo [MAX_IMAGE_BYTES]; null lo toglie. */
    fun setLogo(bytes: ByteArray?): Boolean = synchronized(this) {
        if (bytes != null && (bytes.size > MAX_IMAGE_BYTES || ImageFormat.sniff(bytes) == null)) return false
        directory.mkdirs()
        if (bytes == null) File(directory, LOGO).delete() else File(directory, LOGO).writeBytes(bytes)
        update { it.copy(brand = it.brand.copy(hasLogo = bytes != null, logoStamp = it.brand.logoStamp + 1)) }
        true
    }

    fun logo(): ByteArray? = synchronized(this) { File(directory, LOGO).takeIf { it.exists() }?.readBytes() }

    /** Immagine di uno sponsor; null se ce ne sono già [MAX_SPONSORS] o non è un'immagine. */
    fun addSponsor(bytes: ByteArray): String? = synchronized(this) {
        if (bytes.size > MAX_IMAGE_BYTES || ImageFormat.sniff(bytes) == null || _state.value.sponsors.size >= MAX_SPONSORS) return null
        directory.mkdirs()
        val id = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        File(directory, "sponsor_$id").writeBytes(bytes)
        update { it.copy(sponsors = it.sponsors + id) }
        id
    }

    fun removeSponsor(id: String) = synchronized(this) {
        if (id !in _state.value.sponsors) return
        File(directory, "sponsor_$id").delete()
        update { it.copy(sponsors = it.sponsors - id) }
    }

    fun sponsor(id: String): ByteArray? = synchronized(this) {
        id.takeIf { it in _state.value.sponsors }?.let { File(directory, "sponsor_$it") }?.takeIf { it.exists() }?.readBytes()
    }

    /** Scaletta da testo, una riga per momento ("21:30 Taglio della torta"); le righe senza ora si saltano. */
    fun setSchedule(text: String?) = update { it.copy(schedule = parseSchedule(text.orEmpty())) }

    /** Invitati attesi, uno per riga o separati da virgole. */
    fun setGuestList(text: String?) = update { state ->
        val names = text.orEmpty().split('\n', ',', ';').mapNotNull { GuestSender.clean(it) }.distinct().take(MAX_GUESTS)
        state.copy(guestList = names)
    }

    fun setQuiz(text: String?) = update { it.copy(quiz = text.orEmpty().take(MAX_QUIZ_TEXT)) }

    /** Foto da piccolo di [name] per il gioco; null se sono già [MAX_KIDS] o non è un'immagine. */
    fun addKid(name: String?, bytes: ByteArray): String? = synchronized(this) {
        val cleanName = GuestSender.clean(name) ?: return null
        if (bytes.size > MAX_IMAGE_BYTES || ImageFormat.sniff(bytes) == null || _state.value.kids.size >= MAX_KIDS) return null
        directory.mkdirs()
        val id = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        File(directory, "kid_$id").writeBytes(bytes)
        update { it.copy(kids = it.kids + Kid(id, cleanName)) }
        id
    }

    fun removeKid(id: String) = synchronized(this) {
        // Solo gli id conosciuti: il nome del file non deve mai venire da fuori così com'è.
        if (_state.value.kids.none { it.id == id }) return@synchronized
        File(directory, "kid_$id").delete()
        update { it.copy(kids = it.kids.filterNot { kid -> kid.id == id }) }
    }

    fun kidPhoto(id: String): ByteArray? = synchronized(this) {
        id.takeIf { wanted -> _state.value.kids.any { it.id == wanted } }?.let { File(directory, "kid_$it") }?.takeIf { it.exists() }?.readBytes()
    }

    /** Tutto in un JSON, immagini comprese: per preparare una chiavetta o un'altra TV. */
    fun exportJson(): String = synchronized(this) {
        val current = _state.value
        JSONObject().apply {
            put("version", 1)
            put("name", current.brand.name)
            current.brand.accent?.let { put("accent", it) }
            logo()?.let { put("logo", Base64.getEncoder().encodeToString(it)) }
            put("schedule", JSONArray(current.schedule.map { "${it.time} ${it.title}" }))
            put("guests", JSONArray(current.guestList))
            put("sponsors", JSONArray(current.sponsors.mapNotNull { sponsor(it) }.map { Base64.getEncoder().encodeToString(it) }))
            // Le foto da piccoli restano sulla TV: sono personali e pesanti.
            put("quiz", current.quiz)
        }.toString()
    }

    /** Il contrario di [exportJson]: sostituisce tutto. False se il file non è valido. */
    fun importJson(text: String): Boolean = synchronized(this) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (json.optInt("version") != 1) return false
        _state.value.sponsors.toList().forEach(::removeSponsor)
        setName(json.optString("name"))
        setAccent(json.optString("accent").takeIf(String::isNotEmpty))
        setLogo(json.optString("logo").takeIf(String::isNotEmpty)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() })
        setSchedule(json.optJSONArray("schedule")?.let { array -> (0 until array.length()).joinToString("\n") { array.optString(it) } })
        setGuestList(json.optJSONArray("guests")?.let { array -> (0 until array.length()).joinToString("\n") { array.optString(it) } })
        setQuiz(json.optString("quiz"))
        json.optJSONArray("sponsors")?.let { array ->
            for (index in 0 until minOf(array.length(), MAX_SPONSORS)) {
                runCatching { Base64.getDecoder().decode(array.optString(index)) }.getOrNull()?.let(::addSponsor)
            }
        }
        true
    }

    /** Per la pagina degli invitati: nome, colore, logo e scaletta (niente lista degli invitati). */
    fun publicJson(): String {
        val current = _state.value
        return JSONObject().apply {
            put("name", current.brand.name)
            put("accent", current.brand.accent ?: JSONObject.NULL)
            put("logo", current.brand.hasLogo)
            put("schedule", JSONArray(current.schedule.map { JSONObject().put("time", it.time).put("title", it.title) }))
        }.toString()
    }

    /** Per la regia: tutto, compresi sponsor e invitati attesi (senza immagini). */
    fun json(): String {
        val current = _state.value
        return JSONObject().apply {
            put("name", current.brand.name)
            put("accent", current.brand.accent ?: JSONObject.NULL)
            put("logo", current.brand.hasLogo)
            put("schedule", JSONArray(current.schedule.map { JSONObject().put("time", it.time).put("title", it.title) }))
            put("sponsors", JSONArray(current.sponsors))
            put("guests", JSONArray(current.guestList))
            put("quiz", current.quiz)
            put("kids", JSONArray(current.kids.map { JSONObject().put("id", it.id).put("name", it.name) }))
        }.toString()
    }

    private fun update(change: (State) -> State) {
        synchronized(this) {
            val next = change(_state.value)
            _state.value = next
            runCatching {
                directory.mkdirs()
                file.writeText(
                    JSONObject().apply {
                        put("name", next.brand.name)
                        next.brand.accent?.let { put("accent", it) }
                        put("schedule", JSONArray(next.schedule.map { "${it.time} ${it.title}" }))
                        put("guests", JSONArray(next.guestList))
                        put("sponsors", JSONArray(next.sponsors))
                        put("quiz", next.quiz)
                        put("kids", JSONArray(next.kids.map { JSONObject().put("id", it.id).put("name", it.name) }))
                    }.toString(),
                )
            }
        }
    }

    private fun load(): State = runCatching {
        val json = JSONObject(file.readText())
        fun list(key: String) = json.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        State(
            brand = Brand(
                name = json.optString("name"),
                accent = json.optString("accent").takeIf(::validColor),
                hasLogo = File(directory, LOGO).exists(),
            ),
            schedule = parseSchedule(list("schedule").joinToString("\n")),
            sponsors = list("sponsors").filter { File(directory, "sponsor_$it").exists() },
            guestList = list("guests"),
            quiz = json.optString("quiz"),
            kids = json.optJSONArray("kids")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    Kid(item.optString("id"), item.optString("name")).takeIf { File(directory, "kid_${it.id}").exists() }
                }
            }.orEmpty(),
        )
    }.getOrDefault(State())

    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_SPONSORS = 8
        const val MAX_GUESTS = 300
        const val MAX_KIDS = 40
        const val MAX_QUIZ_TEXT = 6_000
        const val MAX_SLOTS = 30
        private const val MAX_NAME = 60
        private const val LOGO = "logo"

        fun validColor(value: String): Boolean = value.length == 7 && value[0] == '#' && value.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        /** "21:30 Taglio della torta", "9.15 - Arrivo" e simili; ordinata per ora. */
        fun parseSchedule(text: String): List<Slot> {
            val pattern = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*[-–—]?\s*(.+)$""")
            return text.lineSequence().mapNotNull { line ->
                val match = pattern.find(line) ?: return@mapNotNull null
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                if (hour > 23 || minute > 59) return@mapNotNull null
                val title = GuestSender.clean(match.groupValues[3], 80) ?: return@mapNotNull null
                Slot(hour * 60 + minute, title)
            }.take(MAX_SLOTS).sortedBy { it.minuteOfDay }.toList()
        }
    }
}
