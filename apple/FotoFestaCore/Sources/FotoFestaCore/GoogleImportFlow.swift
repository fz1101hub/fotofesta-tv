import Foundation

/// Passi del collegamento a Google Foto da mostrare sulla TV.
public enum ImportStep: Equatable, Sendable {
    case waitingForAuthorization(verificationURL: String, userCode: String, expiresIn: Int)
    case waitingForPhotos(pickerURI: String)
    case downloading(completed: Int, total: Int)
}

/// Flusso completo: autorizzazione, sessione Picker, selezione, download, chiusura della sessione.
public final class GoogleImportFlow: @unchecked Sendable {
    public struct Result: Equatable, Sendable {
        public let photos: [Photo]
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
            guard !picked.isEmpty else { throw FotoFestaError.nothingSelected }
            onStep(.downloading(completed: 0, total: picked.count))
            let photos = try await picker.download(token: tokens, items: picked) { done, total in
                onStep(.downloading(completed: done, total: total))
            }
            guard !photos.isEmpty else { throw FotoFestaError.downloadFailed }
            await picker.deleteSession(token: tokens, sessionID: session.id)
            return Result(photos: photos, skipped: picked.count - photos.count)
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
