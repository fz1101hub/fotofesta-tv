import SwiftUI

enum Theme {
    static let navy = Color(red: 5 / 255, green: 10 / 255, blue: 24 / 255)
    static let navySoft = Color(red: 16 / 255, green: 24 / 255, blue: 45 / 255)
    static let coral = Color(red: 1, green: 121 / 255, blue: 91 / 255)
    static let amber = Color(red: 1, green: 194 / 255, blue: 75 / 255)
    static let cyan = Color(red: 85 / 255, green: 214 / 255, blue: 210 / 255)
    static let secondaryText = Color(red: 185 / 255, green: 195 / 255, blue: 217 / 255)

    static let background = LinearGradient(colors: [Color(red: 26 / 255, green: 41 / 255, blue: 72 / 255), navy], startPoint: .top, endPoint: .bottom)
}

/// Testo localizzato da Localizable.strings, con eventuali argomenti in stile printf.
func L(_ key: String, _ arguments: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    return arguments.isEmpty ? format : String(format: format, locale: .current, arguments: arguments)
}
