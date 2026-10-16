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
    @FocusState private var nextFocused: Bool

    var body: some View {
        Group {
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
        .background(Theme.background.ignoresSafeArea())
        .onAppear {
            guard !started else { return }
            started = true
            photos = model.photos
            nextRound()
        }
    }

    private func game(_ round: WhoTookItRound) -> some View {
        HStack(spacing: 56) {
            // La foto intera, con la stessa foto sfocata ai lati. L'etichetta non dice il titolo:
            // "Foto di Giulia" svelerebbe la risposta a chi usa VoiceOver.
            ZStack {
                BlurredBackdrop(photo: round.photo)
                PhotoImage(photo: round.photo, fitMode: .contain, maxPixelSize: 1920, accessibilityLabel: L("who.photo"))
            }
            .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 24) {
                Text(L("who.kicker", score, played)).font(.headline).foregroundStyle(Theme.secondaryText)
                Text(title(round))
                    .font(.title2.bold())
                    .fixedSize(horizontal: false, vertical: true)
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
                Button(L("who.next")) { nextRound() }
                    .focused($nextFocused)
            }
            .frame(width: 640, alignment: .leading)
        }
        .padding(64)
    }

    private func title(_ round: WhoTookItRound) -> String {
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
    }
}
