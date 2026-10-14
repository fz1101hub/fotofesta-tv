import Foundation

/// Trasporto HTTP iniettabile: in produzione URLSession, nei test un server finto in memoria.
public protocol HTTPClient: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
    func download(_ request: URLRequest, to destination: URL, maxBytes: Int64) async throws -> HTTPURLResponse
}

// I client finti dei test possono utilizzare Data senza dipendere da URLSession.
public extension HTTPClient {
    func download(_ request: URLRequest, to destination: URL, maxBytes: Int64) async throws -> HTTPURLResponse {
        let (data, response) = try await send(request)
        try Task.checkCancellation()
        guard Int64(data.count) <= maxBytes else { throw URLError(.dataLengthExceedsMaximum) }
        try data.write(to: destination, options: .atomic)
        return response
    }
}

public struct URLSessionHTTPClient: HTTPClient {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    // URLSession usa un file temporaneo, evitando il caricamento dell'intera foto 4K in RAM.
    public func download(_ request: URLRequest, to destination: URL, maxBytes: Int64) async throws -> HTTPURLResponse {
        let (temporary, response) = try await session.download(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        try Task.checkCancellation()
        let attrs = try FileManager.default.attributesOfItem(atPath: temporary.path)
        let bytes = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        guard bytes > 0, bytes <= maxBytes else { throw URLError(.dataLengthExceedsMaximum) }
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: temporary, to: destination)
        return http
    }
}

/// Risposta HTTP non riuscita, con il codice OAuth (es. `invalid_grant`) e il messaggio di Google se presenti.
public struct HTTPStatusError: Error, Equatable, Sendable {
    public let code: Int
    public let oauthError: String?
    public let serverMessage: String?

    public init(code: Int, oauthError: String?, serverMessage: String?) {
        self.code = code
        self.oauthError = oauthError
        self.serverMessage = serverMessage
    }

    /// Le API Google usano `{"error":{"message":…}}`, l'endpoint OAuth `{"error":"…","error_description":…}`.
    public static func from(code: Int, body: Data) -> HTTPStatusError {
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        let apiMessage = (json?["error"] as? [String: Any])?["message"] as? String
        let oauthError = json?["error"] as? String
        let description = json?["error_description"] as? String
        let message = [apiMessage, description].compactMap { $0 }.first { !$0.isEmpty }
        return HTTPStatusError(code: code, oauthError: oauthError, serverMessage: message)
    }
}

extension HTTPClient {
    /// Esegue la richiesta e decodifica il JSON di risposta, oppure lancia `HTTPStatusError`.
    func json<T: Decodable>(_ type: T.Type, for request: URLRequest) async throws -> T {
        let (data, response) = try await send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw HTTPStatusError.from(code: response.statusCode, body: data)
        }
        return try JSONDecoder().decode(T.self, from: data.isEmpty ? Data("{}".utf8) : data)
    }
}

/// Corpo `application/x-www-form-urlencoded`.
func formRequest(url: URL, fields: [(String, String)]) -> URLRequest {
    let allowed = CharacterSet.urlStrictValue
    let body = fields
        .map { key, value in
            let encodedKey = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let encodedValue = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(encodedKey)=\(encodedValue)"
        }
        .joined(separator: "&")
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
    request.httpBody = Data(body.utf8)
    return request
}

/// Converte una durata protobuf JSON (es. `"5s"`, `"1.5s"`) in secondi.
func parseProtoDuration(_ value: String?) -> TimeInterval? {
    guard var text = value?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
    if text.hasSuffix("s") { text.removeLast() }
    guard let seconds = Double(text), seconds >= 0, seconds.isFinite else { return nil }
    return seconds
}
