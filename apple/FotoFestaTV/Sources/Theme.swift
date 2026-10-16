import SwiftUI

/// Palette ad alto contrasto, uguale all'app Android: sfondo quasi nero, testi bianchi e grigio chiaro
/// (almeno 6:1), un solo colore d'accento (il corallo del logo) e il fuoco in bianco pieno.
enum Theme {
    static let background = Color(hex: 0x0D0F13)
    static let surface = Color(hex: 0x1B1E25)
    static let surfaceRaised = Color(hex: 0x262A33)
    static let outline = Color(hex: 0x343945)
    static let text = Color.white
    static let secondaryText = Color(hex: 0xC8CDD6)
    static let tertiaryText = Color(hex: 0x9AA2AF)
    static let accent = Color(hex: 0xFF7A57)
    static let onAccent = Color(hex: 0x1A0B06)
    static let focus = Color.white
    static let onFocus = Color(hex: 0x0D0F13)
    static let onFocusSecondary = Color(hex: 0x3A404C)
    static let switchOff = Color(hex: 0x4A505D)
    static let switchThumbOff = Color(hex: 0xD5D9E0)
    static let error = Color(hex: 0xFF9C85)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Pesi di Figtree inclusi nell'app (licenza SIL OFL, file Figtree-OFL.txt).
enum FigtreeWeight: String {
    case regular = "Figtree-Regular"
    case medium = "Figtree-Medium"
    case semibold = "Figtree-SemiBold"
    case bold = "Figtree-Bold"
    case extraBold = "Figtree-ExtraBold"
}

extension Font {
    /// Figtree alla dimensione in punti di tvOS (1920×1080), che segue anche il testo grande di sistema.
    static func figtree(_ size: CGFloat, _ weight: FigtreeWeight = .regular) -> Font {
        .custom(weight.rawValue, size: size)
    }
}

/// Testo con un numero, al singolare o al plurale: chiavi "<chiave>.one" e "<chiave>.other".
func LP(_ key: String, _ count: Int) -> String {
    L(count == 1 ? "\(key).one" : "\(key).other", count)
}

/// Testo localizzato da Localizable.strings, con eventuali argomenti in stile printf.
func L(_ key: String, _ arguments: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    return arguments.isEmpty ? format : String(format: format, locale: .current, arguments: arguments)
}
