package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Flusso completo di importazione da Google Foto: autorizzazione con codice e QR, sessione Picker,
 * attesa della selezione, download nella cache privata e chiusura della sessione.
 *
 * Gli stati intermedi da mostrare sulla TV vengono notificati con `onState`. `onImported` riceve il
 * risultato appena l'archivio è stato sostituito, prima della chiusura della sessione Picker, che può
 * richiedere qualche secondo; da quel momento un annullamento non deve più scartarlo. La sessione
 * Picker viene eliminata anche in caso di errore o annullamento.
 */
class GoogleImportFlow(
    private val auth: GoogleDeviceAuthRepository,
    private val picker: GooglePhotosPickerRepository,
) {
    data class Result(val photos: List<PhotoItem>, val skipped: Int)

    suspend fun run(
        onImported: (Result) -> Unit = {},
        onState: (GoogleConnectState) -> Unit,
    ): Result {
        var token = authorize(onState)
        val session = try {
            picker.createSession(token)
        } catch (unauthorized: HttpStatusException) {
            if (unauthorized.code != 401) throw unauthorized
            // Token revocato dall'utente: si ripete il collegamento da capo una sola volta.
            auth.disconnect()
            token = authorize(onState)
            picker.createSession(token)
        }
        // I quattro download paralleli chiedono il token insieme: il rinnovo avviene una volta sola.
        val refreshLock = Mutex()
        val tokens: TokenProvider = {
            refreshLock.withLock {
                auth.validAccessToken() ?: throw UserFacingException(R.string.error_google_session_expired)
            }
        }
        try {
            onState(GoogleConnectState.WaitingForPhotos(session.pickerUri))
            val completed = picker.awaitSelection(tokens, session)
            val picked = picker.listPickedMedia(tokens, completed.id)
            if (picked.isEmpty()) throw UserFacingException(R.string.error_google_nothing_selected)
            onState(GoogleConnectState.Downloading(0, picked.size))
            val downloaded = picker.downloadToPrivateCache(tokens, picked) { done, total ->
                onState(GoogleConnectState.Downloading(done, total))
            }
            if (downloaded.isEmpty()) throw UserFacingException(R.string.error_google_download_failed)
            val result = Result(downloaded, skipped = picked.size - downloaded.size)
            onImported(result)
            return result
        } finally {
            withContext(NonCancellable) { picker.deleteSession(tokens, session.id) }
        }
    }

    /** Restituisce un token valido o avvia il flusso di autorizzazione con codice e QR. */
    private suspend fun authorize(onState: (GoogleConnectState) -> Unit): String {
        auth.validAccessToken()?.let { return it }
        val code = auth.requestDeviceCode()
        onState(GoogleConnectState.WaitingForAuthorization(code.verificationUrl, code.userCode, code.expiresIn))
        return auth.pollForToken(code).accessToken
    }
}
