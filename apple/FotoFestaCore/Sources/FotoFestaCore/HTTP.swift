import Foundation

/// Trasporto HTTP iniettabile: in produzione URLSession, nei test un server finto in memoria.
public protocol HTTPClient: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionHTTPClient: HTTPClient {
    public static let defaultMaxBytes = 16 * 1024 * 1024

    private let session: URLSession
    private let maxBytes: Int
    private let allowRedirect: @Sendable (URL) -> Bool

    /// - Parameters:
    ///   - maxBytes: tetto alla risposta; oltre, la richiesta fallisce con `URLError.dataLengthExceedsMaximum`.
    ///   - allowRedirect: dove un reindirizzamento può portare (di serie solo https); altrove la
    ///     risposta 3xx arriva com'è e chi chiama la tratta come un errore.
    public init(
        session: URLSession = .shared,
        maxBytes: Int = URLSessionHTTPClient.defaultMaxBytes,
        allowRedirect: @escaping @Sendable (URL) -> Bool = { $0.scheme?.lowercased() == "https" }
    ) {
        self.session = session
        self.maxBytes = maxBytes
        self.allowRedirect = allowRedirect
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await BoundedFetch.data(for: request, session: session, maxBytes: maxBytes, allowRedirect: allowRedirect)
    }
}

/// Scarica una risposta intera con un tetto in byte e segue i reindirizzamenti solo verso gli
/// indirizzi ammessi: una risposta della rete di casa non può portare altrove né riempire la memoria.
public final class BoundedFetch: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let maxBytes: Int
    private let allowRedirect: @Sendable (URL) -> Bool
    // Usati solo dalla coda della sessione, che consegna gli eventi di un task uno alla volta.
    private var received = Data()
    private var response: HTTPURLResponse?
    private var failure: Error?
    private var continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>?

    private init(maxBytes: Int, allowRedirect: @escaping @Sendable (URL) -> Bool) {
        self.maxBytes = maxBytes
        self.allowRedirect = allowRedirect
    }

    public static func data(
        for request: URLRequest,
        session: URLSession,
        maxBytes: Int,
        allowRedirect: @escaping @Sendable (URL) -> Bool
    ) async throws -> (Data, HTTPURLResponse) {
        let fetch = BoundedFetch(maxBytes: maxBytes, allowRedirect: allowRedirect)
        let task = session.dataTask(with: request)
        task.delegate = fetch
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                fetch.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        if let url = request.url, allowRedirect(url) {
            completionHandler(request)
        } else {
            completionHandler(nil)
        }
    }

    public func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        if response.expectedContentLength > Int64(maxBytes) {
            failure = URLError(.dataLengthExceedsMaximum)
            completionHandler(.cancel)
            return
        }
        self.response = response as? HTTPURLResponse
        completionHandler(.allow)
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        received.append(data)
        if received.count > maxBytes {
            failure = URLError(.dataLengthExceedsMaximum)
            dataTask.cancel()
        }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let continuation else { return }
        self.continuation = nil
        if let failure {
            continuation.resume(throwing: failure)
        } else if let error {
            continuation.resume(throwing: error)
        } else if let response {
            continuation.resume(returning: (received, response))
        } else {
            continuation.resume(throwing: URLError(.badServerResponse))
        }
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

/// Istante RFC 3339 di Google (es. `2026-09-30T12:00:00.123456Z`); nil se assente o non valido.
/// I decimali dei secondi si tolgono prima del parsing: il formattatore non ne accetta più di tre.
func parseTimestamp(_ value: String?) -> Date? {
    guard var text = value, !text.isEmpty else { return nil }
    var fraction: TimeInterval = 0
    if let dot = text.firstIndex(of: ".") {
        let end = text[text.index(after: dot)...].firstIndex { !$0.isASCII || !$0.isNumber } ?? text.endIndex
        fraction = Double("0" + text[dot..<end]) ?? 0
        text.removeSubrange(dot..<end)
    }
    guard let date = ISO8601DateFormatter().date(from: text) else { return nil }
    return date.addingTimeInterval(fraction)
}
