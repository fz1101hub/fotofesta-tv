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
 * valida fino alla scadenza decisa da Google. Nessuna immagine viene salvata sulla TV.
 */
class GooglePhotosStream(
    private val picker: GooglePhotosPickerRepository,
    private val auth: GoogleAuthRepository,
    private val selections: GoogleSelectionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Indirizzo da cui scaricare i byte della foto e token da mettere nell'intestazione Authorization. */
    data class Access(val baseUrl: String, val accessToken: String)

    /** Perché le foto di una selezione non sono più raggiungibili. */
    enum class GoneReason {
        /** La sessione Picker è scaduta o è stata eliminata: le foto vanno scelte di nuovo. */
        SELECTION_EXPIRED,

        /** L'accesso a Google è stato revocato o è scaduto: serve un nuovo collegamento. */
        ACCESS_LOST,
    }

    data class Gone(val sessionId: String, val reason: GoneReason)

    private val lock = Mutex()

    // Scritti sotto [lock] da access(); reset() li azzera anche senza lock: al massimo un elenco
    // appena letto viene scartato e richiesto di nuovo.
    @Volatile
    private var urls: Map<String, String> = emptyMap()

    @Volatile
    private var urlsSessionId: String? = null

    @Volatile
    private var urlsFetchedAt = 0L

    @Volatile
    private var lastToken: String? = null

    /** Ultimo elenco fallito senza che la sessione risultasse persa: si riprova dopo un minuto. */
    @Volatile
    private var listFailedAt: Long? = null

    private val _gone = MutableStateFlow<Gone?>(null)

    /**
     * Selezione non più raggiungibile, con la sessione a cui si riferisce. La selezione viene già
     * tolta dall'archivio qui: app e salvaschermo devono solo aggiornare quello che mostrano.
     */
    val gone: StateFlow<Gone?> = _gone.asStateFlow()

    /**
     * Indirizzo valido per [mediaId]. Con [failed] (l'accesso che ha appena ricevuto 401/403 sul
     * download) rinnova token ed elenco, ma solo se nel frattempo nessun'altra richiesta l'ha già
     * fatto: le foto caricate in parallelo condividono un solo rinnovo.
     */
    suspend fun access(mediaId: String, failed: Access? = null): Access = lock.withLock {
        val selection = selections.current() ?: throw UserFacingException(R.string.error_google_selection_gone)
        _gone.value?.takeIf { it.sessionId == selection.sessionId }?.let { throw goneException(it.reason) }

        var token = currentToken(selection, forceRefresh = failed != null && failed.accessToken == lastToken)
        val age = clock() - urlsFetchedAt
        val refresh = urlsSessionId != selection.sessionId ||
            age >= URL_LIFETIME_MILLIS ||
            (failed != null && urls[mediaId] == failed.baseUrl) ||
            // Una foto mancante giustifica un nuovo elenco, ma non a ogni richiesta.
            (mediaId !in urls && age >= MIN_REFRESH_INTERVAL_MILLIS)
        if (refresh) {
            listFailedAt?.let { failedAt ->
                // Google ha appena rifiutato l'elenco: niente nuove richieste a ogni foto per un minuto.
                if (failed == null && clock() - failedAt < MIN_REFRESH_INTERVAL_MILLIS) {
                    throw UserFacingException(R.string.photo_unavailable)
                }
            }
            val listed = try {
                listOrConfirmGone(selection, token)
            } catch (unauthorized: HttpStatusException) {
                if (unauthorized.code != 401) throw unauthorized
                // Token rifiutato prima della scadenza prevista: si rinnova una volta.
                token = currentToken(selection, forceRefresh = true)
                listOrConfirmGone(selection, token)
            }
            listFailedAt = null
            urls = listed.associate { it.id to it.baseUrl }
            urlsSessionId = selection.sessionId
            urlsFetchedAt = clock()
        }
        val baseUrl = urls[mediaId] ?: throw UserFacingException(R.string.photo_unavailable)
        Access(baseUrl, token)
    }

    /** Aspetta che finiscano le richieste in corso (e gli eventuali rinnovi del token). */
    suspend fun drain() = lock.withLock { }

    /** Dimentica indirizzi e stato: da chiamare quando la selezione cambia o viene cancellata. */
    fun reset() {
        urls = emptyMap()
        urlsSessionId = null
        urlsFetchedAt = 0L
        lastToken = null
        listFailedAt = null
        _gone.value = null
    }

    private suspend fun currentToken(selection: GoogleSelection, forceRefresh: Boolean): String {
        val token = auth.validAccessToken(forceRefresh)
        if (token == null) {
            // Refresh token revocato o scaduto: senza un nuovo collegamento nessuna foto è raggiungibile.
            markGone(selection, GoneReason.ACCESS_LOST)
            throw goneException(GoneReason.ACCESS_LOST)
        }
        lastToken = token
        return token
    }

    /**
     * Elenco aggiornato della sessione. 404 indica una sessione che non esiste più; 400 e 403 possono
     * avere anche altre cause, quindi si chiede lo stato della sessione prima di considerarla persa.
     */
    private suspend fun listOrConfirmGone(selection: GoogleSelection, token: String): List<GooglePhotosPickerRepository.PickedMedia> =
        try {
            picker.listPickedMedia({ token }, selection.sessionId)
        } catch (failure: HttpStatusException) {
            val gone = when (failure.code) {
                404 -> true
                400, 403 -> sessionIsGone(selection, token)
                else -> false
            }
            if (!gone) {
                listFailedAt = clock()
                throw failure
            }
            markGone(selection, GoneReason.SELECTION_EXPIRED)
            throw goneException(GoneReason.SELECTION_EXPIRED)
        }

    /**
     * Conferma con Google che la sessione è persa. Un 404 basta; un 400/403 anche su questa richiesta
     * vale solo se la scadenza indicata da Google alla scelta delle foto è passata: un 403 dovuto
     * ad altro (API disattivata, quota) non deve cancellare una selezione valida.
     */
    private suspend fun sessionIsGone(selection: GoogleSelection, token: String): Boolean = try {
        val session = picker.getSession({ token }, selection.sessionId)
        !session.mediaItemsSet || (session.expiresAtMillis != null && session.expiresAtMillis <= clock())
    } catch (failure: HttpStatusException) {
        when (failure.code) {
            404 -> true
            400, 403 -> selection.isExpired(clock())
            else -> false
        }
    }

    /**
     * Segna la selezione come persa e la toglie dall'archivio, ma solo se è ancora quella corrente:
     * la sessione di una selezione appena sostituita viene eliminata, e le sue foto ancora in
     * caricamento non devono far scartare quella nuova.
     */
    private fun markGone(selection: GoogleSelection, reason: GoneReason) {
        if (selections.clearIfCurrent(selection.sessionId)) _gone.value = Gone(selection.sessionId, reason)
    }

    private fun goneException(reason: GoneReason) = UserFacingException(
        if (reason == GoneReason.ACCESS_LOST) R.string.error_google_session_expired else R.string.error_google_selection_gone
    )

    companion object {
        /** Margine rispetto ai 60 minuti di validità degli indirizzi Google. */
        const val URL_LIFETIME_MILLIS = 50L * 60 * 1_000
        const val MIN_REFRESH_INTERVAL_MILLIS = 60L * 1_000

        /**
         * Indirizzo dei byte della foto ridotta da Google a [width]×[height]. Con [crop] l'immagine
         * riempie esattamente il riquadro; senza, ci sta dentro mantenendo le proporzioni.
         */
        fun sizedUrl(baseUrl: String, width: Int, height: Int, crop: Boolean): String =
            "$baseUrl=w$width-h$height" + if (crop) "-c" else ""
    }
}
