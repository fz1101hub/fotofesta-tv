package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Indirizzi aggiornati delle foto scelte in Google Foto. Gli indirizzi restituiti dalla Picker API
 * valgono circa 60 minuti: si rinnovano chiedendo di nuovo l'elenco della sessione, che resta
 * valida fino alla scadenza indicata da Google. Nessuna immagine viene salvata sulla TV.
 */
class GooglePhotosStream(
    private val picker: GooglePhotosPickerRepository,
    private val auth: GoogleDeviceAuthRepository,
    private val selections: GoogleSelectionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Indirizzo da cui scaricare i byte della foto e token da mettere nell'intestazione Authorization. */
    data class Access(val baseUrl: String, val accessToken: String)

    private val lock = Mutex()

    // Scritti sotto [lock] da access(); reset() li azzera anche senza lock: al massimo un elenco
    // appena letto viene scartato e richiesto di nuovo.
    @Volatile
    private var urls: Map<String, String> = emptyMap()

    @Volatile
    private var urlsSessionId: String? = null

    @Volatile
    private var urlsFetchedAt = 0L

    private val _selectionGone = MutableStateFlow(false)

    /** Diventa `true` quando la sessione non è più accessibile: le foto vanno scelte di nuovo. */
    val selectionGone: StateFlow<Boolean> = _selectionGone.asStateFlow()

    /**
     * Indirizzo valido per [mediaId]. Con [stale] (dopo un 401/403 sul download) rinnova token ed
     * elenco anche se non sono ancora scaduti. Le chiamate concorrenti condividono un solo rinnovo.
     */
    suspend fun access(mediaId: String, stale: Boolean = false): Access = lock.withLock {
        val selection = selections.current() ?: throw UserFacingException(R.string.error_google_selection_gone)
        if (selection.isExpired(clock())) {
            markGone(selection)
            throw UserFacingException(R.string.error_google_selection_gone)
        }
        val token = auth.validAccessToken(forceRefresh = stale)
            ?: throw UserFacingException(R.string.error_google_session_expired)
        val age = clock() - urlsFetchedAt
        val refresh = stale ||
            urlsSessionId != selection.sessionId ||
            age >= URL_LIFETIME_MILLIS ||
            // Una foto mancante giustifica un nuovo elenco, ma non a ogni richiesta.
            (mediaId !in urls && age >= MIN_REFRESH_INTERVAL_MILLIS)
        if (refresh) {
            val listed = try {
                picker.listPickedMedia({ token }, selection.sessionId)
            } catch (failure: HttpStatusException) {
                // Sessione scaduta o eliminata: Google risponde 400, 403 o 404.
                if (failure.code in SESSION_GONE_CODES) {
                    markGone(selection)
                    throw UserFacingException(R.string.error_google_selection_gone)
                }
                throw failure
            }
            urls = listed.associate { it.id to it.baseUrl }
            urlsSessionId = selection.sessionId
            urlsFetchedAt = clock()
        }
        val baseUrl = urls[mediaId] ?: throw UserFacingException(R.string.photo_unavailable)
        Access(baseUrl, token)
    }

    /**
     * Segnala la scadenza solo se riguarda ancora la selezione corrente: la sessione di una
     * selezione appena sostituita viene eliminata, e le sue foto ancora in caricamento non devono
     * far scartare quella nuova.
     */
    private fun markGone(selection: GoogleSelection) {
        if (selections.current()?.sessionId == selection.sessionId) _selectionGone.value = true
    }

    /** Dimentica indirizzi e stato: da chiamare quando la selezione cambia o viene cancellata. */
    fun reset() {
        urls = emptyMap()
        urlsSessionId = null
        urlsFetchedAt = 0L
        _selectionGone.value = false
    }

    companion object {
        /** Margine rispetto ai 60 minuti di validità degli indirizzi Google. */
        const val URL_LIFETIME_MILLIS = 50L * 60 * 1_000
        const val MIN_REFRESH_INTERVAL_MILLIS = 60L * 1_000
        private val SESSION_GONE_CODES = setOf(400, 403, 404)

        /**
         * Indirizzo dei byte della foto ridotta da Google a [width]×[height]. Con [crop] l'immagine
         * riempie esattamente il riquadro; senza, ci sta dentro mantenendo le proporzioni.
         */
        fun sizedUrl(baseUrl: String, width: Int, height: Int, crop: Boolean): String =
            "$baseUrl=w$width-h$height" + if (crop) "-c" else ""
    }
}
