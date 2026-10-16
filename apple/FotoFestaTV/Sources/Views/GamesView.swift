import FotoFestaCore
import SwiftUI

/// Numero minimo di foto perché i giochi abbiano senso.
let minimumGamePhotos = 2

struct GamesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let playable = model.photos.count >= minimumGamePhotos
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
                    }
                    // Spazio per il sollevamento delle schede con il fuoco.
                    .padding(.vertical, 40)
                }
                .scrollClipDisabled()
                Text(playable ? L("games.active_collection", model.photos.count) : L("games.need_photos", minimumGamePhotos))
                    .foregroundStyle(Theme.secondaryText)
                Spacer(minLength: 0)
            }
        }
    }

    private func gameCard(_ title: String, _ subtitle: String, _ image: String, _ route: Route, enabled: Bool) -> some View {
        NavigationLink(value: route) {
            GameCardLabel(title: title, subtitle: subtitle, systemImage: image, artwork: artwork(for: route))
        }
        .buttonStyle(.card)
        .disabled(!enabled)
    }

    private func artwork(for route: Route) -> String {
        switch route {
        case .memory: return "demo_coast.jpg"
        case .puzzle: return "demo_picnic.jpg"
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
                    .buttonStyle(.card)
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
        let chosen = Array(model.photos.shuffled().prefix(4))
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

    var body: some View {
        HStack(spacing: 56) {
            ZStack {
                Color(white: 0.12)
                if let answer {
                    PhotoImage(photo: answer, fitMode: .crop, maxPixelSize: 1600, accessibilityLabel: revealed ? answer.title : L("mystery.hidden_photo"))
                        .blur(radius: revealed ? 0 : CGFloat(40 - hintLevel * 12))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 28))
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 24) {
                Text(L("games.mystery_title")).font(.headline).foregroundStyle(Theme.secondaryText)
                Text(revealed ? L("mystery.solved", answer?.title ?? "") : L("mystery.question"))
                    .font(.title2.bold())
                    .fixedSize(horizontal: false, vertical: true)
                Text(feedback).font(.title3.bold()).fixedSize(horizontal: false, vertical: true)
                ForEach(choices) { choice in
                    Button(choice.title) { choose(choice) }
                        .disabled(revealed)
                }
                Button(L("mystery.new_photo")) { newRound() }
            }
            .frame(width: 640)
        }
        .padding(64)
        .background(Theme.background.ignoresSafeArea())
        .onAppear { if answer == nil { newRound() } }
    }

    private func choose(_ choice: Photo) {
        if choice.id == answer?.id {
            revealed = true
            feedback = L("mystery.correct")
        } else {
            hintLevel = min(hintLevel + 1, 2)
            feedback = L("mystery.wrong")
        }
    }

    private func newRound() {
        let photos = model.photos
        guard photos.count >= minimumGamePhotos else { return }
        let candidates = photos.filter { $0.id != answer?.id }
        let next = (candidates.isEmpty ? photos : candidates).randomElement()!
        answer = next
        choices = ([next] + photos.filter { $0.id != next.id }.shuffled().prefix(2)).shuffled()
        revealed = false
        hintLevel = 0
        feedback = L("mystery.prompt")
    }
}
