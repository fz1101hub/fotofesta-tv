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
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("games.kicker").uppercased()).font(.headline).foregroundStyle(Theme.coral)
                    Text(L("games.title")).font(.largeTitle.bold())
                    Text(L("games.subtitle")).foregroundStyle(Theme.secondaryText)
                }
                HStack(spacing: 48) {
                    gameCard(L("games.memory_title"), L("games.memory_body"), "square.grid.2x2", Theme.coral, .memory, enabled: playable)
                    gameCard(L("games.mystery_title"), L("games.mystery_body"), "eye", Theme.cyan, .mystery, enabled: playable)
                }
                Text(playable ? L("games.active_collection", model.photos.count) : L("games.need_photos", minimumGamePhotos))
                    .foregroundStyle(Theme.secondaryText)
                Spacer(minLength: 0)
            }
            .padding(80)
        }
    }

    private func gameCard(_ title: String, _ subtitle: String, _ image: String, _ tint: Color, _ route: Route, enabled: Bool) -> some View {
        NavigationLink(value: route) {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: image).font(.system(size: 56, weight: .semibold)).foregroundStyle(tint)
                Text(title).font(.title2.bold())
                Text(subtitle).font(.callout).foregroundStyle(Theme.secondaryText).lineLimit(3)
            }
            .padding(36)
            .frame(width: 620, height: 300, alignment: .topLeading)
            .background(LinearGradient(colors: [tint.opacity(0.35), Theme.navySoft], startPoint: .topLeading, endPoint: .bottomTrailing))
        }
        .buttonStyle(.card)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

struct MemoryGameView: View {
    @Environment(AppModel.self) private var model
    @State private var cards: [Card] = []
    @State private var moves = 0
    @State private var checking = false
    @State private var gameGeneration = 0

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
                    Text(L("memory.kicker", moves).uppercased()).font(.headline).foregroundStyle(Theme.coral)
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
                LinearGradient(colors: [Theme.coral, Color(red: 0.49, green: 0.24, blue: 0.6)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Text("?").font(.system(size: 80, weight: .bold))
                    .accessibilityLabel(Text(L("memory.card_covered", position + 1)))
            }
        }
        .frame(height: 220)
    }

    private func newGame() {
        // Ogni nuova partita invalida le verifiche asincrone della partita precedente.
        gameGeneration += 1
        let chosen = Array(model.photos.shuffled().prefix(4))
        cards = (chosen + chosen).shuffled().enumerated().map { Card(id: $0.offset, photo: $0.element) }
        moves = 0
        checking = false
    }

    private func reveal(_ index: Int) {
        guard !checking, cards.indices.contains(index), !cards[index].revealed, !cards[index].matched else { return }
        cards[index].revealed = true
        let open = cards.indices.filter { cards[$0].revealed && !cards[$0].matched }
        guard open.count == 2 else { return }
        moves += 1
        checking = true
        let same = cards[open[0]].photo.id == cards[open[1]].photo.id
        let generation = gameGeneration
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: same ? 450_000_000 : 900_000_000)
            // Gli ID delle carte si ripetono; serve la generazione, non cards.map(\.id).
            guard gameGeneration == generation, !Task.isCancelled else { return }
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
                Theme.navySoft
                if let answer {
                    PhotoImage(photo: answer, fitMode: .crop, maxPixelSize: 1600, accessibilityLabel: revealed ? answer.title : L("mystery.hidden_photo"))
                        .blur(radius: revealed ? 0 : CGFloat(40 - hintLevel * 12))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 28))
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 24) {
                Text(L("games.mystery_title").uppercased()).font(.headline).foregroundStyle(Theme.coral)
                Text(revealed ? L("mystery.solved", answer?.title ?? "") : L("mystery.question")).font(.title2.bold())
                Text(feedback).foregroundStyle(revealed ? Theme.cyan : Theme.amber)
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
