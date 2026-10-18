package it.fotofesta.tv.google

import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Foto scelte in Google Foto: sulla TV restano solo la sessione Picker, gli identificativi e i nomi,
 * mai le immagini. Le foto si caricano da Google quando servono e solo finché la sessione è valida.
 */
data class GoogleSelection(
    val sessionId: String,
    /** Scadenza della sessione indicata da Google (epoch ms), se nota. */
    val expiresAtMillis: Long?,
    val items: List<Item>,
) {
    data class Item(val id: String, val filename: String, val video: Boolean = false)

    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis >= expiresAtMillis

    fun toPhotos(): List<PhotoItem> = items.map { item ->
        PhotoItem(
            id = "google:${item.id}",
            title = item.filename,
            ref = PhotoRef.GooglePhoto(item.id),
            source = SourceType.GOOGLE_PHOTOS,
            video = item.video,
        )
    }
}

/**
 * Salva la selezione in un piccolo file JSON (alcune centinaia di byte per ogni foto) e ne tiene una
 * copia in memoria, letta da app, salvaschermo e caricamento delle immagini.
 */
class GoogleSelectionStore(private val file: File) {
    @Volatile
    private var loaded = false

    @Volatile
    private var cached: GoogleSelection? = null

    @Synchronized
    fun current(): GoogleSelection? {
        if (!loaded) {
            cached = read()
            loaded = true
        }
        return cached
    }

    @Synchronized
    fun save(selection: GoogleSelection) {
        val json = JSONObject()
            .put("sessionId", selection.sessionId)
            .put("items", JSONArray().apply {
                selection.items.forEach { item ->
                    put(JSONObject().put("id", item.id).put("filename", item.filename).apply { if (item.video) put("video", true) })
                }
            })
        selection.expiresAtMillis?.let { json.put("expiresAt", it) }
        file.parentFile?.mkdirs()
        // Scrittura su file temporaneo e rinomina: un'interruzione non lascia un file a metà.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Impossibile salvare la selezione Google Foto" }
        }
        cached = selection
        loaded = true
    }

    /** Cancella la selezione solo se appartiene ancora a [sessionId]; restituisce `true` se l'ha cancellata. */
    @Synchronized
    fun clearIfCurrent(sessionId: String): Boolean {
        if (current()?.sessionId != sessionId) return false
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        file.delete()
        cached = null
        loaded = true
    }

    private fun read(): GoogleSelection? = runCatching {
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val array = json.getJSONArray("items")
        val items = (0 until array.length()).mapNotNull { index ->
            val entry = array.getJSONObject(index)
            val id = entry.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            GoogleSelection.Item(id, entry.optString("filename").ifBlank { id }, video = entry.optBoolean("video", false))
        }
        GoogleSelection(
            sessionId = json.getString("sessionId"),
            expiresAtMillis = json.optLong("expiresAt", 0L).takeIf { it > 0L },
            items = items,
        ).takeIf { it.items.isNotEmpty() && it.sessionId.isNotBlank() }
    }.getOrNull()
}
