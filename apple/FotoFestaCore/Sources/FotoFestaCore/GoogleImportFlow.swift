import Foundation

/// Passi del collegamento a Google Foto da mostrare sulla TV.
public enum ImportStep: Equatable, Sendable {
    case waitingForAuthorization(verificationURL: String, userCode: String, expiresIn: Int)
    case waitingForPhotos(pickerURI: String)
}

/// Flusso completo di collegamento: autorizzazione, sessione Picker, selezione ed elenco delle foto
/// scelte. Le foto non vengono scaricate: il risultato contiene solo identificativi e nomi, e la
/// sessione resta aperta perché serve a ottenere gli indirizzi aggiornati durante lo slideshow.
/// Se il flusso non arriva in fondo (errore, nessuna foto, annullamento) la sessione viene eliminata.
public final class GoogleImportFlow: @unchecked Sendable {
    public struct Result: Equatable, Sendable {
        public let selection: GoogleSelection
        /// Elementi scelti che non sono foto (ad esempio i video).
        public let skipped: Int
    }

    private let auth: GoogleDeviceAuth
    private let picker: PhotosPicker

    public init(auth: GoogleDeviceAuth, picker: PhotosPicker) {
        self.auth = auth
        self.picker = picker
    }

    public func run(onStep: @escaping @Sendable (ImportStep) -> Void) async throws -> Result {
        var firstToken = try await authorize(onStep)
        let session: PhotosPicker.Session
        do {
            session = try await picker.createSession(token: firstToken)
        } catch let failure as HTTPStatusError where failure.code == 401 {
            // Token revocato dall'utente: si ripete il collegamento da capo una sola volta.
            await auth.disconnect()
            firstToken = try await authorize(onStep)
            session = try await picker.createSession(token: firstToken)
        }
        let auth = self.auth
        let tokens: TokenProvider = {
            guard let token = try await auth.validAccessToken() else { throw FotoFestaError.sessionExpired }
            return token
        }
        do {
            onStep(.waitingForPhotos(pickerURI: session.pickerURI))
            let completed = try await picker.awaitSelection(token: tokens, session: session)
            let picked = try await picker.listPickedMedia(token: tokens, sessionID: completed.id)
            let photos = picked.filter(\.isPhoto)
            guard !photos.isEmpty else { throw FotoFestaError.nothingSelected }
            let selection = GoogleSelection(
                sessionID: completed.id,
                expiresAt: completed.expiresAt ?? session.expiresAt,
                items: photos.map { GoogleSelection.Item(id: $0.id, filename: $0.filename) }
            )
            return Result(selection: selection, skipped: picked.count - photos.count)
        } catch {
            // Anche se l'attività è stata annullata la sessione va chiusa: si usa un Task non legato.
            let picker = self.picker
            await Task.detached { await picker.deleteSession(token: tokens, sessionID: session.id) }.value
            throw error
        }
    }

    private func authorize(_ onStep: @escaping @Sendable (ImportStep) -> Void) async throws -> String {
        if let token = try await auth.validAccessToken() { return token }
        let code = try await auth.requestDeviceCode()
        onStep(.waitingForAuthorization(verificationURL: code.verificationURL, userCode: code.userCode, expiresIn: code.expiresIn))
        return try await auth.pollForToken(code)
    }
}
