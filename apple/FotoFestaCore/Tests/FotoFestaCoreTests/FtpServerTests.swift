import Foundation
import XCTest
@testable import FotoFestaCore

/// Client TCP bloccante minimo, con i socket BSD, per parlare con il server come una fotocamera.
private final class LineClient {
    let socket: Int32
    private var pending = Data()

    init?(port: UInt16) {
        socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if result != 0 {
            close(socket)
            return nil
        }
    }

    deinit { close(socket) }

    func write(_ data: Data) {
        _ = data.withUnsafeBytes { Darwin.send(socket, $0.baseAddress, data.count, 0) }
    }

    func shutdownWrite() {
        Darwin.shutdown(socket, SHUT_WR)
    }

    /// Una risposta intera: le risposte su più righe ("211-…" fino a "211 …") contano come una.
    func read() -> String {
        var line = readLine()
        if line.count > 3, line[line.index(line.startIndex, offsetBy: 3)] == "-" {
            let code = String(line.prefix(3))
            repeat { line = readLine() } while !line.hasPrefix(code + " ") && !line.isEmpty
        }
        return line
    }

    func send(_ command: String) -> String {
        write(Data((command + "\r\n").utf8))
        return read()
    }

    private func readLine() -> String {
        while true {
            if let newline = pending.firstIndex(of: 0x0A) {
                let line = String(decoding: pending[pending.startIndex..<newline], as: UTF8.self)
                pending.removeSubrange(pending.startIndex...newline)
                return line.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            var buffer = [UInt8](repeating: 0, count: 1_024)
            let count = recv(socket, &buffer, buffer.count, 0)
            if count <= 0 { return "" }
            pending.append(contentsOf: buffer[0..<count])
        }
    }
}

final class FtpServerTests: XCTestCase {
    private final class Received: @unchecked Sendable {
        private let lock = NSLock()
        private var files: [(String, Data)] = []
        func add(_ name: String, _ data: Data) { lock.withLock { files.append((name, data)) } }
        var all: [(String, Data)] { lock.withLock { files } }
    }

    private func makeServer(_ received: Received, arrived: XCTestExpectation? = nil) async throws -> (FtpServer, UInt16) {
        let server = FtpServer(user: "fotofesta", password: { "123456" }, port: 0, maxFileBytes: 1_000, allowLoopback: true) { name, data in
            received.add(name, data)
            arrived?.fulfill()
        }
        return (server, try await server.start())
    }

    private func passivePort(_ reply: String) -> UInt16? {
        guard let open = reply.firstIndex(of: "("), let close = reply.firstIndex(of: ")") else { return nil }
        let numbers = reply[reply.index(after: open)..<close].split(separator: ",").compactMap { Int($0) }
        guard numbers.count == 6 else { return nil }
        return UInt16(numbers[4] * 256 + numbers[5])
    }

    func testACameraLogsInAndUploadsAPhotoInPassiveMode() async throws {
        let received = Received()
        let arrived = expectation(description: "file")
        let (server, port) = try await makeServer(received, arrived: arrived)
        defer { server.stop() }
        let client = try XCTUnwrap(LineClient(port: port))
        XCTAssertTrue(client.read().hasPrefix("220"))
        XCTAssertTrue(client.send("STOR prima.jpg").hasPrefix("530"))
        XCTAssertTrue(client.send("USER fotofesta").hasPrefix("331"))
        XCTAssertTrue(client.send("PASS 123456").hasPrefix("230"))
        XCTAssertTrue(client.send("FEAT").hasPrefix("211"))
        XCTAssertTrue(client.send("TYPE I").hasPrefix("200"))
        XCTAssertTrue(client.send("CWD DCIM").hasPrefix("250"))
        XCTAssertEqual(client.send("PWD"), "257 \"/DCIM\"")
        let pasv = client.send("PASV")
        XCTAssertTrue(pasv.hasPrefix("227"), pasv)
        let data = try XCTUnwrap(LineClient(port: try XCTUnwrap(passivePort(pasv))))
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertTrue(client.send("STOR IMG_0001.JPG").hasPrefix("150"))
        let photo = Data((0..<500).map { UInt8($0 & 0xFF) })
        data.write(photo)
        data.shutdownWrite()
        XCTAssertTrue(client.read().hasPrefix("226"))
        await fulfillment(of: [arrived], timeout: 5)
        XCTAssertEqual(received.all.first?.0, "IMG_0001.JPG")
        XCTAssertEqual(received.all.first?.1, photo)
        XCTAssertTrue(client.send("QUIT").hasPrefix("221"))
    }

    func testFilesThatAreTooLargeAreRefusedAndWrongPasswordsCloseTheSession() async throws {
        let received = Received()
        let (server, port) = try await makeServer(received)
        defer { server.stop() }
        let client = try XCTUnwrap(LineClient(port: port))
        _ = client.read()
        _ = client.send("USER fotofesta")
        _ = client.send("PASS 123456")
        let epsv = client.send("EPSV")
        XCTAssertTrue(epsv.hasPrefix("229"), epsv)
        let digits = epsv.split(separator: "|").compactMap { UInt16($0) }
        let data = try XCTUnwrap(LineClient(port: try XCTUnwrap(digits.first)))
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertTrue(client.send("STOR grande.jpg").hasPrefix("150"))
        data.write(Data(repeating: 1, count: 2_000))
        data.shutdownWrite()
        XCTAssertTrue(client.read().hasPrefix("552"))
        XCTAssertTrue(received.all.isEmpty)

        let intruder = try XCTUnwrap(LineClient(port: port))
        _ = intruder.read()
        _ = intruder.send("USER fotofesta")
        XCTAssertTrue(intruder.send("PASS 1").hasPrefix("530"))
        XCTAssertTrue(intruder.send("PASS 2").hasPrefix("530"))
        XCTAssertTrue(intruder.send("PASS 3").hasPrefix("530"))
        XCTAssertEqual(intruder.read(), "")
    }
}
