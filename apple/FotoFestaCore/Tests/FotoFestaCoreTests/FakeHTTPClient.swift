import Foundation
@testable import FotoFestaCore

/// Server Google finto in memoria: risposte accodate per "METODO /percorso" e richieste registrate.
final class FakeHTTPClient: HTTPClient, @unchecked Sendable {
    struct Reply {
        let status: Int
        let body: Data
        var error: Error?

        static func json(_ text: String, status: Int = 200) -> Reply { Reply(status: status, body: Data(text.utf8)) }
        static func bytes(_ text: String, status: Int = 200) -> Reply { Reply(status: status, body: Data(text.utf8)) }
        static func failure(_ error: Error) -> Reply { Reply(status: 0, body: Data(), error: error) }
    }

    private let lock = NSLock()
    private var queues: [String: [Reply]] = [:]
    private var fallbacks: [String: Reply] = [:]
    private(set) var requests: [URLRequest] = []
    var onRequest: (URLRequest) -> Void = { _ in }

    func enqueue(_ method: String, _ path: String, _ replies: Reply...) {
        lock.lock(); defer { lock.unlock() }
        queues["\(method) \(path)", default: []].append(contentsOf: replies)
    }

    func always(_ method: String, _ path: String, _ reply: Reply) {
        lock.lock(); defer { lock.unlock() }
        fallbacks["\(method) \(path)"] = reply
    }

    func requests(_ method: String, _ path: String) -> [URLRequest] {
        lock.lock(); defer { lock.unlock() }
        return requests.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        onRequest(request)
        let key = "\(request.httpMethod ?? "GET") \(request.url?.path ?? "")"
        let reply: Reply = {
            lock.lock(); defer { lock.unlock() }
            requests.append(request)
            if var queue = queues[key], !queue.isEmpty {
                let first = queue.removeFirst()
                queues[key] = queue
                return first
            }
            return fallbacks[key] ?? .json(#"{"error":{"message":"no route \#(key)"}}"#, status: 404)
        }()
        if let error = reply.error { throw error }
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: nil)!
        return (reply.body, response)
    }
}

final class MemoryTokenStore: TokenStore {
    var accessToken: String?
    var refreshToken: String?
    var expiresAt: Date?
    var cleared = false

    func clear() {
        accessToken = nil
        refreshToken = nil
        expiresAt = nil
        cleared = true
    }
}

/// Orologio controllabile dai test.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = Date(timeIntervalSince1970: 1_800_000_000)

    var now: Date {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    func advance(_ seconds: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        current = current.addingTimeInterval(seconds)
    }
}

extension URLRequest {
    var bodyString: String { httpBody.map { String(decoding: $0, as: UTF8.self) } ?? "" }
}

let oauthBase = URL(string: "https://oauth.test/")!
let apiBase = URL(string: "https://picker.test/v1/")!
