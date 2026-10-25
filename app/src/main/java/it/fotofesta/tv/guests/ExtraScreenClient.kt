package it.fotofesta.tv.guests

import it.fotofesta.tv.network.LanHttp
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Schermo aggiuntivo: questa TV si collega alla festa di un'altra TV della casa (stessa rete) con il
 * PIN della regia e ne mostra la parete delle foto. Tutto passa da [LanHttp], solo verso indirizzi
 * della rete locale.
 */
class ExtraScreenClient(private val http: LanHttp) {
    /** Festa collegata: indirizzo della TV principale ("http://192.168.1.20:8765") e chiave. */
    data class Party(val base: String, val key: String) {
        /** Indirizzo della pagina per i telefoni, per il QR dello schermo aggiuntivo. */
        val guestUrl: String get() = "$base/?k=$key"
    }

    data class WallPhoto(val id: String, val sender: String?)

    data class Wall(
        val title: String,
        val count: Int,
        val photos: List<WallPhoto>,
        val messages: List<String>,
        val announcement: String?,
    )

    /** Il PIN non è quello della TV principale. */
    class WrongPinException : IOException("PIN sbagliato")

    /** La TV principale non accetta schermi: regia spenta, troppi tentativi o festa finita. */
    class UnavailableException(val status: Int) : IOException("Schermo non accettato: $status")

    fun login(base: String, pin: String): Party {
        val body = "pin=" + URLEncoder.encode(pin.filter(Char::isDigit), "UTF-8")
        http.post("$base/screen/login", mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray()).use { response ->
            when (response.status) {
                200 -> {
                    val key = JSONObject(response.bytes(MAX_JSON).toString(Charsets.UTF_8)).getString("key")
                    return Party(base, key)
                }
                403 -> throw WrongPinException()
                else -> throw UnavailableException(response.status)
            }
        }
    }

    fun wall(party: Party): Wall = http.get("${party.base}/wall/state?k=${encode(party.key)}").use { response ->
        if (response.status == 401) throw UnavailableException(401)
        if (!response.isSuccessful) throw IOException("HTTP ${response.status}")
        parseWall(response.bytes(MAX_JSON).toString(Charsets.UTF_8))
    }

    fun photo(party: Party, id: String): ByteArray =
        http.get("${party.base}/wall/photo?k=${encode(party.key)}&id=${encode(id)}").use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.status}")
            response.bytes(MAX_PHOTO)
        }

    companion object {
        private const val MAX_JSON = 256 * 1024
        private const val MAX_PHOTO = 20 * 1024 * 1024

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        /** "192.168.1.20", "192.168.1.20:8765" o un indirizzo completo diventano "http://…:porta". */
        fun base(address: String, defaultPort: Int = 8765): String {
            val trimmed = address.trim().trimEnd('/').removePrefix("http://")
            val host = trimmed.substringBefore('/')
            return "http://" + if (':' in host) host else "$host:$defaultPort"
        }

        fun parseWall(text: String): Wall {
            val json = JSONObject(text)
            val photos = json.optJSONArray("photos")
            val messages = json.optJSONArray("messages")
            return Wall(
                title = json.optString("title"),
                count = json.optInt("count"),
                photos = (0 until (photos?.length() ?: 0)).mapNotNull { index ->
                    val item = photos!!.optJSONObject(index) ?: return@mapNotNull null
                    WallPhoto(item.optString("id").ifEmpty { return@mapNotNull null }, item.optString("sender").takeIf { !item.isNull("sender") && it.isNotBlank() })
                },
                messages = (0 until (messages?.length() ?: 0)).mapNotNull { index ->
                    val item = messages!!.optJSONObject(index) ?: return@mapNotNull null
                    val text = item.optString("text").ifBlank { return@mapNotNull null }
                    if (item.isNull("name")) text else "${item.optString("name")}: $text"
                },
                announcement = json.optString("announcement").takeIf { !json.isNull("announcement") && it.isNotBlank() },
            )
        }
    }
}
