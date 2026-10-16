import SwiftUI
import UIKit

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.path) {
            HomeView()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .slideshow: SlideshowView()
                    case .settings: SettingsView()
                    case .games: GamesView()
                    case .memory: MemoryGameView()
                    case .mystery: MysteryGameView()
                    case .googleConnect: GoogleConnectView()
                    case .guests: GuestsView()
                    case .guestReview: GuestReviewView()
                    case .guestWifi: GuestWifiView()
                    }
                }
        }
        .overlay(alignment: .top) {
            if let message = model.message {
                Text(message)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 44)
                    .padding(.vertical, 26)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .shadow(color: .black.opacity(0.5), radius: 30, y: 12)
                    .padding(.top, 40)
                    .frame(maxWidth: 1200)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: model.message)
        // Il server per le foto degli invitati resta acceso solo con l'app in primo piano. "Inattiva"
        // (Centro di Controllo aperto sopra l'app) non conta come uscita.
        .onChange(of: scenePhase, initial: true) { _, phase in
            model.guests.setForeground(phase != .background)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
            model.guests.lowMemory()
        }
        // Schermo sempre acceso durante lo slideshow in riproduzione, mentre il QR della festa è
        // visibile e durante l'approvazione. Deciso in un solo punto: con più schermate gli eventi di
        // comparsa e scomparsa arrivano in ordine diverso e si annullerebbero a vicenda.
        .onChange(of: keepAwake, initial: true) { _, keep in
            UIApplication.shared.isIdleTimerDisabled = keep
        }
    }

    private var keepAwake: Bool {
        switch model.path.last {
        case .slideshow: return model.isPlaying
        case .guests: return model.guests.active
        case .guestReview, .memory, .mystery: return true
        case .googleConnect:
            if case .working = model.googleState { return true }
            return false
        default: return false
        }
    }
}
