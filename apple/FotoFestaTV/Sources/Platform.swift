import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// Le poche differenze fra Apple TV e Mac stanno qui: il resto delle schermate è lo stesso codice.

#if canImport(UIKit)
typealias PlatformImage = UIImage

extension UIImage {
    /// Pixel della foto, per calcolare il peso nella cache e ritagliare i pezzi del puzzle.
    var platformCGImage: CGImage? { cgImage }
}

extension Image {
    init(platformImage: PlatformImage) { self.init(uiImage: platformImage) }
}
#else
typealias PlatformImage = NSImage

extension NSImage {
    /// Immagine grande quanto i pixel (con `.zero` AppKit usa la misura del CGImage).
    convenience init(cgImage: CGImage) {
        self.init(cgImage: cgImage, size: .zero)
    }

    /// Pixel della foto, per calcolare il peso nella cache e ritagliare i pezzi del puzzle.
    var platformCGImage: CGImage? {
        var rect = CGRect(origin: .zero, size: size)
        return cgImage(forProposedRect: &rect, context: nil, hints: nil)
    }
}

extension Image {
    init(platformImage: PlatformImage) { self.init(nsImage: platformImage) }
}
#endif

extension View {
    /// Schede della home e dei giochi: su Apple TV lo stile `.card` del sistema (sollevamento e ombra
    /// con il fuoco); sul Mac lo stesso effetto al passaggio del mouse.
    @ViewBuilder
    func cardButtonStyle() -> some View {
        #if os(tvOS)
        buttonStyle(.card)
        #else
        buttonStyle(HoverCardButtonStyle())
        #endif
    }

    /// Play/Pausa: il tasto del telecomando su Apple TV, la barra spaziatrice sul Mac.
    @ViewBuilder
    func onPlayPause(perform action: @escaping () -> Void) -> some View {
        #if os(tvOS)
        onPlayPauseCommand(perform: action)
        #else
        onKeyPress(.space) {
            action()
            return .handled
        }
        #endif
    }

    /// Campi di testo per nomi di rete e indirizzi: niente maiuscola automatica dove la tastiera la
    /// metterebbe (Apple TV); sul Mac non serve.
    @ViewBuilder
    func plainTextInput() -> some View {
        #if os(tvOS)
        textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        autocorrectionDisabled()
        #endif
    }

    /// Sul Mac le schermate a tutto schermo prendono subito il fuoco della tastiera, così frecce,
    /// spazio ed Esc funzionano senza cliccare prima. Su Apple TV il fuoco lo gestisce il sistema.
    @ViewBuilder
    func keyboardFocusOnMac() -> some View {
        #if os(macOS)
        modifier(KeyboardFocusOnAppear())
        #else
        self
        #endif
    }
}

#if os(macOS)
/// Scheda che si solleva e si illumina al passaggio del mouse, come una scheda con il fuoco su tvOS.
private struct HoverCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        HoverCard(configuration: configuration)
    }

    private struct HoverCard: View {
        let configuration: ButtonStyleConfiguration
        @State private var hovering = false

        var body: some View {
            configuration.label
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(.white.opacity(hovering ? 0.9 : 0), lineWidth: 3)
                )
                .scaleEffect(configuration.isPressed ? 0.97 : (hovering ? 1.04 : 1))
                .shadow(color: .black.opacity(hovering ? 0.6 : 0.25), radius: hovering ? 22 : 8, y: hovering ? 12 : 4)
                .animation(.easeOut(duration: 0.15), value: hovering)
                .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
                .onHover { hovering = $0 }
                .contentShape(Rectangle())
        }
    }
}

private struct KeyboardFocusOnAppear: ViewModifier {
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focusable()
            .focusEffectDisabled()
            .focused($focused)
            .onAppear { focused = true }
    }
}
#endif

/// Schermo sempre acceso: su Apple TV il blocco del timer di inattività, sul Mac un'attività che
/// impedisce lo spegnimento dello schermo.
@MainActor
enum KeepAwake {
    #if os(macOS)
    private static var activity: NSObjectProtocol?
    #endif

    static func set(_ keep: Bool) {
        #if os(tvOS)
        UIApplication.shared.isIdleTimerDisabled = keep
        #else
        if keep, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(options: [.idleDisplaySleepDisabled, .userInitiated], reason: "Slideshow")
        } else if !keep, let current = activity {
            ProcessInfo.processInfo.endActivity(current)
            activity = nil
        }
        #endif
    }
}

/// Risoluzione dello schermo in pixel (lato lungo, lato corto), per la misura delle foto degli invitati.
@MainActor
func screenPixelSize() -> CGSize? {
    #if os(tvOS)
    let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    return scene?.screen.nativeBounds.size
    #else
    guard let screen = NSScreen.main else { return nil }
    return CGSize(width: screen.frame.width * screen.backingScaleFactor, height: screen.frame.height * screen.backingScaleFactor)
    #endif
}
