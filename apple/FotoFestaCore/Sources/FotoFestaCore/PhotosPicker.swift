import Foundation

/// Fornisce un access token valido a ogni richiesta, rinnovandolo se sta per scadere.
public typealias TokenProvider = @Sendable () async throws -> String

/// Google Photos Picker API: sessioni di selezione ed elenco delle foto scelte (stessa logica
/// dell'app Android). Le foto non vengono mai scaricate su disco: l'elenco fornisce indirizzi
/// validi circa 60 minuti, che si rinnovano chiedendo di nuovo l'elenco finché la sessione è valida.
public final class PhotosPicker: @unchecked Sendable {
    public struct Session: Equatable, Sendable {
        public let id: String
        public let pickerURI: String
        public let mediaItemsSet: Bool
        public let pollInterval: TimeInterval
        /// Tempo residuo per scegliere le foto, indicato da Google nell'ultima risposta.
        public let timeout: TimeInterval?
        /// Istante dopo il quale la sessione e le sue foto non sono più accessibili.
        public let expiresAt: Date?
    }

    public struct PickedMedia: Equatable, Sendable {
        public let id: String
        public let filename: String
        public let baseURL: String
        public let mimeType: String

        public var isPhoto: Bool { mimeType.hasPrefix("image/") }
    }

    public static let defaultAPIBase = URL(string: "https://photospicker.googleapis.com/v1/")!
    /// Cartelle delle foto scaricate dalle versioni precedenti, che copiavano le immagini sul dispositivo.
    static let legacyDownloadFolder = "google_photos"
    private static let defaultSessionTimeout: TimeInterval = 900

    private let http: HTTPClient
    private let apiBase: URL
    private let sleep: @Sendable (TimeInterval) async throws -> Void

    public init(
        http: HTTPClient,
        apiBase: URL = PhotosPicker.defaultAPIBase,
        sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    ) {
        self.http = http
        self.apiBase = apiBase
        self.sleep = sleep
    }

    // MARK: - Sessione Picker

    public func createSession(token: String) async throws -> Session {
        var components = URLComponents(url: apiBase.appendingPathComponent("sessions"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "requestId", value: UUID().uuidString.lowercased())]
        var request = authorized(components.url!, token: token)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("{}".utf8)
        return try await http.json(SessionResponse.self, for: request).session
    }

    public func awaitSelection(token: TokenProvider, session initial: Session) async throws -> Session {
        var current = initial
        var deadline = Date().addingTimeInterval(max(current.timeout ?? Self.defaultSessionTimeout, 60))
        while !current.mediaItemsSet {
            guard Date() < deadline else { throw FotoFestaError.selectionExpired }
            try await sleep(min(max(current.pollInterval, 2), 30))
            current = try await session(token: token, id: current.id)
            // timeoutIn è relativo alla risposta: la scadenza si ricalcola a ogni polling.
            if let timeout = current.timeout { deadline = Date().addingTimeInterval(max(timeout, 60)) }
        }
        return current
    }

    /// Tutti gli elementi scelti (foto e video), con indirizzi validi circa 60 minuti.
    public func listPickedMedia(token: TokenProvider, sessionID: String) async throws -> [PickedMedia] {
        var output: [PickedMedia] = []
        var pageToken: String?
        repeat {
            var components = URLComponents(url: apiBase.appendingPathComponent("mediaItems"), resolvingAgainstBaseURL: false)!
            var query = [("sessionId", sessionID), ("pageSize", "100")]
            if let pageToken { query.append(("pageToken", pageToken)) }
            // Codifica rigorosa: URLComponents lascerebbe "+" (letto come spazio) e altri separatori.
            components.percentEncodedQueryItems = query.map { name, value in
                URLQueryItem(name: name, value: value.addingPercentEncoding(withAllowedCharacters: .urlStrictValue))
            }
            let accessToken = try await token()
            let page = try await http.json(MediaItemsResponse.self, for: authorized(components.url!, token: accessToken))
            for item in page.mediaItems ?? [] {
                guard let id = item.id, !id.isEmpty, let file = item.mediaFile,
                      let baseURL = file.baseUrl, !baseURL.isEmpty else { continue }
                output.append(PickedMedia(
                    id: id,
                    filename: file.filename.flatMap { $0.isEmpty ? nil : $0 } ?? "Photo \(output.count + 1)",
                    baseURL: baseURL,
                    mimeType: file.mimeType ?? ""
                ))
            }
            pageToken = page.nextPageToken.flatMap { $0.isEmpty ? nil : $0 }
        } while pageToken != nil
        return output
    }

    /// Elimina la sessione lato Google. Best effort: la sessione scade comunque da sola.
    public func deleteSession(token: TokenProvider, sessionID: String) async {
        guard let accessToken = try? await token() else { return }
        var request = authorized(apiBase.appendingPathComponent("sessions").appendingPathComponent(sessionID), token: accessToken)
        request.httpMethod = "DELETE"
        _ = try? await http.send(request)
    }

    /// Indirizzo dei byte della foto ridotta da Google a `width`×`height`. Con `crop` l'immagine
    /// riempie esattamente il riquadro; senza, ci sta dentro mantenendo le proporzioni.
    public static func sizedURL(base: String, width: Int, height: Int, crop: Bool) -> URL? {
        URL(string: "\(base)=w\(width)-h\(height)" + (crop ? "-c" : ""))
    }

    /// Elimina le copie delle foto Google lasciate dalle versioni precedenti, che le scaricavano.
    public static func deleteLegacyDownloads(in root: URL, fileManager: FileManager = .default) {
        let contents = (try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        for url in contents where url.lastPathComponent == legacyDownloadFolder || url.lastPathComponent.hasPrefix("\(legacyDownloadFolder).") {
            try? fileManager.removeItem(at: url)
        }
    }

    // MARK: - Interni

    private func session(token: TokenProvider, id: String) async throws -> Session {
        let url = apiBase.appendingPathComponent("sessions").appendingPathComponent(id)
        let accessToken = try await token()
        return try await http.json(SessionResponse.self, for: authorized(url, token: accessToken)).session
    }

    private func authorized(_ url: URL, token: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private struct SessionResponse: Decodable {
        struct PollingConfig: Decodable {
            let pollInterval: String?
            let timeoutIn: String?
        }

        let id: String
        let pickerUri: String
        let mediaItemsSet: Bool?
        let pollingConfig: PollingConfig?
        let expireTime: String?

        var session: Session {
            Session(
                id: id,
                pickerURI: pickerUri,
                mediaItemsSet: mediaItemsSet ?? false,
                pollInterval: parseProtoDuration(pollingConfig?.pollInterval) ?? 5,
                timeout: parseProtoDuration(pollingConfig?.timeoutIn),
                expiresAt: parseTimestamp(expireTime)
            )
        }
    }

    private struct MediaItemsResponse: Decodable {
        struct Item: Decodable {
            struct MediaFile: Decodable {
                let baseUrl: String?
                let mimeType: String?
                let filename: String?
            }

            let id: String?
            let mediaFile: MediaFile?
        }

        let mediaItems: [Item]?
        let nextPageToken: String?
    }
}

extension CharacterSet {
    /// Caratteri non riservati RFC 3986: tutto il resto viene codificato.
    static let urlStrictValue: CharacterSet = {
        var set = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        set.insert(charactersIn: "-._~")
        return set
    }()
}
