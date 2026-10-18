import XCTest
@testable import FotoFestaCore

/// Il client di rete contro il server degli invitati, l'unico server HTTP vero di questi test.
final class HTTPClientTests: XCTestCase {
    func testResponsesBeyondTheLimitAreRefused() async throws {
        let server = GuestUploadServer(key: "test-key", page: { "<html>pagina di prova</html>" }, onPhoto: { _, _ in .shown })
        let port = try await server.start(host: "127.0.0.1", preferredPort: 0)
        defer { server.stop() }
        let request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/?k=test-key")!)

        let roomy = URLSessionHTTPClient(session: URLSession(configuration: .ephemeral), maxBytes: 1_024) { _ in false }
        let (data, response) = try await roomy.send(request)
        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "<html>pagina di prova</html>")

        let tight = URLSessionHTTPClient(session: URLSession(configuration: .ephemeral), maxBytes: 8) { _ in false }
        do {
            _ = try await tight.send(request)
            XCTFail("risposta oltre il limite accettata")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .dataLengthExceedsMaximum)
        }
    }

    func testRequestsWithoutHostAreRefused() async throws {
        // Un reindirizzamento verso un indirizzo non ammesso non viene seguito: il 3xx arriva com'è.
        // Il server degli invitati non reindirizza mai, quindi qui si verifica l'altra regola nuova:
        // senza l'intestazione Host la richiesta è rifiutata (421).
        let server = GuestUploadServer(key: "test-key", page: { "<html>pagina</html>" }, onPhoto: { _, _ in .shown })
        let port = try await server.start(host: "127.0.0.1", preferredPort: 0)
        defer { server.stop() }
        let status = try await RawHTTP.status(host: "127.0.0.1", port: port, request: "GET /?k=test-key HTTP/1.1\r\nConnection: close\r\n\r\n")
        XCTAssertEqual(status, 421)
        let withHost = try await RawHTTP.status(host: "127.0.0.1", port: port, request: "GET /?k=test-key HTTP/1.1\r\nHost: 127.0.0.1:\(port)\r\nConnection: close\r\n\r\n")
        XCTAssertEqual(withHost, 200)
    }
}

/// Richiesta HTTP scritta a mano su un socket, per provare quello che URLSession aggiunge da sé.
enum RawHTTP {
    static func status(host: String, port: UInt16, request: String) async throws -> Int {
        let stream = try await Self.exchange(host: host, port: port, request: request)
        guard let line = stream.split(separator: "\r\n", maxSplits: 1).first,
              let code = line.split(separator: " ").dropFirst().first, let status = Int(code) else {
            throw URLError(.badServerResponse)
        }
        return status
    }

    private static func exchange(host: String, port: UInt16, request: String) async throws -> String {
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw URLError(.cannotConnectToHost) }
        defer { Darwin.close(socket) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr(host)
        let connected = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard connected == 0 else { throw URLError(.cannotConnectToHost) }
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        let bytes = Array(request.utf8)
        guard Darwin.send(socket, bytes, bytes.count, 0) == bytes.count else { throw URLError(.cannotConnectToHost) }
        var received = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while true {
            let count = Darwin.recv(socket, &buffer, buffer.count, 0)
            if count <= 0 { break }
            received.append(contentsOf: buffer[0..<count])
            if received.count > 64 * 1024 { break }
        }
        return String(decoding: received, as: UTF8.self)
    }
}
