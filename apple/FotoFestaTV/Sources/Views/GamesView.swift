import FotoFestaCore
import SwiftUI

/// Numero minimo di foto perché i giochi abbiano senso.
let minimumGamePhotos = 2

struct GamesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        // I giochi usano solo le foto: i video si guardano nello slideshow.
        let playable = model.stillPhotos.count >= minimumGamePhotos
        let whoTookItPlayable = WhoTookIt.canPlay(model.photos)
        // I sondaggi si votano dai telefoni: servono la festa e la pagina raggiungibile.
        let partyOn = model.guests.active && model.guests.url != nil
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 48) {
                ScreenTitle(title: L("games.title"), subtitle: L("games.subtitle"))
                // Fila di schede come le altre di tvOS: con più giochi scorre di lato.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 44) {
                        gameCard(L("games.memory_title"), L("games.memory_body"), "square.grid.2x2.fill", .memory, enabled: playable)
                        gameCard(L("games.mystery_title"), L("games.mystery_body"), "eye.fill", .mystery, enabled: playable)
                        gameCard(L("games.puzzle_title"), L("games.puzzle_body"), "puzzlepiece.fill", .puzzle, enabled: playable)
                        // Si gioca solo con le foto della festa: senza, la scheda spiega cosa serve.
                        gameCard(
                            L("games.who_title"),
                            whoTookItPlayable ? L("games.who_body") : L("games.who_unavailable"),
                            "camera.fill",
                            .whoTookIt,
                            enabled: whoTookItPlayable
                        )
                        gameCard(L("games.shows_title"), L("games.shows_body"), "theatermasks.fill", .shows, enabled: true)
                        gameCard(
                            L("games.party_title"),
                            partyOn ? L("games.party_body") : L("games.poll_unavailable"),
                            "party.popper.fill",
                            .partyGames,
                            enabled: partyOn
                        )
                        gameCard(
                            L("games.poll_title"),
                            partyOn ? L("games.poll_body") : L("games.poll_unavailable"),
                            "chart.bar.fill",
                            .poll,
                            enabled: partyOn
                        )
                        Button { model.startDirector() } label: {
                            GameCardLabel(
                                title: L("games.director_title"),
                                subtitle: partyOn ? L("games.director_body") : L("games.poll_unavailable"),
                                systemImage: "video.fill",
                                artwork: "demo_birthday.jpg"
                            )
                        }
                        .cardButtonStyle()
                        .disabled(!partyOn)
                        Button { model.startQuiet() } label: {
                            GameCardLabel(
                                title: L("games.quiet_title"),
                                subtitle: partyOn ? L("games.quiet_body") : L("games.poll_unavailable"),
                                systemImage: "iphone.slash",
                                artwork: "demo_mountains.jpg"
                            )
                        }
                        .cardButtonStyle()
                        .disabled(!partyOn)
                        gameCard(L("games.lottery_title"), L("games.lottery_body"), "dice.fill", .lottery, enabled: true)
                    }
                    // Spazio per il sollevamento delle schede con il fuoco.
                    .padding(.vertical, 40)
                }
                .scrollClipDisabled()
                Text(playable ? L("games.active_collection", model.stillPhotos.count) : L("games.need_photos", minimumGamePhotos))
                    .foregroundStyle(Theme.secondaryText)
                Spacer(minLength: 0)
            }
        }
    }

    private func gameCard(_ title: String, _ subtitle: String, _ image: String, _ route: Route, enabled: Bool) -> some View {
        NavigationLink(value: route) {
            GameCardLabel(title: title, subtitle: subtitle, systemImage: image, artwork: artwork(for: route))
        }
        .cardButtonStyle()
        .disabled(!enabled)
    }

    private func artwork(for route: Route) -> String {
        switch route {
        case .memory: return "demo_coast.jpg"
        case .puzzle: return "demo_picnic.jpg"
        case .whoTookIt: return "demo_birthday.jpg"
        case .poll: return "demo_picnic.jpg"
        case .lottery: return "demo_coast.jpg"
        default: return "demo_mountains.jpg"
        }
    }
}

/// Scheda di un gioco, nello stile delle schede di tvOS: foto demo, icona, nome e descrizione.
private struct GameCardLabel: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let artwork: String

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            PhotoImage(photo: Photo(id: "artwork:\(artwork)", title: title, source: .bundled(fileName: artwork)), fitMode: .crop, maxPixelSize: 1200, placeholder: false)
                .accessibilityHidden(true)
            LinearGradient(colors: [.clear, .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: systemImage).font(.system(size: 48, weight: .semibold))
                Text(title).font(.title2.bold())
                Text(subtitle).font(.callout).foregroundStyle(Theme.secondaryText).lineLimit(2)
            }
            .foregroundStyle(.white)
            .padding(36)
        }
        .frame(width: 540, height: 304)
        .accessibilityElement(children: .combine)
    }
}

struct MemoryGameView: View {
    @Environment(AppModel.self) private var model
    @State private var cards: [Card] = []
    @State private var moves = 0
    @State private var checking = false
    @State private var gameID = UUID()

    struct Card: Identifiable {
        let id: Int
        let photo: Photo
        var revealed = false
        var matched = false
    }

    var body: some View {
        let complete = !cards.isEmpty && cards.allSatisfy(\.matched)
        VStack(alignment: .leading, spacing: 32) {
            HStack {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("memory.kicker", moves)).font(.headline).foregroundStyle(Theme.secondaryText)
                    Text(complete ? L("memory.complete", moves) : L("memory.find_pairs")).font(.title.bold())
                }
                Spacer()
                Button(L("memory.new_game")) { newGame() }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 32), count: 4), spacing: 32) {
                ForEach(cards.indices, id: \.self) { index in
                    Button {
                        reveal(index)
                    } label: {
                        cardFace(cards[index], position: index)
                    }
                    .cardButtonStyle()
                }
            }
            Spacer(minLength: 0)
        }
        .padding(64)
        .background(Theme.background.ignoresSafeArea())
        .onAppear { if cards.isEmpty { newGame() } }
    }

    @ViewBuilder
    private func cardFace(_ card: Card, position: Int) -> some View {
        ZStack {
            if card.revealed || card.matched {
                PhotoImage(photo: card.photo, fitMode: .crop, maxPixelSize: 800, accessibilityLabel: card.matched ? L("memory.card_matched", card.photo.title) : card.photo.title)
            } else {
                LinearGradient(colors: [Color(white: 0.34), Color(white: 0.16)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Text("?").font(.system(size: 80, weight: .bold)).foregroundStyle(.white)
                    .accessibilityLabel(Text(L("memory.card_covered", position + 1)))
            }
        }
        .frame(height: 220)
    }

    private func newGame() {
        let chosen = Array(model.stillPhotos.shuffled().prefix(4))
        cards = (chosen + chosen).shuffled().enumerated().map { Card(id: $0.offset, photo: $0.element) }
        moves = 0
        checking = false
        gameID = UUID()
    }

    private func reveal(_ index: Int) {
        guard !checking, cards.indices.contains(index), !cards[index].revealed, !cards[index].matched else { return }
        cards[index].revealed = true
        let open = cards.indices.filter { cards[$0].revealed && !cards[$0].matched }
        guard open.count == 2 else { return }
        moves += 1
        checking = true
        let same = cards[open[0]].photo.id == cards[open[1]].photo.id
        let game = gameID
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: same ? 450_000_000 : 900_000_000)
            // Una nuova partita nel frattempo invalida il controllo (gli id delle carte sono solo posizioni).
            guard gameID == game else { return }
            for position in open {
                if same { cards[position].matched = true } else { cards[position].revealed = false }
            }
            checking = false
        }
    }
}

struct MysteryGameView: View {
    @Environment(AppModel.self) private var model
    @State private var answer: Photo?
    @State private var choices: [Photo] = []
    @State private var revealed = false
    @State private var hintLevel = 0
    @State private var feedback = L("mystery.prompt")
    /// Con i telefoni rispondono gli invitati: la foto si fa più nitida da sola ogni tanto e il
    /// telecomando mostra la risposta.
    @State private var phoneMode = false
    @FocusState private var newPhotoFocused: Bool

    private static let phoneHintDelay: Duration = .seconds(8)

    /// Indirizzo della pagina della festa: il gioco con i telefoni c'è solo durante la festa.
    private var phoneURL: String? { model.guests.active ? model.guests.url : nil }
    private var phoneGame: PhoneGame { model.guests.phoneGame }

    var body: some View {
        HStack(spacing: 56) {
            VStack(spacing: 28) {
                ZStack {
                    Color(white: 0.12)
                    if let answer {
                        PhotoImage(photo: answer, fitMode: .crop, maxPixelSize: 1600, accessibilityLabel: revealed ? answer.title : L("mystery.hidden_photo"))
                            .blur(radius: revealed ? 0 : CGFloat(40 - hintLevel * 12))
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 28))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if phoneMode, let phoneURL {
                    PhonePlayPanel(game: phoneGame, url: phoneURL, revealed: revealed)
                }
            }

            VStack(alignment: .leading, spacing: 24) {
                Text(L("games.mystery_title")).font(.headline).foregroundStyle(Theme.secondaryText)
                Text(revealed ? L("mystery.solved", answer?.title ?? "") : L("mystery.question"))
                    .font(.title2.bold())
                    .fixedSize(horizontal: false, vertical: true)
                if phoneMode {
                    VStack(spacing: 12) {
                        ForEach(choices) { choice in
                            ChoiceLabel(text: choice.title, highlighted: revealed && choice.id == answer?.id)
                        }
                    }
                    if !revealed {
                        Button(L("phone.reveal")) {
                            phoneGame.reveal()
                            revealed = true
                            newPhotoFocused = true
                        }
                    }
                } else {
                    Text(feedback).font(.title3.bold()).fixedSize(horizontal: false, vertical: true)
                    ForEach(choices) { choice in
                        Button(choice.title) { choose(choice) }
                            .disabled(revealed)
                    }
                }
                Button(L("mystery.new_photo")) { newRound() }
                    .focused($newPhotoFocused)
                if phoneURL != nil {
                    Button(phoneMode ? L("phone.mode_remote") : L("phone.mode_phones")) { togglePhoneMode() }
                }
            }
            .frame(width: 640, alignment: .leading)
        }
        .padding(64)
        .background(Theme.background.ignoresSafeArea())
        .onAppear { if answer == nil { newRound() } }
        .onDisappear {
            if phoneMode { phoneGame.stop() }
        }
        // Se la festa finisce durante la partita si torna al telecomando.
        .onChange(of: phoneURL) { _, url in
            if url == nil, phoneMode { togglePhoneMode() }
        }
        // Con i telefoni nessuno sbaglia dal telecomando: la foto si fa più nitida col tempo.
        .task(id: "\(answer?.id ?? "")|\(phoneMode)") {
            guard phoneMode else { return }
            for _ in 0..<2 {
                try? await Task.sleep(for: Self.phoneHintDelay)
                guard !Task.isCancelled else { return }
                if !revealed { hintLevel = min(hintLevel + 1, 2) }
            }
        }
    }

    private func choose(_ choice: Photo) {
        if choice.id == answer?.id {
            revealed = true
            feedback = L("mystery.correct")
            // Le scelte si disattivano: il fuoco passa a "Nuova foto".
            newPhotoFocused = true
        } else {
            hintLevel = min(hintLevel + 1, 2)
            feedback = L("mystery.wrong")
        }
    }

    private func newRound() {
        let photos = model.stillPhotos
        guard photos.count >= minimumGamePhotos else { return }
        let candidates = photos.filter { $0.id != answer?.id }
        let next = (candidates.isEmpty ? photos : candidates).randomElement()!
        answer = next
        choices = ([next] + photos.filter { $0.id != next.id }.shuffled().prefix(2)).shuffled()
        revealed = false
        hintLevel = 0
        feedback = L("mystery.prompt")
        if phoneMode { ask() }
    }

    /// Cambiando modalità a foto già indovinata si passa alla prossima.
    private func togglePhoneMode() {
        let resolved = revealed
        phoneMode.toggle()
        if phoneMode {
            phoneGame.resetScores()
        } else {
            phoneGame.stop()
        }
        if resolved {
            newRound()
        } else if phoneMode {
            ask()
        }
    }

    /// La domanda va alla pagina della festa: i telefoni mostrano i titoli tra cui scegliere.
    private func ask() {
        guard let answer, let correct = choices.firstIndex(where: { $0.id == answer.id }) else { return }
        phoneGame.ask(question: L("mystery.question"), choices: choices.map(\.title), correct: correct)
    }
}
