package it.fotofesta.tv.cast

import android.util.Log
import it.fotofesta.tv.BuildConfig
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SettingsPatch
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.TransitionType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject

sealed interface CastCommand {
    data object Play : CastCommand
    data object Pause : CastCommand
    data object Next : CastCommand
    data object Previous : CastCommand
    data class Load(val photos: List<PhotoItem>, val settings: SettingsPatch?) : CastCommand
    data class ChangeSettings(val settings: SettingsPatch) : CastCommand
}

object CastBridge {
    private const val TAG = "CastBridge"

    // Un Channel conserva i comandi arrivati prima che la UI inizi a raccoglierli
    // (es. LOAD inviato subito dopo l'avvio dell'app tramite Cast Connect).
    private val _commands = Channel<CastCommand>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: Flow<CastCommand> = _commands.receiveAsFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    fun senderConnected(value: Boolean) { _connected.value = value }

    fun receive(message: String) {
        runCatching { parse(message) }
            .onSuccess { _commands.trySend(it) }
            .onFailure { Log.w(TAG, "Messaggio Cast ignorato: ${it.message}") }
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
                require(RemoteUrlPolicy.isAllowed(url)) { "URL remoto non ammesso" }
                add(
                    PhotoItem(
                        // L'indice rende l'id univoco anche se il sender ripete o omette gli id.
                        id = "cast:$index:${item.optString("id").take(160)}",
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

    private fun parseSettings(json: JSONObject) = SettingsPatch(
        intervalSeconds = (json.opt("intervalSeconds") as? Number)?.toInt()?.coerceIn(2, 120),
        transition = enumOrNull<TransitionType>(json.opt("transition")),
        fitMode = enumOrNull<FitMode>(json.opt("fitMode")),
        shuffle = json.opt("shuffle") as? Boolean,
        reducedMotion = json.opt("reducedMotion") as? Boolean,
        showClock = json.opt("showClock") as? Boolean,
        musicVolume = (json.opt("musicVolume") as? Number)?.toFloat()?.coerceIn(0f, 1f),
    )

    private inline fun <reified T : Enum<T>> enumOrNull(value: Any?): T? =
        (value as? String)?.let { name -> enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } }
}
