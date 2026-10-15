import Foundation

/// Indirizzi aggiornati delle foto scelte in Google Foto. Gli indirizzi restituiti dalla Picker API
/// valgono circa 60 minuti: si rinnovano chiedendo di nuovo l'elenco della sessione, che resta valida
/// fino alla scadenza decisa da Google. Nessuna immagine viene salvata sul dispositivo.
public actor GooglePhotosStream {
    /// Indirizzo da cui scaricare i byte della foto e token per l'intestazione Authorization.
    public struct Access: Equatable, Sendable {
        public let baseURL: String
        public let accessToken: String
    }

    /// Perché le foto di una selezione non sono più raggiungibili.
    public enum GoneReason: Equatable, Sendable {
        /// La sessione Picker è scaduta o è stata eliminata: le foto vanno scelte di nuovo.
        case selectionExpired
        /// L'accesso a Google è stato revocato o è scaduto: serve un nuovo collegamento.
        case accessLost
    }

    /// Margine rispetto ai 60 minuti di validità degli indirizzi Google.
    public static let urlLifetime: TimeInterval = 50 * 60
    static let minimumRefreshInterval: TimeInterval = 60

    private let picker: PhotosPicker
    private let auth: GoogleDeviceAuth
    private let selections: GoogleSelectionStore
    private let now: @Sendable () -> Date
    private var urls: [String: String] = [:]
    private var urlsSessionID: String?
    private var urlsFetchedAt = Date.distantPast
    private var lastToken: String?
    /// Elenco in corso, condiviso dalle richieste della stessa sessione.
    private var refreshing: (sessionID: String, task: Task<[String: String], Error>)?
    private var goneSessionID: String?
    private var onSelectionGone: (@Sendable (String, GoneReason) -> Void)?

    public init(picker: PhotosPicker, auth: GoogleDeviceAuth, selections: GoogleSelectionStore, now: @escaping @Sendable () -> Date = { Date() }) {
        self.picker = picker
        self.auth = auth
        self.selections = selections
        self.now = now
    }

    /// Chiamato con la sessione persa e il motivo. La selezione è già stata tolta dall'archivio:
    /// l'app deve solo aggiornare quello che mostra.
    public func setOnSelectionGone(_ handler: @escaping @Sendable (String, GoneReason) -> Void) {
        onSelectionGone = handler
    }

    /// Indirizzo valido per `mediaID`. Con `failed` (l'accesso che ha appena ricevuto 401/403 sul
    /// download) rinnova token ed elenco, ma solo se nel frattempo nessun'altra richiesta l'ha già
    /// fatto: le foto caricate in parallelo condividono un solo rinnovo.
    public func access(mediaID: String, failed: Access? = nil) async throws -> Access {
        guard let selection = selections.current(), selection.sessionID != goneSessionID else {
            throw FotoFestaError.selectionGone
        }
        var token = try await currentToken(selection, forceRefresh: failed != nil && failed?.accessToken == lastToken)
        let age = now().timeIntervalSince(urlsFetchedAt)
        let needsRefresh = urlsSessionID != selection.sessionID
            || age >= Self.urlLifetime
            || (failed != nil && urls[mediaID] == failed?.baseURL)
            // Una foto mancante giustifica un nuovo elenco, ma non a ogni richiesta.
            || (urls[mediaID] == nil && age >= Self.minimumRefreshInterval)
        if needsRefresh {
            let listed: [String: String]
            do {
                listed = try await list(selection, token: token)
            } catch let failure as HTTPStatusError where failure.code == 401 {
                // Token rifiutato prima della scadenza prevista: si rinnova una volta.
                token = try await currentToken(selection, forceRefresh: true)
                listed = try await list(selection, token: token)
            }
            // Una selezione sostituita nel frattempo non deve ricevere l'elenco della precedente.
            guard selections.current()?.sessionID == selection.sessionID else { throw FotoFestaError.photoUnavailable }
            urls = listed
            urlsSessionID = selection.sessionID
            urlsFetchedAt = now()
        }
        guard let baseURL = urls[mediaID] else { throw FotoFestaError.photoUnavailable }
        return Access(baseURL: baseURL, accessToken: token)
    }

    /// Dimentica indirizzi e stato: da chiamare quando la selezione cambia o viene cancellata.
    public func reset() {
        urls = [:]
        urlsSessionID = nil
        urlsFetchedAt = .distantPast
        lastToken = nil
        refreshing = nil
        goneSessionID = nil
    }

    private func currentToken(_ selection: GoogleSelection, forceRefresh: Bool) async throws -> String {
        guard let token = try await auth.validAccessToken(forceRefresh: forceRefresh) else {
            // Refresh token revocato o scaduto: senza un nuovo collegamento nessuna foto è raggiungibile.
            markGone(selection, reason: .accessLost)
            throw FotoFestaError.sessionExpired
        }
        lastToken = token
        return token
    }

    /// Elenco della sessione, condiviso dalle richieste concorrenti. 404 indica una sessione che non
    /// esiste più; 400 e 403 possono avere altre cause, quindi si chiede lo stato della sessione.
    private func list(_ selection: GoogleSelection, token: String) async throws -> [String: String] {
        let task: Task<[String: String], Error>
        if let refreshing, refreshing.sessionID == selection.sessionID {
            task = refreshing.task
        } else {
            let picker = self.picker
            let sessionID = selection.sessionID
            task = Task {
                let listed = try await picker.listPickedMedia(token: { token }, sessionID: sessionID)
                return Dictionary(listed.map { ($0.id, $0.baseURL) }, uniquingKeysWith: { first, _ in first })
            }
            refreshing = (selection.sessionID, task)
        }
        defer { if refreshing?.task == task { refreshing = nil } }
        do {
            return try await task.value
        } catch let failure as HTTPStatusError where [400, 403, 404].contains(failure.code) {
            var gone = failure.code == 404
            if !gone { gone = await sessionIsGone(selection, token: token) }
            guard gone else { throw failure }
            markGone(selection, reason: .selectionExpired)
            throw FotoFestaError.selectionGone
        }
    }

    private func sessionIsGone(_ selection: GoogleSelection, token: String) async -> Bool {
        do {
            let session = try await picker.session(token: { token }, id: selection.sessionID)
            if !session.mediaItemsSet { return true }
            if let expiresAt = session.expiresAt { return expiresAt <= now() }
            return false
        } catch let failure as HTTPStatusError {
            return failure.code == 404 || failure.code == 403
        } catch {
            return false
        }
    }

    /// Segna la selezione come persa e la toglie dall'archivio, ma solo se è ancora quella corrente:
    /// la sessione di una selezione appena sostituita viene eliminata, e le sue foto ancora in
    /// caricamento non devono far scartare quella nuova.
    private func markGone(_ selection: GoogleSelection, reason: GoneReason) {
        guard selections.clearIfCurrent(sessionID: selection.sessionID) else { return }
        goneSessionID = selection.sessionID
        onSelectionGone?(selection.sessionID, reason)
    }
}
