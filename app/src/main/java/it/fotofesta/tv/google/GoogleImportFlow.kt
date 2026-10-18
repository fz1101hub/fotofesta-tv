package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Flusso completo di collegamento a Google Foto: autorizzazione dal telefono (QR verso la pagina
 * della TV, consenso su Google, codice di ritorno), sessione Picker, attesa della selezione ed
 * elenco delle foto scelte. Le foto non vengono scaricate: il risultato contiene solo identificativi
 * e nomi, e la sessione resta aperta perché serve a ottenere gli indirizzi aggiornati durante lo
 * slideshow.
 *
 * Gli stati intermedi da mostrare sulla TV vengono notificati con `onState`. Se il flusso non
 * arriva in fondo (errore, nessuna foto, annullamento) la sessione Picker viene eliminata.
 */
class GoogleImportFlow(
    private val auth: GoogleAuthRepository,
    private val picker: GooglePhotosPickerRepository,
    /** Un consenso nuovo per ogni collegamento: la pagina resta accesa fino alla fine del flusso. */
    private val newConsent: () -> PhoneConsent,
) {
    /** [skipped] conta gli elementi scelti che non sono né foto né video. */
    data class Result(val selection: GoogleSelection, val skipped: Int)

    suspend fun run(onState: (GoogleConnectState) -> Unit): Result {
        val consent = newConsent()
        try {
            return run(consent, onState)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { consent.close() }
        }
    }

    private suspend fun run(consent: PhoneConsent, onState: (GoogleConnectState) -> Unit): Result {
        var token = authorize(consent, onState)
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
                token = authorize(consent, onState)
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
            // Il telefono che ha appena dato il consenso può scegliere le foto senza un secondo QR.
            consent.publishPickerUrl(session.pickerUri)
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

    /** Restituisce un token valido o chiede il consenso dal telefono e scambia il codice. */
    private suspend fun authorize(consent: PhoneConsent, onState: (GoogleConnectState) -> Unit): String {
        auth.validAccessToken()?.let { return it }
        val pending = auth.beginAuthorization()
        val code = consent.obtainCode({ returnUrl -> auth.authorizationUrl(pending, returnUrl) }, onState)
        return auth.exchangeCode(pending, code).accessToken
    }
}
