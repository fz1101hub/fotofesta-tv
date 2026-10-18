package it.fotofesta.tv.google

import android.content.Context
import it.fotofesta.tv.R
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.UserFacingException
import it.fotofesta.tv.network.LocalAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Consenso dal telefono attraverso la pagina che la TV serve sulla rete di casa. */
class LanPhoneConsent(
    private val context: Context,
    private val page: () -> String = { context.assets.open(PAGE_ASSET).bufferedReader().use { it.readText() } },
) : PhoneConsent {
    @Volatile
    private var server: GoogleConsentPageServer? = null

    override suspend fun obtainCode(authorizationUrl: (returnUrl: String) -> String, onState: (GoogleConnectState) -> Unit): String {
        val address = withContext(Dispatchers.IO) { LocalAddress.current(context) }
            ?: throw UserFacingException(R.string.error_google_no_network)
        val result = CompletableDeferred<String>()
        val key = UUID.randomUUID().toString().replace("-", "")
        val consent = GoogleConsentPageServer(
            key = key,
            page = page,
            authorizationUrl = authorizationUrl,
            onCode = { result.complete(it) },
            onError = { error ->
                val message = if (error == "access_denied") R.string.error_google_access_denied else R.string.error_google_consent_failed
                result.completeExceptionally(UserFacingException(message))
            },
        )
        val url = withContext(Dispatchers.IO) { consent.start(address) }
        server = consent
        onState(GoogleConnectState.WaitingForAuthorization(url, EXPIRES_SECONDS))
        return try {
            withTimeout(EXPIRES_SECONDS * 1_000L) { result.await() }
        } catch (_: TimeoutCancellationException) {
            throw UserFacingException(R.string.error_google_code_expired)
        }
    }

    override fun publishPickerUrl(url: String) {
        server?.pickerUrl = url
    }

    override fun close() {
        server?.close()
        server = null
    }

    companion object {
        const val PAGE_ASSET = "guest/google.html"
        /** Quanto resta aperta la pagina: il consenso su Google richiede un minuto o due. */
        const val EXPIRES_SECONDS = 10 * 60
    }
}

