import SwiftUI
#if os(macOS)
import AppKit
#endif

@main
struct FotoFestaTVApp: App {
    @State private var model = AppModel()
    #if os(macOS)
    @NSApplicationDelegateAdaptor private var delegate: MacAppDelegate
    #endif

    var body: some Scene {
        #if os(macOS)
        // Una sola finestra: due finestre avrebbero due feste e due server per gli invitati.
        Window("FotoFesta TV", id: "main") {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .frame(minWidth: 1100, minHeight: 680)
        }
        .defaultSize(width: 1440, height: 900)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
        #else
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
        }
        #endif
    }
}

#if os(macOS)
/// Chiusa la finestra l'app termina: la festa e il server per gli invitati non restano attivi di nascosto.
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
#endif
