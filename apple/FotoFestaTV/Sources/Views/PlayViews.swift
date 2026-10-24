import FotoFestaCore
import SwiftUI

// MARK: - Giochi con i telefoni: menu, gioco in corso, torneo, scommesse, brindisi e monete

/// Colori dei disegni, nello stesso ordine della tavolozza della pagina dei telefoni.
private let drawingColors: [Color] = [
    Color(red: 0.07, green: 0.07, blue: 0.07), Color(red: 0.9, green: 0.22, blue: 0.21), Color(red: 0.98, green: 0.55, blue: 0),
    Color(red: 0.99, green: 0.85, blue: 0.21), Color(red: 0.26, green: 0.63, blue: 0.28), Color(red: 0.12, green: 0.53, blue: 0.9),
    Color(red: 0.56, green: 0.14, blue: 0.67), .white,
]

extension PartyPlay.Kind {
    var title: String {
        switch self {
        case .drawGuess: L("play.title_draw_guess")
        case .drawingWall: L("play.title_wall")
        case .story: L("play.title_story")
        case .whisper: L("play.title_whisper")
        case .compliments: L("play.title_compliments")
        case .missions: L("play.title_missions")
        case .paparazzi: L("play.title_paparazzi")
        case .sameMoment: L("play.title_moment")
        case .recreate: L("play.title_recreate")
        case .mole: L("play.title_mole")
        case .capsule: L("play.title_capsule")
        }
    }

    var summary: String {
        switch self {
        case .drawGuess: L("play.body_draw_guess")
        case .drawingWall: L("play.body_wall")
        case .story: L("play.body_story")
        case .whisper: L("play.body_whisper")
        case .compliments: L("play.body_compliments")
        case .missions: L("play.body_missions")
        case .paparazzi: L("play.body_paparazzi")
        case .sameMoment: L("play.body_moment")
        case .recreate: L("play.body_recreate")
        case .mole: L("play.body_mole")
        case .capsule: L("play.body_capsule")
        }
    }

    var systemImage: String {
        switch self {
        case .drawGuess: "paintbrush.pointed.fill"
        case .drawingWall: "paintpalette.fill"
        case .story: "book.fill"
        case .whisper: "bubble.left.and.bubble.right.fill"
        case .compliments: "heart.fill"
        case .missions: "flag.fill"
        case .paparazzi: "camera.fill"
        case .sameMoment: "timer"
        case .recreate: "arrow.counterclockwise"
        case .mole: "magnifyingglass"
        case .capsule: "hourglass"
        }
    }
}

private struct PlayTile: Identifiable {
    let id: String
    let title: String
    let body: String
    let systemImage: String
    let action: () -> Void
}

/// Tutti i giochi con i telefoni, in una griglia; in fondo i telefoni come luci.
struct PartyGamesView: View {
    @Environment(AppModel.self) private var model

    private var partyOn: Bool { model.guests.active && model.guests.url != nil }

    private var tiles: [PlayTile] {
        PartyPlay.Kind.allCases.map { kind in
            PlayTile(id: kind.rawValue, title: kind.title, body: kind.summary, systemImage: kind.systemImage) { _ = model.startPlay(kind) }
        } + [
            PlayTile(id: "tournament", title: L("play.title_tournament"), body: L("play.body_tournament"), systemImage: "trophy.fill") { model.path.append(.tournament) },
            PlayTile(id: "bets", title: L("play.title_bets"), body: L("play.body_bets"), systemImage: "banknote.fill") { model.path.append(.bets) },
            PlayTile(id: "toast", title: L("play.title_toast"), body: L("play.body_toast"), systemImage: "wineglass.fill") { model.path.append(.toast) },
            PlayTile(id: "treasure", title: L("play.title_treasure"), body: L("play.body_treasure"), systemImage: "qrcode") { model.startTreasure() },
            PlayTile(id: "coins", title: L("play.title_coins"), body: L("play.body_coins"), systemImage: "dollarsign.circle.fill") { model.path.append(.coins) },
        ]
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("games.party_title"), subtitle: partyOn ? L("games.party_body") : L("games.poll_unavailable"))
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 32), count: 4), spacing: 32) {
                        ForEach(tiles) { tile in
                            Button(action: tile.action) {
                                HStack(alignment: .top, spacing: 18) {
                                    Image(systemName: tile.systemImage)
                                        .font(.system(size: 40, weight: .semibold))
                                        .foregroundStyle(Theme.highlight)
                                        .frame(width: 52)
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(tile.title).font(.headline).lineLimit(1)
                                        Text(tile.body).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(3)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(24)
                                .frame(maxWidth: .infinity, minHeight: 170, alignment: .topLeading)
                            }
                            .cardButtonStyle()
                            .disabled(!partyOn)
                        }
                    }
                    .padding(.vertical, 30)
                }
                .scrollClipDisabled()
                HStack(spacing: 24) {
                    Label(L("play.title_lights"), systemImage: "lightbulb.fill").font(.headline)
                    ForEach(PartyPlay.Lights.allCases, id: \.self) { lights in
                        Button(lightsName(lights)) { model.setLights(lights) }
                            .tint(model.guests.lights == lights ? Theme.highlight : nil)
                            .disabled(!partyOn)
                    }
                    Button(L("lights.off")) { model.setLights(nil) }
                        .disabled(!partyOn || model.guests.lights == nil)
                }
            }
            if let url = model.treasureURL {
                TreasureOverlay(url: url, finders: model.guests.treasureFinders).padding(40)
            }
        }
    }
}

private func lightsName(_ lights: PartyPlay.Lights) -> String {
    switch lights {
    case .rainbow: L("lights.rainbow")
    case .wave: L("lights.wave")
    case .pulse: L("lights.pulse")
    case .candle: L("lights.candle")
    }
}

/// Gioco in corso: cosa succede sui telefoni, con il QR della festa e le monete a lato.
struct PlayView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let game = model.guests.play
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: game?.kind.title ?? L("games.party_title"), subtitle: game?.prompt)
                if let game {
                    HStack(alignment: .top, spacing: 48) {
                        PlayContent(game: game)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        PlaySidePanel(url: model.guests.url, game: game, coins: model.guests.coins)
                            .frame(width: 460)
                    }
                    actions(game)
                }
                Spacer(minLength: 0)
            }
        }
        .onDisappear { model.stopPlay() }
    }

    @ViewBuilder
    private func actions(_ game: PartyPlay.State) -> some View {
        let endsWithReveal = game.kind != .compliments && game.kind != .drawingWall
        HStack(spacing: 24) {
            if game.kind == .mole, !game.voting, !game.revealed {
                Button(L("play.vote")) { model.playVoting() }
            } else if endsWithReveal, !game.revealed {
                Button(L("play.reveal")) { model.playReveal() }
            }
            Button(L("play.new_round")) { model.playAgain() }
        }
    }
}

private struct PlayContent: View {
    @Environment(AppModel.self) private var model
    let game: PartyPlay.State

    var body: some View {
        switch game.kind {
        case .drawGuess:
            HStack(alignment: .top, spacing: 40) {
                DrawingView(drawing: game.entries.last { $0.drawing != nil }?.drawing)
                    .frame(width: 560, height: 560)
                VStack(alignment: .leading, spacing: 14) {
                    if let special = game.special { Text(L("play.drawer", special.name)).font(.title2.bold()) }
                    if let winner = game.winner {
                        BigText(L("play.guessed", winner.name, game.secret ?? ""))
                    } else if game.revealed {
                        BigText(L("play.word", game.secret ?? ""))
                    } else {
                        ForEach(Array(game.entries.filter { $0.text != nil }.suffix(6).reversed())) { guess in
                            Text("\(guess.name ?? "?"): \(guess.text ?? "")").font(.title3).foregroundStyle(Theme.secondaryText).lineLimit(1)
                        }
                    }
                }
            }
        case .drawingWall:
            let drawings = game.entries.filter { $0.drawing != nil }.suffix(20)
            if drawings.isEmpty {
                Waiting(text: L("play.waiting"))
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 24), count: 5), spacing: 24) {
                    ForEach(Array(drawings)) { entry in
                        VStack(alignment: .leading, spacing: 8) {
                            DrawingView(drawing: entry.drawing).aspectRatio(1, contentMode: .fit)
                            Text(entry.name ?? "?").font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(1)
                        }
                    }
                }
            }
        case .story, .capsule:
            if !game.revealed {
                Waiting(text: game.kind == .story ? LP("play.sentences", game.entries.count) : LP("play.capsule_entries", game.entries.count))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if game.kind == .story, let start = game.secret { StoryLine(text: start, name: nil) }
                        ForEach(game.entries) { entry in StoryLine(text: entry.text ?? "", name: entry.name) }
                    }
                }
            }
        case .whisper:
            if !game.revealed {
                Waiting(text: L("play.whisper_steps", game.entries.count, PartyPlay.maxWhisperSteps))
            } else {
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: 24) {
                        if let start = game.secret { WhisperCard(text: start, drawing: nil, name: nil) }
                        ForEach(game.entries) { entry in WhisperCard(text: entry.text, drawing: entry.drawing, name: entry.name) }
                    }
                    .padding(.vertical, 20)
                }
                .scrollClipDisabled()
            }
        case .compliments:
            Waiting(text: L("play.compliments_hint"))
        case .missions, .paparazzi:
            VStack(alignment: .leading, spacing: 20) {
                if game.kind == .paparazzi, let star = game.special { BigText(L("play.paparazzi_star", star.name)) }
                PhotoGrid(photos: model.playPhotos(round: game.round), empty: L("play.waiting_photos"))
            }
        case .sameMoment:
            TimelineView(.periodic(from: .now, by: 0.25)) { context in
                let momentAt = game.momentAt ?? context.date
                let left = Int(momentAt.timeIntervalSince(context.date).rounded(.up))
                if left > 0 {
                    Waiting(text: L("play.moment_in", left), large: true)
                } else if context.date < momentAt.addingTimeInterval(PartyPlay.momentWindow) {
                    VStack(alignment: .leading, spacing: 20) {
                        BigText(L("play.moment_now"))
                        PhotoGrid(photos: model.playPhotos(round: game.round), empty: nil)
                    }
                } else {
                    PhotoGrid(photos: model.playPhotos(round: game.round), empty: L("play.waiting_photos"))
                }
            }
        case .recreate:
            HStack(alignment: .top, spacing: 32) {
                if let original = model.recreateOriginal {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(L("play.original").uppercased()).font(.headline).foregroundStyle(Theme.secondaryText)
                        PhotoImage(photo: original, fitMode: .contain, maxPixelSize: 1600)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    }
                }
                PhotoGrid(photos: model.playPhotos(round: game.round), empty: L("play.waiting_photos"), columns: 2)
            }
        case .mole:
            VStack(alignment: .leading, spacing: 18) {
                if game.revealed, let mole = game.special {
                    let caught = game.votes.first?.name == mole.name
                    BigText(caught ? L("play.mole_caught", mole.name) : L("play.mole_free", mole.name))
                    if let mission = game.secret { Text(L("play.mole_mission", mission)).font(.title3).foregroundStyle(Theme.secondaryText) }
                } else if game.voting {
                    Text(L("play.mole_voting")).font(.title2.bold())
                    let total = max(game.votes.map(\.votes).reduce(0, +), 1)
                    ForEach(game.votes, id: \.name) { vote in
                        VoteBar(label: vote.name, votes: vote.votes, fraction: CGFloat(vote.votes) / CGFloat(total))
                    }
                } else {
                    Waiting(text: L("play.mole_playing"))
                }
            }
        }
    }
}

private struct BigText: View {
    let text: String

    var body: some View {
        Text(text).font(.system(size: 52, weight: .bold)).foregroundStyle(Theme.highlight).lineLimit(3).minimumScaleFactor(0.6)
    }
}

private struct Waiting: View {
    let text: String
    var large = false

    var body: some View {
        Text(text)
            .font(large ? .system(size: 120, weight: .bold) : .title2)
            .multilineTextAlignment(.center)
            .padding(40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 32, style: .continuous))
    }
}

private struct StoryLine: View {
    let text: String
    let name: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text).font(.title3)
            if let name { Text(name).font(.callout).foregroundStyle(Theme.secondaryText) }
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

private struct WhisperCard: View {
    let text: String?
    let drawing: String?
    let name: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let drawing {
                DrawingView(drawing: drawing)
            } else {
                Text(text ?? "")
                    .font(.title3.bold())
                    .multilineTextAlignment(.center)
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(white: 0.18), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
            Text(name ?? L("play.whisper_start")).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(1)
        }
        .frame(width: 380, height: 430)
    }
}

private struct PhotoGrid: View {
    let photos: [Photo]
    let empty: String?
    var columns = 4

    var body: some View {
        if photos.isEmpty {
            if let empty { Waiting(text: empty) }
        } else {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 20), count: columns), spacing: 20) {
                ForEach(photos.suffix(24)) { photo in
                    VStack(alignment: .leading, spacing: 6) {
                        PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 600, placeholder: false)
                            .aspectRatio(16 / 10, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        if let sender = photo.sender { Text(sender).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(1) }
                    }
                }
            }
        }
    }
}

private struct VoteBar: View {
    let label: String
    let votes: Int
    let fraction: CGFloat

    var body: some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(white: 0.14))
            GeometryReader { geometry in
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(white: 0.3))
                    .frame(width: geometry.size.width * min(max(fraction, 0), 1))
            }
            HStack {
                Text(label).font(.headline).lineLimit(1)
                Spacer(minLength: 16)
                Text(LP("poll.votes", votes)).font(.headline)
            }
            .padding(.horizontal, 28)
        }
        .frame(height: 76)
        .animation(.easeOut(duration: 0.6), value: fraction)
    }
}

/// QR della festa e le monete dei primi tre, a lato del gioco.
private struct PlaySidePanel: View {
    let url: String?
    let game: PartyPlay.State
    let coins: [PartyPlay.Coins]

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let url {
                HStack(alignment: .top, spacing: 20) {
                    QRCodeView(text: url, accessibilityLabel: L("phone.scan"), size: 170)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Text(L("phone.scan")).font(.caption).foregroundStyle(Theme.secondaryText).fixedSize(horizontal: false, vertical: true)
                }
            }
            if !game.entries.isEmpty { Text(LP("play.contributions", game.entries.count)).font(.headline) }
            if !coins.isEmpty {
                Text(L("play.title_coins")).font(.subheadline).foregroundStyle(Theme.secondaryText)
                ForEach(Array(coins.prefix(3).enumerated()), id: \.offset) { index, entry in
                    HStack {
                        Text("\(index + 1). \(entry.name)").lineLimit(1)
                        Spacer(minLength: 16)
                        Text(LP("coins.count", entry.coins)).foregroundStyle(Theme.secondaryText)
                    }
                    .font(.callout)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Disegno di un telefono: tratti colorati su fondo bianco, nelle coordinate 0–1 della tela.
struct DrawingView: View {
    let drawing: String?

    var body: some View {
        let strokes = drawing.map(PartyPlay.strokes) ?? []
        Canvas { context, size in
            let side = min(size.width, size.height)
            let width = side * 0.012
            for stroke in strokes {
                let color = drawingColors.indices.contains(stroke.color) ? drawingColors[stroke.color] : drawingColors[0]
                guard let first = stroke.points.first else { continue }
                if stroke.points.count == 1 {
                    let center = CGPoint(x: first.x * size.width, y: first.y * size.height)
                    context.fill(Path(ellipseIn: CGRect(x: center.x - width, y: center.y - width, width: width * 2, height: width * 2)), with: .color(color))
                    continue
                }
                var path = Path()
                path.move(to: CGPoint(x: first.x * size.width, y: first.y * size.height))
                for point in stroke.points.dropFirst() { path.addLine(to: CGPoint(x: point.x * size.width, y: point.y * size.height)) }
                context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round))
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// Foto nata in un gioco: la sua etichetta in alto al centro dello slideshow.
struct GameTagBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.headline)
            .foregroundStyle(Theme.onHighlight)
            .lineLimit(1)
            .padding(.horizontal, 28)
            .padding(.vertical, 12)
            .background(Theme.highlight, in: Capsule())
    }
}

/// Caccia al QR: il codice grande finché qualcuno non lo trova.
struct TreasureOverlay: View {
    let url: String
    var finders: [String] = []

    var body: some View {
        VStack(spacing: 14) {
            Text(L("treasure.title")).font(.headline).foregroundStyle(Theme.highlight).multilineTextAlignment(.center)
            QRCodeView(text: url, accessibilityLabel: L("treasure.title"), size: 300)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            if !finders.isEmpty { Text(L("treasure.found", finders.joined(separator: " · "))).font(.callout) }
        }
        .padding(28)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
    }
}

// MARK: - Torneo delle foto

/// Torneo a eliminazione: due foto alla volta, i telefoni votano, la più votata passa il turno.
struct TournamentView: View {
    @Environment(AppModel.self) private var model
    @State private var round: [Photo] = []
    @State private var winners: [Photo] = []
    @State private var match = 0
    @State private var started = false

    private var game: PhoneGame { model.guests.phoneGame }
    private var partyOn: Bool { model.guests.active && model.guests.url != nil }
    private var choices: [String] { [L("tournament.a"), L("tournament.b")] }

    var body: some View {
        let champion = round.count == 1 ? round.first : nil
        let pair: (Photo, Photo)? = champion == nil && round.count >= 2 && match * 2 + 1 < round.count ? (round[match * 2], round[match * 2 + 1]) : nil
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("play.title_tournament"), subtitle: subtitle(champion: champion))
                if let champion {
                    PhotoImage(photo: champion, fitMode: .contain)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 32, style: .continuous))
                } else if let pair {
                    TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                        let tally = game.tally()
                        let votes = [tally.first ?? 0, tally.count > 1 ? tally[1] : 0]
                        HStack(spacing: 40) {
                            ForEach(0..<2, id: \.self) { index in
                                VStack(spacing: 16) {
                                    PhotoImage(photo: index == 0 ? pair.0 : pair.1, fitMode: .contain, maxPixelSize: 1600)
                                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                                        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                                    ChoiceLabel(text: "\(choices[index]) · \(LP("poll.votes", votes[index]))", highlighted: votes[index] > votes[1 - index])
                                }
                            }
                        }
                    }
                    Button(L("tournament.next")) { advance(pair) }
                }
                Spacer(minLength: 0)
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            let photos = model.stillPhotos.shuffled()
            round = Array(photos.prefix(photos.count >= 8 ? 8 : 4))
            if round.count >= 4, partyOn { ask() } else { round = [] }
        }
        .onDisappear { game.stop() }
    }

    private func subtitle(champion: Photo?) -> String {
        if !partyOn { return L("games.poll_unavailable") }
        if model.stillPhotos.count < 4 { return L("tournament.need") }
        if champion != nil { return L("tournament.champion") }
        switch round.count {
        case 2: return L("tournament.final")
        case 4: return L("tournament.semi")
        default: return L("tournament.quarter")
        }
    }

    private func ask() {
        game.askPoll(question: L("tournament.question"), choices: choices)
    }

    private func advance(_ pair: (Photo, Photo)) {
        let tally = game.tally()
        let first = tally.first ?? 0
        let second = tally.count > 1 ? tally[1] : 0
        let winner = first > second ? pair.0 : second > first ? pair.1 : (Bool.random() ? pair.0 : pair.1)
        let next = winners + [winner]
        if match * 2 + 2 >= round.count {
            round = next
            winners = []
            match = 0
        } else {
            winners = next
            match += 1
        }
        if round.count > 1 { ask() } else { game.stop() }
    }
}

// MARK: - Scommesse

/// Scommesse con i telefoni: si scommette, si chiude, e chi aveva ragione vince monete.
struct BetsView: View {
    @Environment(AppModel.self) private var model
    @State private var question: String?
    @State private var choices: [String] = []
    @State private var closed = false
    @State private var winners: Int?

    private var game: PhoneGame { model.guests.phoneGame }
    private var phoneURL: String? { model.guests.active ? model.guests.url : nil }

    private struct BetPreset: Identifiable {
        let id: Int
        let question: String
        let choices: [String]?
    }

    private var presets: [BetPreset] {
        [
            BetPreset(id: 0, question: L("bets.q_cake"), choices: nil),
            BetPreset(id: 1, question: L("bets.q_last"), choices: nil),
            BetPreset(id: 2, question: L("bets.q_song"), choices: L("bets.c_song").components(separatedBy: "|")),
            BetPreset(id: 3, question: L("bets.q_photos"), choices: L("bets.c_photos").components(separatedBy: "|")),
            BetPreset(id: 4, question: L("bets.q_speech"), choices: L("bets.c_speech").components(separatedBy: "|")),
        ]
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: question ?? L("play.title_bets"), subtitle: subtitle)
                if let phoneURL {
                    if question == nil { presetList } else { running(phoneURL) }
                }
                Spacer(minLength: 0)
            }
        }
        .onDisappear { if question != nil { game.stop() } }
    }

    private var subtitle: String {
        if phoneURL == nil { return L("games.poll_unavailable") }
        if question == nil { return L("bets.choose") }
        if let winners { return LP("bets.winners", winners) }
        return closed ? L("bets.outcome") : L("play.body_bets")
    }

    private var presetList: some View {
        let names = model.lotteryCandidates()
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ForEach(presets) { preset in
                    Button {
                        let options = preset.choices ?? Array(names.shuffled().prefix(4))
                        guard options.count >= 2 else { return }
                        question = preset.question
                        choices = options
                        closed = false
                        winners = nil
                        game.askPoll(question: preset.question, choices: options)
                    } label: {
                        Label(preset.question, systemImage: "banknote.fill")
                    }
                    .disabled(preset.choices == nil && names.count < 2)
                }
            }
            .padding(.vertical, 20)
        }
        .scrollClipDisabled()
    }

    private func running(_ url: String) -> some View {
        HStack(alignment: .top, spacing: 56) {
            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                let tally = game.tally()
                let total = max(tally.reduce(0, +), 1)
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(choices.enumerated()), id: \.offset) { index, choice in
                        let votes = index < tally.count ? tally[index] : 0
                        if closed, winners == nil {
                            // Si sceglie com'è andata: chi l'aveva detto vince le monete.
                            Button("\(choice) · \(LP("poll.votes", votes))") { winners = model.settleBet(index) }
                        } else {
                            VoteBar(label: choice, votes: votes, fraction: closed ? CGFloat(votes) / CGFloat(total) : 0)
                        }
                    }
                    HStack(spacing: 24) {
                        if !closed {
                            Button(L("bets.close")) {
                                closed = true
                                game.reveal()
                            }
                        }
                        if winners != nil || !closed {
                            Button(L("bets.new")) {
                                game.stop()
                                question = nil
                            }
                        }
                    }
                    .padding(.top, 12)
                }
            }
            .frame(maxWidth: .infinity)
            PhonePlayPanel(game: game, url: url, revealed: false)
                .frame(width: 640)
        }
    }
}

// MARK: - Roulette dei brindisi

/// La roulette sceglie chi fa il prossimo brindisi; il nome arriva anche sui telefoni.
struct ToastView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: String?
    @State private var spinning = false

    var body: some View {
        let names = model.lotteryCandidates()
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                ScreenTitle(title: L("play.title_toast"), subtitle: names.isEmpty ? L("lottery.none") : L("play.body_toast"))
                VStack(spacing: 24) {
                    Image(systemName: "wineglass.fill")
                        .font(.system(size: 80, weight: .semibold))
                        .foregroundStyle(Theme.highlight)
                    Text(shown.map { spinning ? $0 : L("toast.winner", $0) } ?? L("toast.ready"))
                        .font(.system(size: 88, weight: .bold))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .minimumScaleFactor(0.5)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 32, style: .continuous))
                Button(L("toast.spin")) { spin(names) }
                    .disabled(spinning || names.isEmpty)
            }
        }
    }

    private func spin(_ names: [String]) {
        guard !spinning, let winner = names.randomElement() else { return }
        spinning = true
        Task {
            if !reduceMotion {
                let start = Date()
                while Date().timeIntervalSince(start) < 3 {
                    shown = names.randomElement()
                    try? await Task.sleep(for: .milliseconds(85))
                }
            }
            shown = winner
            spinning = false
            model.announceToast(winner)
        }
    }
}

// MARK: - Monete della festa

struct CoinsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let coins = model.guests.coins
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("play.title_coins"), subtitle: L("coins.how"))
                if coins.isEmpty {
                    Waiting(text: L("coins.empty"))
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            ForEach(Array(coins.enumerated()), id: \.offset) { index, entry in
                                HStack {
                                    Text("\(index + 1).").frame(width: 80, alignment: .leading)
                                    Text(entry.name).lineLimit(1)
                                    Spacer(minLength: 24)
                                    Text(LP("coins.count", entry.coins))
                                }
                                .font(.title2.bold())
                                .foregroundStyle(index == 0 ? Theme.onHighlight : Color.primary)
                                .padding(.horizontal, 32)
                                .padding(.vertical, 20)
                                .background(index == 0 ? AnyShapeStyle(Theme.highlight) : AnyShapeStyle(Color(white: 0.12)), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                                .focusable()
                            }
                        }
                    }
                    .scrollClipDisabled()
                }
                Spacer(minLength: 0)
            }
        }
    }
}
