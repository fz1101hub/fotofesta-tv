import Foundation

/// Indirizzi aggiornati delle foto scelte in Google Foto. Gli indirizzi restituiti dalla Picker API
/// valgono circa 60 minuti: si rinnovano chiedendo di nuovo l'elenco della sessione, che resta valida
/// fino alla scadenza indicata da Google. Nessuna immagine viene salvata sul dispositivo.
public actor GooglePhotosStream {
    /// Indirizzo da cui scaricare i byte della foto e token per l'intestazione Authorization.
    public struct Access: Equatable, Sendable {
        public let baseURL: String
        public let accessToken: String
    }

    /// Margine rispetto ai 60 minuti di validità degli indirizzi Google.
    public static let urlLifetime: TimeInterval = 50 * 60
    static let minimumRefreshInterval: TimeInterval = 60
    private static let sessionGoneCodes: Set<Int> = [400, 403, 404]

    private let picker: PhotosPicker
    private let auth: GoogleDeviceAuth
    private let selections: GoogleSelectionStore
    private let now: @Sendable () -> Date
    private var urls: [String: String] = [:]
    private var urlsSessionID: String?
    private var urlsFetchedAt = Date.distantPast
    private var refreshing: Task<[String: String], Error>?
    private var onSelectionGone: (@Sendable (String) -> Void)?

    public init(picker: PhotosPicker, auth: GoogleDeviceAuth, selections: GoogleSelectionStore, now: @escaping @Sendable () -> Date = Date.init) {
        self.picker = picker
        self.auth = auth
        self.selections = selections
        self.now = now
    }

    /// Chiamato con l'identificativo della sessione quando non è più accessibile: le foto vanno scelte di nuovo.
    public func setOnSelectionGone(_ handler: @escaping @Sendable (String) -> Void) {
        onSelectionGone = handler
    }

    /// Indirizzo valido per `mediaID`. Con `stale` (dopo un 401/403 sul download) rinnova token ed
    /// elenco anche se non sono scaduti. Le richieste concorrenti condividono un solo rinnovo.
    public func access(mediaID: String, stale: Bool = false) async throws -> Access {
        guard let selection = selections.current() else { throw FotoFestaError.selectionGone }
        if selection.isExpired(at: now()) {
            markGone(selection)
            throw FotoFestaError.selectionGone
        }
        guard let token = try await auth.validAccessToken(forceRefresh: stale) else { throw FotoFestaError.sessionExpired }
        let age = now().timeIntervalSince(urlsFetchedAt)
        let needsRefresh = stale
            || urlsSessionID != selection.sessionID
            || age >= Self.urlLifetime
            // Una foto mancante giustifica un nuovo elenco, ma non a ogni richiesta.
            || (urls[mediaID] == nil && age >= Self.minimumRefreshInterval)
        if needsRefresh {
            let task: Task<[String: String], Error>
            if let refreshing {
                task = refreshing
            } else {
                let picker = self.picker
                let sessionID = selection.sessionID
                task = Task {
                    let listed = try await picker.listPickedMedia(token: { token }, sessionID: sessionID)
                    return Dictionary(listed.map { ($0.id, $0.baseURL) }, uniquingKeysWith: { first, _ in first })
                }
                refreshing = task
            }
            do {
                let listed = try await task.value
                if refreshing == task { refreshing = nil }
                urls = listed
                urlsSessionID = selection.sessionID
                urlsFetchedAt = now()
            } catch let failure as HTTPStatusError where Self.sessionGoneCodes.contains(failure.code) {
                if refreshing == task { refreshing = nil }
                markGone(selection)
                throw FotoFestaError.selectionGone
            } catch {
                if refreshing == task { refreshing = nil }
                throw error
            }
        }
        guard let baseURL = urls[mediaID] else { throw FotoFestaError.photoUnavailable }
        return Access(baseURL: baseURL, accessToken: token)
    }

    /// Dimentica indirizzi e stato: da chiamare quando la selezione cambia o viene cancellata.
    public func reset() {
        urls = [:]
        urlsSessionID = nil
        urlsFetchedAt = .distantPast
        refreshing = nil
    }

    /// Segnala la scadenza solo se riguarda ancora la selezione corrente: la sessione di una selezione
    /// appena sostituita viene eliminata, e le sue foto ancora in caricamento non devono scartare quella nuova.
    private func markGone(_ selection: GoogleSelection) {
        guard selections.current()?.sessionID == selection.sessionID else { return }
        onSelectionGone?(selection.sessionID)
    }
}
