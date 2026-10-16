package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Flusso completo di collegamento a Google Foto: autorizzazione con codice e QR, sessione Picker,
 * attesa della selezione ed elenco delle foto scelte. Le foto non vengono scaricate: il risultato
 * contiene solo identificativi e nomi, e la sessione resta aperta perché serve a ottenere gli
 * indirizzi aggiornati durante lo slideshow.
 *
 * Gli stati intermedi da mostrare sulla TV vengono notificati con `onState`. Se il flusso non
 * arriva in fondo (errore, nessuna foto, annullamento) la sessione Picker viene eliminata.
 */
class GoogleImportFlow(
    private val auth: GoogleDeviceAuthRepository,
    private val picker: GooglePhotosPickerRepository,
) {
    /** [skipped] conta gli elementi scelti che non sono né foto né video. */
    data class Result(val selection: GoogleSelection, val skipped: Int)

    suspend fun run(onState: (GoogleConnectState) -> Unit): Result {
        var token = authorize(onState)
        val session = try {
            picker.createSession(token)
        } catch (unauthorized: HttpStatusException) {
            if (unauthorized.code != 401) throw unauthorized
            // Token rifiutato: prima si prova a rinnovarlo (le foto mostrate restano raggiungibili),
            // e solo se non si può si ripete il collegamento da capo, una sola volta.
            val renewed = auth.validAccessToken(forceRefresh = true)
            if (renewed != null) {
                token = renewed
            } else {
                auth.disconnect()
                token = authorize(onState)
            }
            picker.createSession(token)
        }
        // Le richieste chiedono il token in sequenza, ma il rinnovo deve comunque avvenire una volta sola.
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
            val media = picked.filter { it.isPhoto || it.isVideo }
            if (media.isEmpty()) throw UserFacingException(R.string.error_google_nothing_selected)
            val selection = GoogleSelection(
                sessionId = completed.id,
                expiresAtMillis = completed.expiresAtMillis ?: session.expiresAtMillis,
                items = media.map { GoogleSelection.Item(it.id, it.filename, video = it.isVideo) },
            )
            return Result(selection, skipped = picked.size - media.size)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { picker.deleteSession(tokens, session.id) }
            throw failure
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
