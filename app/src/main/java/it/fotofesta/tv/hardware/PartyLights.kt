package it.fotofesta.tv.hardware

import it.fotofesta.tv.network.LanHttp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Luci della festa: Philips Hue (bridge), WLED e Nanoleaf prendono il colore della foto sullo
 * schermo e lampeggiano agli arrivi. Tutto nella rete di casa, con le API locali dei dispositivi
 * (niente account né cloud). Qui solo le richieste, senza Android: l'invio è in [PartyLightsClient].
 */
object PartyLights {
    enum class Kind { HUE, WLED, NANOLEAF }

    /** Luce collegata: indirizzo nella rete di casa e, per Hue e Nanoleaf, la chiave avuta all'abbinamento. */
    data class Device(val kind: Kind, val host: String, val port: Int, val token: String? = null, val name: String = host) {
        val baseUrl: String get() = "http://${if (':' in host) "[$host]" else host}:$port"

        fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("host", host).put("port", port).put("name", name)
            .apply { token?.let { put("token", it) } }

        companion object {
            fun fromJson(json: JSONObject): Device? = runCatching {
                Device(Kind.valueOf(json.getString("kind")), json.getString("host"), json.getInt("port"), json.optString("token").ifEmpty { null }, json.optString("name").ifEmpty { json.getString("host") })
            }.getOrNull()
        }
    }

    /** Una richiesta HTTP da mandare alla luce. */
    data class Call(val method: String, val url: String, val body: String)

    /** Porta e servizio mDNS di ciascun tipo, per trovarle nella rete. */
    fun serviceType(kind: Kind): String = when (kind) {
        Kind.HUE -> "_hue._tcp"
        Kind.WLED -> "_wled._tcp"
        Kind.NANOLEAF -> "_nanoleafapi._tcp"
    }

    fun defaultPort(kind: Kind): Int = when (kind) {
        Kind.HUE, Kind.WLED -> 80
        Kind.NANOLEAF -> 16021
    }

    /** Abbinamento: Hue dopo aver premuto il pulsante del bridge, Nanoleaf tenendo premuto l'interruttore. */
    fun pairCall(device: Device): Call? = when (device.kind) {
        Kind.HUE -> Call("POST", "${device.baseUrl}/api", JSONObject().put("devicetype", "fotofesta#tv").toString())
        Kind.NANOLEAF -> Call("POST", "${device.baseUrl}/api/v1/new", "")
        Kind.WLED -> null
    }

    /** La chiave nella risposta all'abbinamento; null se il pulsante non è stato premuto. */
    fun tokenFrom(kind: Kind, response: String): String? = runCatching {
        when (kind) {
            Kind.HUE -> JSONArray(response).optJSONObject(0)?.optJSONObject("success")?.optString("username")
            Kind.NANOLEAF -> JSONObject(response).optString("auth_token")
            Kind.WLED -> null
        }?.takeIf { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char == '-' } }
    }.getOrNull()

    /** Accende la luce del colore [rgb] (0xRRGGBB) con luminosità [brightness] da 0 a 1. */
    fun colorCall(device: Device, rgb: Int, brightness: Float = 1f): Call? {
        val level = brightness.coerceIn(0f, 1f)
        return when (device.kind) {
            Kind.HUE -> {
                val token = device.token ?: return null
                val (x, y) = hueXy(rgb)
                val body = JSONObject().put("on", true).put("bri", (1 + level * 253).roundToInt())
                    .put("xy", JSONArray().put(x).put(y)).put("transitiontime", 6)
                Call("PUT", "${device.baseUrl}/api/$token/groups/0/action", body.toString())
            }
            Kind.WLED -> {
                val color = JSONArray().put(rgb shr 16 and 0xFF).put(rgb shr 8 and 0xFF).put(rgb and 0xFF)
                val body = JSONObject().put("on", true).put("bri", (level * 255).roundToInt()).put("transition", 6)
                    .put("seg", JSONArray().put(JSONObject().put("col", JSONArray().put(color))))
                Call("POST", "${device.baseUrl}/json/state", body.toString())
            }
            Kind.NANOLEAF -> {
                val token = device.token ?: return null
                val (hue, saturation, _) = hsv(rgb)
                val body = JSONObject()
                    .put("on", JSONObject().put("value", true))
                    .put("hue", JSONObject().put("value", hue.roundToInt()))
                    .put("sat", JSONObject().put("value", (saturation * 100).roundToInt()))
                    .put("brightness", JSONObject().put("value", (level * 100).roundToInt()).put("duration", 1))
                Call("PUT", "${device.baseUrl}/api/v1/$token/state", body.toString())
            }
        }
    }

    /**
     * Colore di una foto adatto alle luci: il medio, con più saturazione e luminosità (un grigio
     * resta un bianco caldo, un colore spento diventa vivace).
     */
    fun partyColor(average: Int): Int {
        val (hue, saturation, value) = hsv(average)
        if (saturation < 0.12f) return 0xFFC98A // bianco caldo
        return fromHsv(hue, min(1f, saturation * 1.6f + 0.2f), max(value, 0.85f))
    }

    /** Coordinate CIE xy (gamut di Hue) per un colore sRGB. */
    fun hueXy(rgb: Int): Pair<Double, Double> {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92
        }
        val r = linear(rgb shr 16 and 0xFF)
        val g = linear(rgb shr 8 and 0xFF)
        val b = linear(rgb and 0xFF)
        val x = r * 0.664511 + g * 0.154324 + b * 0.162028
        val y = r * 0.283881 + g * 0.668433 + b * 0.047685
        val z = r * 0.000088 + g * 0.072310 + b * 0.986039
        val sum = x + y + z
        if (sum == 0.0) return 0.3227 to 0.329 // bianco
        return round4(x / sum) to round4(y / sum)
    }

    private fun round4(value: Double) = (value * 10_000).roundToInt() / 10_000.0

    /** Tinta (0–360), saturazione e valore (0–1). */
    fun hsv(rgb: Int): Triple<Float, Float, Float> {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        val delta = high - low
        val hue = when {
            delta == 0f -> 0f
            high == r -> 60f * (((g - b) / delta).mod(6f))
            high == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Triple(hue, if (high == 0f) 0f else delta / high, high)
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f).mod(2f) - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt().mod(6)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}

/** Manda le richieste di [PartyLights] alle luci, solo nella rete di casa. */
class PartyLightsClient(private val http: LanHttp = LanHttp(connectTimeoutMs = 2_000, readTimeoutMs = 4_000)) {
    /** Abbina la luce; null se serve ancora premere il pulsante o se non risponde. */
    fun pair(device: PartyLights.Device): String? {
        val call = PartyLights.pairCall(device) ?: return null
        return runCatching { send(call) }.getOrNull()?.let { PartyLights.tokenFrom(device.kind, it) }
    }

    fun show(device: PartyLights.Device, rgb: Int, brightness: Float = 1f): Boolean {
        val call = PartyLights.colorCall(device, rgb, brightness) ?: return false
        return runCatching { send(call) }.isSuccess
    }

    private fun send(call: PartyLights.Call): String {
        val headers = mapOf("Content-Type" to "application/json")
        val body = call.body.toByteArray(Charsets.UTF_8)
        val response = if (call.method == "PUT") http.put(call.url, headers, body) else http.post(call.url, headers, body)
        return response.use {
            if (it.status !in 200..299) throw java.io.IOException("HTTP ${it.status}")
            String(it.bytes(64 * 1024), Charsets.UTF_8)
        }
    }
}
