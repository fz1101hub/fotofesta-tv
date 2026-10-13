import Foundation

/// Passi del collegamento a Google Foto da mostrare sulla TV.
public enum ImportStep: Equatable, Sendable {
    case waitingForAuthorization(verificationURL: String, userCode: String, expiresIn: Int)
    case waitingForPhotos(pickerURI: String)
    case downloading(completed: Int, total: Int)
}

/// Flusso completo: autorizzazione, sessione Picker, selezione, download, chiusura della sessione.
public final class GoogleImportFlow {
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
        var token = try await authorize(onStep)
        let session: PhotosPicker.Session
        do {
            session = try await picker.createSession(token: token)
        } catch let failure as HTTPStatusError where failure.code == 401 {
            // Token revocato dall'utente: si ripete il collegamento da capo una sola volta.
            auth.disconnect()
            token = try await authorize(onStep)
            session = try await picker.createSession(token: token)
        }
        let usedToken = token
        do {
            onStep(.waitingForPhotos(pickerURI: session.pickerURI))
            let completed = try await picker.awaitSelection(token: usedToken, session: session)
            let picked = try await picker.listPickedMedia(token: usedToken, sessionID: completed.id)
            guard !picked.isEmpty else { throw FotoFestaError.nothingSelected }
            onStep(.downloading(completed: 0, total: picked.count))
            let photos = try await picker.download(token: usedToken, items: picked) { done, total in
                onStep(.downloading(completed: done, total: total))
            }
            guard !photos.isEmpty else { throw FotoFestaError.downloadFailed }
            await picker.deleteSession(token: usedToken, sessionID: session.id)
            return Result(photos: photos, skipped: picked.count - photos.count)
        } catch {
            // Anche se l'attività è stata annullata la sessione va chiusa: si usa un Task separato.
            await Task.detached { await self.picker.deleteSession(token: usedToken, sessionID: session.id) }.value
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
