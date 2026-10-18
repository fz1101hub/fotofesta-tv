import FotoFestaCore
import SwiftUI

/// "Chi l'ha scattata?": una foto della festa e i nomi degli invitati; si indovina chi l'ha mandata.
struct WhoTookItView: View {
    @Environment(AppModel.self) private var model
    /// Foto presenti all'apertura del gioco: quelle che arrivano durante la partita non la cambiano.
    @State private var photos: [Photo] = []
    @State private var round: WhoTookItRound?
    @State private var chosen: String?
    @State private var score = 0
    @State private var played = 0
    @State private var started = false
    /// Con i telefoni rispondono gli invitati; il telecomando mostra la risposta e passa alla foto dopo.
    @State private var phoneMode = false
    @State private var revealed = false
    @FocusState private var nextFocused: Bool

    /// Indirizzo della pagina della festa: il gioco con i telefoni c'è solo durante la festa.
    private var phoneURL: String? { model.guests.active ? model.guests.url : nil }
    private var phoneGame: PhoneGame { model.guests.phoneGame }

    var body: some View {
        // Lo sfondo c'è sempre: una vista vuota non riceverebbe onAppear e il gioco non partirebbe.
        ZStack {
            Theme.background.ignoresSafeArea()
            if let round {
                game(round)
            } else if started {
                VStack(alignment: .leading, spacing: 24) {
                    ScreenTitle(title: L("who.unavailable_title"), subtitle: L("games.who_unavailable"))
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            photos = model.photos
            nextRound()
            #if DEBUG
            playScreenshotScenario()
            #endif
        }
        .onDisappear {
            if phoneMode { phoneGame.stop() }
        }
        // Se la festa finisce durante la partita si torna al telecomando.
        .onChange(of: phoneURL) { _, url in
            if url == nil, phoneMode { togglePhoneMode() }
        }
    }

    private func game(_ round: WhoTookItRound) -> some View {
        HStack(spacing: 56) {
            VStack(spacing: 28) {
                // La foto intera, con la stessa foto sfocata ai lati. L'etichetta non dice il titolo:
                // "Foto di Giulia" svelerebbe la risposta a chi usa VoiceOver.
                ZStack {
                    BlurredBackdrop(photo: round.photo)
                    PhotoImage(photo: round.photo, fitMode: .contain, maxPixelSize: 1920, accessibilityLabel: L("who.photo"))
                }
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if phoneMode, let phoneURL {
                    PhonePlayPanel(game: phoneGame, url: phoneURL, revealed: revealed)
                }
            }

            VStack(alignment: .leading, spacing: 24) {
                Text(phoneMode ? L("games.who_title") : L("who.kicker", score, played))
                    .font(.headline)
                    .foregroundStyle(Theme.secondaryText)
                Text(title(round))
                    .font(.title2.bold())
                    .fixedSize(horizontal: false, vertical: true)
                if phoneMode {
                    VStack(spacing: 12) {
                        ForEach(round.choices, id: \.self) { name in
                            ChoiceLabel(text: name, highlighted: revealed && WhoTookIt.isAnswer(round, name))
                        }
                    }
                    if !revealed {
                        Button(L("phone.reveal")) { reveal() }
                    }
                } else {
                    remoteChoices(round)
                }
                Button(L("who.next")) { nextRound() }
                    .focused($nextFocused)
                if phoneURL != nil {
                    Button(phoneMode ? L("phone.mode_remote") : L("phone.mode_phones")) { togglePhoneMode() }
                }
            }
            .frame(width: 640, alignment: .leading)
        }
        .padding(64)
    }

    @ViewBuilder
    private func remoteChoices(_ round: WhoTookItRound) -> some View {
        ForEach(round.choices, id: \.self) { name in
            Button {
                choose(name, in: round)
            } label: {
                if chosen != nil, WhoTookIt.isAnswer(round, name) {
                    Label(name, systemImage: "checkmark")
                } else {
                    Text(name)
                }
            }
            .disabled(chosen != nil)
        }
    }

    private func title(_ round: WhoTookItRound) -> String {
        if phoneMode { return revealed ? L("phone.reveal_who", round.answer) : L("who.question") }
        guard let chosen else { return L("who.question") }
        return WhoTookIt.isAnswer(round, chosen) ? L("who.correct", round.answer) : L("who.wrong", round.answer)
    }

    private func choose(_ name: String, in round: WhoTookItRound) {
        guard chosen == nil else { return }
        chosen = name
        played += 1
        if WhoTookIt.isAnswer(round, name) { score += 1 }
        // I nomi si disattivano: il fuoco passa a "Prossima foto".
        nextFocused = true
    }

    private func nextRound() {
        round = WhoTookIt.round(photos, previousPhotoID: round?.photo.id)
        chosen = nil
        revealed = false
        if phoneMode, let round { ask(round) }
    }

    private func reveal() {
        phoneGame.reveal()
        revealed = true
        nextFocused = true
    }

    #if DEBUG
    /// Schermata "who_phone": gioco con i telefoni; lo script risponde da due telefoni, poi si
    /// mostra la risposta.
    private func playScreenshotScenario() {
        guard ProcessInfo.processInfo.environment["FOTOFESTA_SCREENSHOT"] == "who_phone" else { return }
        Task {
            await waitFor { round != nil && phoneURL != nil }
            togglePhoneMode()
            await waitFor { phoneGame.answerCount() >= 2 }
            reveal()
        }
    }

    /// Attende al massimo mezzo minuto che la condizione sia vera.
    private func waitFor(_ condition: () -> Bool) async {
        var attempts = 0
        while !condition(), attempts < 60 {
            attempts += 1
            try? await Task.sleep(for: .milliseconds(500))
        }
    }
    #endif

    /// Cambiando modalità a domanda già risolta si passa alla foto dopo.
    private func togglePhoneMode() {
        let resolved = phoneMode ? revealed : chosen != nil
        phoneMode.toggle()
        revealed = false
        if phoneMode {
            phoneGame.resetScores()
        } else {
            phoneGame.stop()
        }
        if resolved {
            nextRound()
        } else if phoneMode, let round {
            ask(round)
        }
    }

    /// La domanda va alla pagina della festa: i telefoni mostrano i nomi tra cui scegliere.
    private func ask(_ round: WhoTookItRound) {
        phoneGame.ask(question: L("who.question"), choices: round.choices, correct: round.choices.firstIndex(of: round.answer) ?? 0)
    }
}
