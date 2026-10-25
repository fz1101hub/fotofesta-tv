import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.path) {
            HomeView()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .slideshow: SlideshowView().portrait(model.settings.portrait)
                    case .settings: KioskGate(purpose: .settings) { SettingsView() }
                    case .games: GamesView()
                    case .memory: MemoryGameView()
                    case .mystery: MysteryGameView()
                    case .puzzle: PuzzleGameView()
                    case .whoTookIt: WhoTookItView()
                    case .poll: PollView()
                    case .lottery: LotteryView()
                    case .googleConnect: GoogleConnectView()
                    case .guests: GuestsView()
                    case .guestReview: GuestReviewView()
                    case .guestWifi: GuestWifiView()
                    case .awards: AwardsView()
                    case .guestbook: GuestbookView()
                    case .announce: AnnounceView()
                    case .library: LibraryView()
                    case .network: NetworkView()
                    case .partyGames: PartyGamesView()
                    case .play: PlayView()
                    case .tournament: TournamentView()
                    case .bets: BetsView()
                    case .toast: ToastView()
                    case .coins: CoinsView()
                    case .shows: ShowsView()
                    case .rewind: RewindView()
                    case .barcode: BarcodeView()
                    case .photoMosaic: PhotoMosaicView()
                    case .credits: CreditsView()
                    case .teletext: TeletextView()
                    case .sky: SkyView()
                    case .stats: StatsView()
                    case .door: DoorView()
                    case .phoneWall: PhoneWallView()
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
        // Chiosco: scelta del PIN, spegnimento e fine della festa.
        .sheet(item: $model.pinRequest) { purpose in
            PinPadView(purpose: purpose) { pin in
                model.submitPin(pin, for: purpose)
            } onCancel: {
                model.cancelPin()
            }
        }
        // Il server per le foto degli invitati resta acceso solo con l'app in primo piano. "Inattiva"
        // (Centro di Controllo aperto sopra l'app) non conta come uscita.
        .onChange(of: scenePhase, initial: true) { _, phase in
            model.guests.setForeground(phase != .background)
        }
        #if os(tvOS)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
            model.guests.lowMemory()
        }
        #endif
        // Schermo sempre acceso durante lo slideshow in riproduzione, mentre il QR della festa è
        // visibile e durante l'approvazione. Deciso in un solo punto: con più schermate gli eventi di
        // comparsa e scomparsa arrivano in ordine diverso e si annullerebbero a vicenda.
        .onChange(of: keepAwake, initial: true) { _, keep in
            KeepAwake.set(keep)
        }
        #if DEBUG
        .task { model.applyScreenshotScenario() }
        #endif
    }

    private var keepAwake: Bool {
        switch model.path.last {
        case .slideshow: return model.isPlaying
        case .guests: return model.guests.active
        case .guestReview, .memory, .mystery, .puzzle, .whoTookIt, .awards, .poll, .lottery, .guestbook, .announce: return true
        case .partyGames, .play, .tournament, .bets, .toast, .coins: return true
        case .shows, .rewind, .barcode, .photoMosaic, .credits, .teletext, .sky, .stats: return true
        case .door, .phoneWall: return true
        case .googleConnect:
            if case .working = model.googleState { return true }
            return false
        default: return false
        }
    }
}
