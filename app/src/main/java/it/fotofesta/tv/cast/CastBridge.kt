package it.fotofesta.tv.cast

import it.fotofesta.tv.BuildConfig
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.TransitionType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URI

sealed interface CastCommand {
    data object Play : CastCommand
    data object Pause : CastCommand
    data object Next : CastCommand
    data object Previous : CastCommand
    data class Load(val photos: List<PhotoItem>, val settings: SlideshowSettings?) : CastCommand
    data class ChangeSettings(val settings: SlideshowSettings) : CastCommand
}

object CastBridge {
    private val _commands = MutableSharedFlow<CastCommand>(extraBufferCapacity = 16)
    val commands = _commands.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    fun senderConnected(value: Boolean) { _connected.value = value }

    fun receive(message: String) {
        runCatching { parse(message) }.onSuccess(_commands::tryEmit)
    }

    internal fun parse(message: String): CastCommand {
        require(message.length in 2..1_000_000) { "Dimensione messaggio non valida" }
        val json = JSONObject(message)
        require(json.optInt("v", -1) == BuildConfig.CAST_PROTOCOL_VERSION) { "Versione protocollo non supportata" }
        return when (json.getString("type").uppercase()) {
            "PLAY" -> CastCommand.Play
            "PAUSE" -> CastCommand.Pause
            "NEXT" -> CastCommand.Next
            "PREVIOUS" -> CastCommand.Previous
            "LOAD" -> parseLoad(json)
            "SETTINGS" -> CastCommand.ChangeSettings(parseSettings(json.getJSONObject("settings")))
            else -> error("Comando Cast sconosciuto")
        }
    }

    private fun parseLoad(json: JSONObject): CastCommand.Load {
        val array = json.getJSONArray("photos")
        require(array.length() in 1..500) { "Numero foto non valido" }
        val photos = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val url = item.getString("url")
                require(isSafeRemoteUrl(url)) { "URL remoto non ammesso" }
                add(
                    PhotoItem(
                        id = "cast:${item.optString("id", index.toString()).take(160)}",
                        title = item.optString("title", "Foto ${index + 1}").take(240),
                        ref = PhotoRef.Remote(url),
                        source = SourceType.CAST,
                    )
                )
            }
        }
        val settings = json.optJSONObject("settings")?.let(::parseSettings)
        return CastCommand.Load(photos, settings)
    }

    private fun isSafeRemoteUrl(value: String): Boolean = runCatching {
        if (value.length !in 10..2_048) return@runCatching false
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return@runCatching false
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) {
            return@runCatching false
        }
        !isPrivateHost(host)
    }.getOrDefault(false)

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host == "::" || host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")) return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 0 || octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun parseSettings(json: JSONObject): SlideshowSettings = SlideshowSettings(
        intervalSeconds = json.optInt("intervalSeconds", 8).coerceIn(2, 120),
        transition = runCatching { TransitionType.valueOf(json.optString("transition", "FADE")) }.getOrDefault(TransitionType.FADE),
        fitMode = runCatching { FitMode.valueOf(json.optString("fitMode", "CONTAIN")) }.getOrDefault(FitMode.CONTAIN),
        shuffle = json.optBoolean("shuffle", false),
        reducedMotion = json.optBoolean("reducedMotion", false),
        highContrast = json.optBoolean("highContrast", false),
        showClock = json.optBoolean("showClock", true),
        musicVolume = json.optDouble("musicVolume", 0.35).toFloat().coerceIn(0f, 1f),
    )
}
