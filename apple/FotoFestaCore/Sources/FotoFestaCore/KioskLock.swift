import CryptoKit
import Foundation

/// PIN del chiosco per bar e locali: si salva solo un'impronta SHA-256 con il sale, mai il PIN.
/// Dopo un PIN giusto le impostazioni restano aperte per qualche minuto.
public final class KioskLock {
    public static let pinLength = 4
    public static let unlockTime: TimeInterval = 5 * 60
    private static let pinKey = "kiosk.pin"
    private static let saltKey = "kiosk.salt"

    private let defaults: UserDefaults
    public private(set) var unlockedUntil = Date.distantPast

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public var hasPin: Bool { defaults.string(forKey: Self.pinKey) != nil }

    /// Quattro cifre, niente altro.
    public static func valid(_ pin: String) -> Bool {
        pin.count == pinLength && pin.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// Salva il PIN nuovo (nil lo cancella); false se non è di quattro cifre.
    @discardableResult
    public func save(_ pin: String?) -> Bool {
        guard let pin else {
            defaults.removeObject(forKey: Self.pinKey)
            defaults.removeObject(forKey: Self.saltKey)
            return true
        }
        guard Self.valid(pin) else { return false }
        let salt = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) }).base64EncodedString()
        defaults.set(salt, forKey: Self.saltKey)
        defaults.set(Self.hash(pin, salt: salt), forKey: Self.pinKey)
        return true
    }

    public func matches(_ pin: String) -> Bool {
        guard let salt = defaults.string(forKey: Self.saltKey), let expected = defaults.string(forKey: Self.pinKey) else { return false }
        let given = Data(Self.hash(pin, salt: salt).utf8)
        let stored = Data(expected.utf8)
        // Confronto a tempo costante.
        guard given.count == stored.count else { return false }
        return zip(given, stored).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public func unlock(now: Date = Date()) {
        unlockedUntil = now.addingTimeInterval(Self.unlockTime)
    }

    /// Chiosco acceso, PIN scelto e nessuno sblocco recente.
    public func locked(enabled: Bool, now: Date = Date()) -> Bool {
        enabled && hasPin && now > unlockedUntil
    }

    private static func hash(_ pin: String, salt: String) -> String {
        Data(SHA256.hash(data: Data((salt + ":" + pin).utf8))).base64EncodedString()
    }
}
