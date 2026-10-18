import Foundation

/// Passi del collegamento a Google Foto da mostrare sulla TV.
public enum ImportStep: Equatable, Sendable {
    /// `pageURL`: pagina della TV da inquadrare con il telefono; da lì si va da Google.
    case waitingForAuthorization(pageURL: String, expiresIn: Int)
    case waitingForPhotos(pickerURI: String)
}

/// Flusso completo di collegamento: autorizzazione dal telefono (QR verso la pagina della TV,
/// consenso su Google, codice di ritorno), sessione Picker, selezione ed elenco delle foto scelte.
/// Le foto non vengono scaricate: il risultato contiene solo identificativi e nomi, e la sessione
/// resta aperta perché serve a ottenere gli indirizzi aggiornati durante lo slideshow.
/// Se il flusso non arriva in fondo (errore, nessuna foto, annullamento) la sessione viene eliminata.
public final class GoogleImportFlow: @unchecked Sendable {
    public struct Result: Equatable, Sendable {
        public let selection: GoogleSelection
        /// Elementi scelti che non sono né foto né video.
        public let skipped: Int
    }

    private let auth: GoogleAuth
    private let picker: PhotosPicker
    /// Un consenso nuovo per ogni collegamento: la pagina resta accesa fino alla fine del flusso.
    private let newConsent: @Sendable () -> PhoneConsent

    public init(auth: GoogleAuth, picker: PhotosPicker, consent: @escaping @Sendable () -> PhoneConsent) {
        self.auth = auth
        self.picker = picker
        self.newConsent = consent
    }

    public func run(onStep: @escaping @Sendable (ImportStep) -> Void) async throws -> Result {
        let consent = newConsent()
        defer { consent.close() }
        return try await run(consent: consent, onStep: onStep)
    }

    private func run(consent: PhoneConsent, onStep: @escaping @Sendable (ImportStep) -> Void) async throws -> Result {
        var firstToken = try await authorize(consent, onStep)
        let session: PhotosPicker.Session
        do {
            session = try await picker.createSession(token: firstToken)
        } catch let failure as HTTPStatusError where failure.code == 401 {
            // Token rifiutato: prima si prova a rinnovarlo (le foto mostrate restano raggiungibili),
            // e solo se non si può si ripete il collegamento da capo, una sola volta.
            if let renewed = try await auth.validAccessToken(forceRefresh: true) {
                firstToken = renewed
            } else {
                await auth.disconnect()
                firstToken = try await authorize(consent, onStep)
            }
            session = try await picker.createSession(token: firstToken)
        }
        let auth = self.auth
        let tokens: TokenProvider = {
            guard let token = try await auth.validAccessToken() else { throw FotoFestaError.sessionExpired }
            return token
        }
        do {
            // Il telefono che ha appena dato il consenso può scegliere le foto senza un secondo QR.
            consent.publishPickerURL(session.pickerURI)
            onStep(.waitingForPhotos(pickerURI: session.pickerURI))
            let completed = try await picker.awaitSelection(token: tokens, session: session)
            let picked = try await picker.listPickedMedia(token: tokens, sessionID: completed.id)
            let media = picked.filter { $0.isPhoto || $0.isVideo }
            guard !media.isEmpty else { throw FotoFestaError.nothingSelected }
            let selection = GoogleSelection(
                sessionID: completed.id,
                expiresAt: completed.expiresAt ?? session.expiresAt,
                items: media.map { GoogleSelection.Item(id: $0.id, filename: $0.filename, isVideo: $0.isVideo) }
            )
            return Result(selection: selection, skipped: picked.count - media.count)
        } catch {
            // Anche se l'attività è stata annullata la sessione va chiusa: si usa un Task non legato.
            let picker = self.picker
            await Task.detached { await picker.deleteSession(token: tokens, sessionID: session.id) }.value
            throw error
        }
    }

    /// Restituisce un token valido o chiede il consenso dal telefono e scambia il codice.
    private func authorize(_ consent: PhoneConsent, _ onStep: @escaping @Sendable (ImportStep) -> Void) async throws -> String {
        if let token = try await auth.validAccessToken() { return token }
        let pending = try await auth.beginAuthorization()
        let auth = self.auth
        let code = try await consent.obtainCode(authorizationURL: { returnURL in auth.authorizationURL(pending, returnURL: returnURL) }, onStep: onStep)
        return try await auth.exchange(pending, code: code)
    }
}
