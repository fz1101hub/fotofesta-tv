import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

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
                    }
                }
        }
        .overlay(alignment: .top) {
            if let message = model.message {
                Text(message)
                    .font(.headline)
                    .foregroundStyle(Theme.amber)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 18)
                    .background(Theme.navySoft.opacity(0.95), in: RoundedRectangle(cornerRadius: 18))
                    .padding(.top, 40)
                    .frame(maxWidth: 1200)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: model.message)
    }
}
