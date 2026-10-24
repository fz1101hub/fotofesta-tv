import FotoFestaCore
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

/// Testo localizzato da Localizable.strings, con eventuali argomenti in stile printf. Sul Mac prima si
/// cerca nella tabella Mac.strings, che sostituisce i testi su Apple TV e telecomando.
func L(_ key: String, _ arguments: CVarArg...) -> String {
    #if os(macOS)
    let missing = "\u{0}"
    let mac = Bundle.main.localizedString(forKey: key, value: missing, table: "Mac")
    let format = mac == missing ? NSLocalizedString(key, comment: "") : mac
    #else
    let format = NSLocalizedString(key, comment: "")
    #endif
    return arguments.isEmpty ? format : String(format: format, locale: .current, arguments: arguments)
}

/// Nome del momento del conto alla rovescia.
func countdownName(_ event: CountdownEvent) -> String {
    switch event {
    case .midnight: return L("countdown.midnight")
    case .cake: return L("countdown.cake")
    case .toast: return L("countdown.toast")
    case .surprise: return L("countdown.surprise")
    }
}

/// Messaggio quando arriva il momento.
func countdownMessage(_ event: CountdownEvent) -> String {
    switch event {
    case .midnight: return L("countdown.midnight_message")
    case .cake: return L("countdown.cake_message")
    case .toast: return L("countdown.toast_message")
    case .surprise: return L("countdown.surprise_message")
    }
}

func countdownSymbol(_ event: CountdownEvent) -> String {
    switch event {
    case .midnight: return "sparkles"
    case .cake: return "birthday.cake.fill"
    case .toast: return "wineglass.fill"
    case .surprise: return "gift.fill"
    }
}
