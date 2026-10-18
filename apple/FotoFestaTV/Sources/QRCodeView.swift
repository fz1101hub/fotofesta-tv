import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Codice QR nitido (un pixel per modulo, ingrandito senza interpolazione).
struct QRCodeView: View {
    let text: String
    let accessibilityLabel: String
    var size: CGFloat = 360

    var body: some View {
        Group {
            if let image = Self.makeImage(text) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Color.white
            }
        }
        .padding(18)
        .background(Color.white)
        .frame(width: size, height: size)
        .accessibilityLabel(Text(accessibilityLabel))
    }

    private static func makeImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
