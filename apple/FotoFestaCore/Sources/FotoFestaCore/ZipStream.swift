import Foundation

/// Archivio ZIP senza compressione (le foto JPEG e i video sono già compressi), scritto su un file
/// temporaneo voce per voce: in memoria resta una foto alla volta. Come `ZipStream.kt`.
public enum ZipStream {
    /// Una voce dell'archivio: `read` dà i byte (nil se nel frattempo il file è sparito).
    public struct Entry: Sendable {
        public let name: String
        public let read: @Sendable () -> Data?

        public init(name: String, read: @escaping @Sendable () -> Data?) {
            self.name = name
            self.read = read
        }
    }

    public static let maxEntries = 65_000
    /// Senza ZIP64: niente oltre i 4 GB.
    public static let maxBytes: Int64 = 0xFFFF_FFF0

    /// Scrive l'archivio in `url`; le voci che non si leggono più si saltano. Restituisce quante voci ha scritto.
    @discardableResult
    public static func write(_ entries: [Entry], to url: URL, date: Date = Date()) throws -> Int {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        let (dosTime, dosDate) = dosDateTime(date)
        var central = Data()
        var offset: Int64 = 0
        var written = 0
        for entry in entries.prefix(maxEntries) {
            guard let data = entry.read() else { continue }
            let name = Data(entry.name.utf8)
            guard offset + Int64(30 + name.count + data.count) <= maxBytes else { break }
            let crc = crc32(data)
            var local = Data()
            local.appendLE32(0x0403_4B50)
            local.appendLE16(10) // versione 1.0
            local.appendLE16(0x0800) // nomi in UTF-8
            local.appendLE16(0) // nessuna compressione
            local.appendLE16(dosTime)
            local.appendLE16(dosDate)
            local.appendLE32(crc)
            local.appendLE32(UInt32(data.count))
            local.appendLE32(UInt32(data.count))
            local.appendLE16(UInt16(name.count))
            local.appendLE16(0)
            local.append(name)
            try handle.write(contentsOf: local)
            try handle.write(contentsOf: data)

            central.appendLE32(0x0201_4B50)
            central.appendLE16(0x031E) // creato su Unix, versione 3.0
            central.appendLE16(10)
            central.appendLE16(0x0800)
            central.appendLE16(0)
            central.appendLE16(dosTime)
            central.appendLE16(dosDate)
            central.appendLE32(crc)
            central.appendLE32(UInt32(data.count))
            central.appendLE32(UInt32(data.count))
            central.appendLE16(UInt16(name.count))
            central.appendLE16(0)
            central.appendLE16(0)
            central.appendLE16(0)
            central.appendLE16(0)
            central.appendLE32(0o100644 << 16) // file normale, rw-r--r--
            central.appendLE32(UInt32(offset))
            central.append(name)
            offset += Int64(local.count + data.count)
            written += 1
        }
        var end = Data()
        end.appendLE32(0x0605_4B50)
        end.appendLE16(0)
        end.appendLE16(0)
        end.appendLE16(UInt16(written))
        end.appendLE16(UInt16(written))
        end.appendLE32(UInt32(central.count))
        end.appendLE32(UInt32(offset))
        end.appendLE16(0)
        try handle.write(contentsOf: central)
        try handle.write(contentsOf: end)
        return written
    }

    /// CRC-32 (IEEE), come lo vuole lo ZIP.
    public static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            for byte in buffer {
                crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
            }
        }
        return crc ^ 0xFFFF_FFFF
    }

    private static let table: [UInt32] = (0..<256).map { index in
        var value = UInt32(index)
        for _ in 0..<8 { value = (value & 1) != 0 ? 0xEDB8_8320 ^ (value >> 1) : value >> 1 }
        return value
    }

    /// Data e ora nel formato MS-DOS delle voci ZIP (ora locale).
    static func dosDateTime(_ date: Date) -> (time: UInt16, date: UInt16) {
        let parts = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let year: Int = min(max((parts.year ?? 1980) - 1980, 0), 127)
        let hour: Int = parts.hour ?? 0
        let minute: Int = parts.minute ?? 0
        let second: Int = (parts.second ?? 0) / 2
        let month: Int = parts.month ?? 1
        let dayOfMonth: Int = parts.day ?? 1
        let time: Int = (hour << 11) | (minute << 5) | second
        let day: Int = (year << 9) | (month << 5) | dayOfMonth
        return (UInt16(time), UInt16(day))
    }
}

private extension Data {
    mutating func appendLE16(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8(value >> 8))
    }

    mutating func appendLE32(_ value: UInt32) {
        for shift in stride(from: 0, to: 32, by: 8) { append(UInt8((value >> UInt32(shift)) & 0xFF)) }
    }
}
