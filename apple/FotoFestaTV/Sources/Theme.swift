import SwiftUI

/// Colori dell'app su Apple TV. Come nelle app di sistema di tvOS l'interfaccia è bianca e grigia,
/// con materiali ed effetti di fuoco nativi: il colore viene dalle foto e dall'icona dell'app. Niente
/// tinta globale: su tvOS 26 colora i pulsanti senza fuoco e ne nasconde il testo.
enum Theme {
    /// Unico colore per i dettagli da notare ("In uso", ora della pausa automatica): l'arancione di
    /// sistema, che si adatta da solo al contrasto aumentato.
    static let highlight = Color.orange
    static let onHighlight = Color.black
    static let background = Color.black
    /// Testo secondario su fondo scuro, più chiaro del grigio di sistema per la lettura da lontano.
    static let secondaryText = Color(hex: 0xC8CDD6)
    static let error = Color.red
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
