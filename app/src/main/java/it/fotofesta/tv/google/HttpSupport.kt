package it.fotofesta.tv.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Risposta HTTP non riuscita. [oauthError] contiene il codice OAuth (es. `invalid_grant`) e
 * [serverMessage] la descrizione fornita da Google, se presenti.
 */
class HttpStatusException(
    val code: Int,
    val oauthError: String?,
    val serverMessage: String?,
) : IOException(serverMessage ?: "HTTP $code")

/** Esegue la chiamata in modo asincrono: se la coroutine viene annullata, anche la richiesta HTTP lo è. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}

/** Esegue [request] e restituisce il corpo JSON, oppure lancia [HttpStatusException] con il messaggio Google. */
suspend fun OkHttpClient.fetchJson(request: Request): JSONObject {
    val response = newCall(request).await()
    return withContext(Dispatchers.IO) {
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw httpError(it.code, text)
            JSONObject(text.ifBlank { "{}" })
        }
    }
}

internal fun httpError(code: Int, body: String): HttpStatusException {
    val json = runCatching { JSONObject(body) }.getOrNull()
    // Le API Google usano {"error":{"message":…}}, l'endpoint OAuth {"error":"…","error_description":…}.
    val apiMessage = json?.optJSONObject("error")?.optString("message")
    val oauthError = json?.opt("error") as? String
    val description = json?.optString("error_description")
    val message = listOf(apiMessage, description).firstOrNull { !it.isNullOrBlank() }
    return HttpStatusException(code, oauthError, message)
}

/** Converte una durata protobuf JSON (es. `"5s"`, `"1.5s"`) in secondi interi. */
internal fun parseProtoDurationSeconds(value: String?): Long? =
    value?.trim()?.removeSuffix("s")?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }?.toLong()
