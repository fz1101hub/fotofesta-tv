import CryptoKit
import Foundation

/// Archivio dei token OAuth. Nell'app è il Portachiavi; nei test un oggetto in memoria.
public protocol TokenStore: AnyObject {
    var accessToken: String? { get set }
    var refreshToken: String? { get set }
    var expiresAt: Date? { get set }
    func clear()
}

/// OAuth 2.0 con il consenso dato dal telefono (stesso flusso dell'app Android): il flusso per TV di
/// Google non ammette lo scope del Photos Picker, quindi la Apple TV mostra un QR con una sua pagina,
/// il telefono va da Google (flusso "authorization code" con PKCE) e torna, attraverso la pagina del
/// sito indicata come `redirect_uri`, con un codice che la Apple TV scambia con i token.
/// È un actor: i download paralleli chiedono il token insieme e il rinnovo non deve sovrapporsi.
public actor GoogleAuth {
    public static let scope = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    /// Un'autorizzazione iniziata: il segreto PKCE resta sulla TV, al telefono va solo la sua impronta.
    public struct Pending: Equatable, Sendable {
        public let verifier: String
        public let challenge: String
    }

    private let http: HTTPClient
    private let tokens: TokenStore
    private let clientID: String
    private let clientSecret: String
    /// Pagina del sito che riceve il codice da Google e lo rimanda alla TV (`site/google/`).
    private let redirectURI: String
    private let tokenURL: URL
    private let revokeURL: URL
    private let authorizationBase: URL
    private let now: @Sendable () -> Date

    public init(
        http: HTTPClient,
        tokens: TokenStore,
        clientID: String,
        clientSecret: String,
        redirectURI: String,
        oauthBase: URL = URL(string: "https://oauth2.googleapis.com/")!,
        authorizationBase: URL = URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.http = http
        self.tokens = tokens
        self.clientID = clientID
        self.clientSecret = clientSecret
        self.redirectURI = redirectURI
        self.tokenURL = oauthBase.appendingPathComponent("token")
        self.revokeURL = oauthBase.appendingPathComponent("revoke")
        self.authorizationBase = authorizationBase
        self.now = now
    }

    public nonisolated var isConfigured: Bool { !clientID.isEmpty && !clientSecret.isEmpty && !redirectURI.isEmpty }

    public func beginAuthorization() throws -> Pending {
        try requireConfigured()
        let bytes = (0..<32).map { _ in UInt8.random(in: .min ... .max) }
        let verifier = Self.base64URL(Data(bytes))
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        return Pending(verifier: verifier, challenge: challenge)
    }

    /// Indirizzo di Google da aprire sul telefono. `returnURL` è la pagina della TV a cui il sito
    /// rimanda il codice: viaggia nello `state`, che Google restituisce intatto.
    public nonisolated func authorizationURL(_ pending: Pending, returnURL: String) -> String {
        var components = URLComponents(url: authorizationBase, resolvingAgainstBaseURL: false) ?? URLComponents()
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: Self.scope),
            URLQueryItem(name: "code_challenge", value: pending.challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: Self.encodeState(returnURL)),
            // Refresh token a ogni collegamento, anche se l'utente aveva già dato il consenso.
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
        ]
        return components.url?.absoluteString ?? authorizationBase.absoluteString
    }

    /// Scambia il codice arrivato dal telefono con i token e li salva.
    public func exchange(_ pending: Pending, code: String) async throws -> String {
        try requireConfigured()
        let request = formRequest(url: tokenURL, fields: [
            ("client_id", clientID),
            ("client_secret", clientSecret),
            ("code", code),
            ("code_verifier", pending.verifier),
            ("redirect_uri", redirectURI),
            ("grant_type", "authorization_code"),
        ])
        do {
            return persist(try await http.json(TokenResponse.self, for: request))
        } catch let failure as HTTPStatusError where failure.oauthError == "invalid_grant" {
            // Codice già usato o scaduto (vale pochi minuti): si ricomincia dal QR.
            throw FotoFestaError.codeExpired
        }
    }

    /// Token valido, rinnovato se necessario. Con refresh token revocato cancella tutto e restituisce nil.
    /// Le chiamate concorrenti durante un rinnovo attendono lo stesso rinnovo (l'actor è rientrante).
    /// Con `forceRefresh` il token viene rinnovato anche se non è scaduto (ad esempio dopo un 401).
    public func validAccessToken(forceRefresh: Bool = false) async throws -> String? {
        if !forceRefresh, let token = tokens.accessToken, let expiresAt = tokens.expiresAt, expiresAt > now().addingTimeInterval(60) {
            return token
        }
        if let refreshing { return try await refreshing.value }
        let task = Task { try await self.refreshAccessToken() }
        refreshing = task
        defer { refreshing = nil }
        return try await task.value
    }

    private var refreshing: Task<String?, Error>?

    private func refreshAccessToken() async throws -> String? {
        guard let refresh = tokens.refreshToken else { return nil }
        try requireConfigured()
        // Un "Disconnetti" arrivato durante la richiesta invalida il risultato: il token non va salvato.
        let generation = self.generation
        let request = formRequest(url: tokenURL, fields: [
            ("client_id", clientID),
            ("client_secret", clientSecret),
            ("refresh_token", refresh),
            ("grant_type", "refresh_token"),
        ])
        do {
            let response = try await http.json(TokenResponse.self, for: request)
            guard generation == self.generation else { return nil }
            return persist(response)
        } catch let failure as HTTPStatusError where failure.oauthError == "invalid_grant" || failure.code == 401 {
            disconnect()
            return nil
        }
    }

    public func disconnect() {
        generation += 1
        tokens.clear()
    }

    /// Cresce a ogni disconnessione: i rinnovi partiti prima non salvano il token ottenuto.
    private var generation = 0

    /// Revoca l'accesso anche sui server Google (best effort) e cancella i token locali.
    public func revokeAndDisconnect() async {
        // Prima si cancellano i token locali (subito, anche se la rete è lenta), poi si revoca.
        let token = tokens.refreshToken ?? tokens.accessToken
        disconnect()
        if let token {
            _ = try? await http.send(formRequest(url: revokeURL, fields: [("token", token)]))
        }
    }

    private func persist(_ response: TokenResponse) -> String {
        tokens.accessToken = response.access_token
        if let refresh = response.refresh_token, !refresh.isEmpty { tokens.refreshToken = refresh }
        tokens.expiresAt = now().addingTimeInterval(TimeInterval(response.expires_in ?? 3_600))
        return response.access_token
    }

    private func requireConfigured() throws {
        guard isConfigured else { throw FotoFestaError.notConfigured }
    }

    /// Lo `state` porta l'indirizzo di ritorno sulla TV, in base64url come lo legge la pagina del sito.
    public nonisolated static func encodeState(_ returnURL: String) -> String {
        base64URL(Data(returnURL.utf8))
    }

    public nonisolated static func decodeState(_ state: String) -> String? {
        var base64 = state.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        return Data(base64Encoded: base64).flatMap { String(data: $0, encoding: .utf8) }
    }

    private nonisolated static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private struct TokenResponse: Decodable {
        let access_token: String
        let refresh_token: String?
        let expires_in: Int?
    }
}
