import Foundation

/// Movimento "Ken Burns" di una foto: zoom e spostamento lenti per tutta la sua permanenza.
/// Deterministico per foto e con spostamento sempre entro il margine creato dallo zoom,
/// così i bordi della foto non entrano mai nell'inquadratura.
public struct KenBurnsMotion: Equatable, Sendable {
    public static let maxZoom = 1.10
    private static let panShare = 0.8

    public let startScale: Double
    public let endScale: Double
    /// Direzione dello spostamento: -1, 0 o 1.
    public let directionX: Double
    public let directionY: Double

    public init(startScale: Double, endScale: Double, directionX: Double, directionY: Double) {
        self.startScale = startScale
        self.endScale = endScale
        self.directionX = directionX
        self.directionY = directionY
    }

    public func scale(at progress: Double) -> Double {
        startScale + (endScale - startScale) * Self.clamp(progress)
    }

    /// Spostamento orizzontale come frazione della larghezza.
    public func offsetX(at progress: Double) -> Double { directionX * Self.panShare * margin(at: progress) }

    /// Spostamento verticale come frazione dell'altezza.
    public func offsetY(at progress: Double) -> Double { directionY * Self.panShare * margin(at: progress) }

    private func margin(at progress: Double) -> Double { (scale(at: progress) - 1) / 2 }

    private static func clamp(_ value: Double) -> Double { min(max(value, 0), 1) }

    public static func forPhoto(id: String) -> KenBurnsMotion {
        // FNV-1a: a differenza di hashValue è stabile tra un avvio e l'altro.
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in id.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        let zoomIn = hash % 2 == 0
        return KenBurnsMotion(
            startScale: zoomIn ? 1 : maxZoom,
            endScale: zoomIn ? maxZoom : 1,
            directionX: (hash / 2) % 2 == 0 ? 1 : -1,
            directionY: Double((hash / 4) % 3) - 1
        )
    }
}
