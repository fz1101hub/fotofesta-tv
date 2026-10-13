import Foundation

/// Trasporto HTTP iniettabile: in produzione URLSession, nei test un server finto in memoria.
public protocol HTTPClient: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
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
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
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
