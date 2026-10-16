import Foundation

/// Didascalia dello slideshow. I nomi dati dalle fotocamere e dai telefoni ("IMG_20240512_153422",
/// "PXL_…", "WhatsApp Image …") non dicono nulla a chi guarda: al loro posto nessun titolo.
public enum PhotoCaption {
    private static let cameraPrefixes = [
        "img", "dsc", "dscn", "dscf", "pxl", "mvimg", "pano", "burst", "photo", "pic", "image", "cimg",
        "screenshot", "whatsapp image", "signal-", "telegram", "gopr", "dji", "sam_", "wp_", "imag",
    ]

    public static func looksLikeCameraName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return true }
        let lower = trimmed.lowercased()
        let digits = trimmed.filter(\.isNumber).count
        let letters = trimmed.filter(\.isLetter).count
        // Molte cifre e poche lettere: data e ora scritte dal telefono o dalla fotocamera.
        if digits >= 6, letters <= 4 { return true }
        return digits >= 2 && cameraPrefixes.contains { lower.hasPrefix($0) }
    }

    /// Titolo da mostrare: il nome del file se è significativo, altrimenti nessuno.
    public static func title(_ fileTitle: String) -> String? {
        looksLikeCameraName(fileTitle) ? nil : fileTitle
    }
}
