import Foundation

/// Archivio dei token OAuth. Nell'app è il Portachiavi; nei test un oggetto in memoria.
public protocol TokenStore: AnyObject {
    var accessToken: String? { get set }
    var refreshToken: String? { get set }
    var expiresAt: Date? { get set }
    func clear()
}

/// OAuth 2.0 per TV e dispositivi a input limitato (stesso flusso dell'app Android).
public final class GoogleDeviceAuth {
    public static let scope = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    public struct DeviceCode: Equatable, Sendable {
        public let deviceCode: String
        public let userCode: String
        public let verificationURL: String
        public let expiresIn: Int
        public let interval: Int
    }

    private let http: HTTPClient
    private let tokens: TokenStore
    private let clientID: String
    private let clientSecret: String
    private let deviceCodeURL: URL
    private let tokenURL: URL
    private let now: () -> Date
    private let sleep: (TimeInterval) async throws -> Void

    public init(
        http: HTTPClient,
        tokens: TokenStore,
        clientID: String,
        clientSecret: String,
        oauthBase: URL = URL(string: "https://oauth2.googleapis.com/")!,
        now: @escaping () -> Date = Date.init,
        sleep: @escaping (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    ) {
        self.http = http
        self.tokens = tokens
        self.clientID = clientID
        self.clientSecret = clientSecret
        self.deviceCodeURL = oauthBase.appendingPathComponent("device").appendingPathComponent("code")
        self.tokenURL = oauthBase.appendingPathComponent("token")
        self.now = now
        self.sleep = sleep
    }

    public var isConfigured: Bool { !clientID.isEmpty && !clientSecret.isEmpty }

    public func requestDeviceCode() async throws -> DeviceCode {
        try requireConfigured()
        let request = formRequest(url: deviceCodeURL, fields: [("client_id", clientID), ("scope", Self.scope)])
        let response = try await http.json(DeviceCodeResponse.self, for: request)
        return DeviceCode(
            deviceCode: response.device_code,
            userCode: response.user_code,
            verificationURL: response.verification_url ?? response.verification_uri ?? "https://www.google.com/device",
            expiresIn: response.expires_in,
            interval: max(response.interval ?? 5, 1)
        )
    }

    public func pollForToken(_ code: DeviceCode) async throws -> String {
        let deadline = now().addingTimeInterval(TimeInterval(code.expiresIn))
        var interval = code.interval
        while now() < deadline {
            try await sleep(TimeInterval(interval))
            let request = formRequest(url: tokenURL, fields: [
                ("client_id", clientID),
                ("client_secret", clientSecret),
                ("device_code", code.deviceCode),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ])
            do {
                let response = try await http.json(TokenResponse.self, for: request)
                return persist(response)
            } catch let failure as HTTPStatusError {
                switch failure.oauthError {
                case "authorization_pending"?: continue
                case "slow_down"?: interval += 5
                case "access_denied"?: throw FotoFestaError.accessDenied
                case "expired_token"?: throw FotoFestaError.codeExpired
                default: throw failure
                }
            }
        }
        throw FotoFestaError.codeExpired
    }

    /// Token valido, rinnovato se necessario. Con refresh token revocato cancella tutto e restituisce nil.
    public func validAccessToken() async throws -> String? {
        if let token = tokens.accessToken, let expiresAt = tokens.expiresAt, expiresAt > now().addingTimeInterval(60) {
            return token
        }
        guard let refresh = tokens.refreshToken else { return nil }
        try requireConfigured()
        let request = formRequest(url: tokenURL, fields: [
            ("client_id", clientID),
            ("client_secret", clientSecret),
            ("refresh_token", refresh),
            ("grant_type", "refresh_token"),
        ])
        do {
            return persist(try await http.json(TokenResponse.self, for: request))
        } catch let failure as HTTPStatusError where failure.oauthError == "invalid_grant" || failure.code == 401 {
            disconnect()
            return nil
        }
    }

    public func disconnect() {
        tokens.clear()
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

    private struct DeviceCodeResponse: Decodable {
        let device_code: String
        let user_code: String
        let verification_url: String?
        let verification_uri: String?
        let expires_in: Int
        let interval: Int?
    }

    private struct TokenResponse: Decodable {
        let access_token: String
        let refresh_token: String?
        let expires_in: Int?
    }
}
