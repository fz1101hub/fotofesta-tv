import SwiftUI

/// Colori dell'app su Apple TV. Il resto segue lo stile di tvOS: carattere di sistema, materiali,
/// colori `.primary`/`.secondary` e gli effetti di fuoco dei controlli nativi.
enum Theme {
    /// Corallo del logo, usato come tinta dell'app (pulsante principale, interruttori, avanzamento).
    static let accent = Color(hex: 0xFF7A57)
    static let onAccent = Color(hex: 0x1A0B06)
    static let background = Color.black
    /// Testo secondario su fondo scuro, più chiaro del grigio di sistema per la lettura da lontano.
    static let secondaryText = Color(hex: 0xC8CDD6)
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

/// Testo con un numero, al singolare o al plurale: chiavi "<chiave>.one" e "<chiave>.other".
func LP(_ key: String, _ count: Int) -> String {
    L(count == 1 ? "\(key).one" : "\(key).other", count)
}

/// Testo localizzato da Localizable.strings, con eventuali argomenti in stile printf.
func L(_ key: String, _ arguments: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    return arguments.isEmpty ? format : String(format: format, locale: .current, arguments: arguments)
}
