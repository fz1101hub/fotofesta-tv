import FotoFestaCore
import SwiftUI

// MARK: - Sopra lo slideshow

private struct FloatingReaction: Identifiable {
    let id: Int
    let emoji: String
    let lane: CGFloat
}

/// Reazioni dei telefoni: ogni emoji sale dal basso a destra e svanisce in tre secondi.
struct ReactionsOverlay: View {
    let hub: PartyHub
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var floating: [FloatingReaction] = []
    @State private var lastSerial: Int?

    private static let maxFloating = 24

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Color.clear
            ForEach(floating) { item in
                FloatingEmoji(emoji: item.emoji, reduceMotion: reduceMotion)
                    // Corsie nella parte destra dello schermo, lontano dalla didascalia e dal QR.
                    .padding(.trailing, 110 + item.lane * 420)
                    .padding(.bottom, 230)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task {
            // Le reazioni arrivate prima di aprire lo slideshow non si ripetono.
            var last = lastSerial ?? hub.lastReactionSerial()
            while !Task.isCancelled {
                for event in hub.reactions(after: last) {
                    last = event.serial
                    add(event)
                }
                lastSerial = last
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }

    private func add(_ event: PartyHub.ReactionEvent) {
        let item = FloatingReaction(id: event.serial, emoji: event.reaction.emoji, lane: .random(in: 0...1))
        floating.append(item)
        if floating.count > Self.maxFloating { floating.removeFirst(floating.count - Self.maxFloating) }
        Task {
            try? await Task.sleep(for: .seconds(3.4))
            floating.removeAll { $0.id == item.id }
        }
    }
}

private struct FloatingEmoji: View {
    let emoji: String
    let reduceMotion: Bool
    @State private var risen = false

    var body: some View {
        Text(emoji)
            .font(.system(size: 84))
            .scaleEffect(risen && !reduceMotion ? 1.2 : 0.8)
            .offset(y: risen && !reduceMotion ? -600 : 0)
            .opacity(risen ? 0 : 1)
            .onAppear {
                withAnimation(.easeOut(duration: reduceMotion ? 1.6 : 3.2)) { risen = true }
            }
    }
}

/// Dediche in sovrimpressione: le nuove una alla volta, per nove secondi ciascuna; quando non ne
/// arrivano, ogni tanto ne torna una di quelle già lette, come un libro degli ospiti che si sfoglia.
struct MessageBanner: View {
    let messages: [PartyHub.Message]
    @State private var seen: Set<String> = []
    @State private var queue: [PartyHub.Message] = []
    @State private var current: PartyHub.Message?
    /// Tutte le dediche, aggiornate a ogni cambio: il ciclo qui sotto non riparte quando arrivano.
    @State private var all: [PartyHub.Message] = []

    private static let showTime: Duration = .seconds(9)
    private static let replayAfter: TimeInterval = 45

    var body: some View {
        VStack {
            if let current {
                VStack(alignment: .leading, spacing: 8) {
                    Text(current.name.map { L("guestbook.from", $0) } ?? L("guestbook.kicker"))
                        .font(.headline)
                        .foregroundStyle(Theme.secondaryText)
                    Text(current.text)
                        .font(.title2.bold())
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 40)
                .padding(.vertical, 24)
                .frame(maxWidth: 1300, alignment: .leading)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .padding(.top, 150)
                .transition(.opacity)
                .id(current.id)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.4), value: current?.id)
        .onChange(of: messages, initial: true) { _, latest in
            all = latest
            // All'apertura dello slideshow si parte dall'ultima dedica arrivata, non da tutte.
            if seen.isEmpty { seen.formUnion(latest.dropLast().map(\.id)) }
            for message in latest where !seen.contains(message.id) {
                seen.insert(message.id)
                queue.append(message)
            }
        }
        .task {
            var idle: TimeInterval = 0
            while !Task.isCancelled {
                if let next = queue.first {
                    queue.removeFirst()
                    idle = 0
                    current = next
                } else if idle >= Self.replayAfter, let old = all.randomElement() {
                    idle = 0
                    current = old
                } else {
                    try? await Task.sleep(for: .milliseconds(500))
                    idle += 0.5
                    continue
                }
                try? await Task.sleep(for: Self.showTime)
                current = nil
                try? await Task.sleep(for: .milliseconds(600))
            }
        }
    }
}

// MARK: - Sondaggio lampo

/// Domanda pronta; senza `choices` le risposte sono i nomi di chi partecipa.
private struct PollPreset: Identifiable {
    let id: Int
    let question: String
    let choices: [String]?
}

/// Sondaggio con i telefoni: si sceglie una domanda, gli invitati votano dalla pagina della festa e
/// alla fine la TV mostra i voti. Alcune domande hanno come risposte i nomi di chi partecipa.
struct PollView: View {
    @Environment(AppModel.self) private var model
    @State private var question: String?
    @State private var choices: [String] = []
    @State private var revealed = false

    private static let maxNameChoices = 4

    private var phoneURL: String? { model.guests.active ? model.guests.url : nil }
    private var game: PhoneGame { model.guests.phoneGame }

    private var presets: [PollPreset] {
        [
            PollPreset(id: 0, question: L("poll.q_king"), choices: nil),
            PollPreset(id: 1, question: L("poll.q_last_dance"), choices: nil),
            PollPreset(id: 2, question: L("poll.q_style"), choices: nil),
            PollPreset(id: 3, question: L("poll.q_moment"), choices: L("poll.c_moment").components(separatedBy: "|")),
            PollPreset(id: 4, question: L("poll.q_vote"), choices: L("poll.c_vote").components(separatedBy: "|")),
            PollPreset(id: 5, question: L("poll.q_sleep"), choices: L("poll.c_sleep").components(separatedBy: "|")),
        ]
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                ScreenTitle(
                    title: question ?? L("games.poll_title"),
                    subtitle: question == nil ? (phoneURL != nil ? L("poll.choose") : L("poll.need_party")) : nil
                )
                if let phoneURL {
                    if question == nil {
                        presetList
                    } else {
                        running(phoneURL)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onDisappear { if question != nil { game.stop() } }
    }

    private var presetList: some View {
        let names = model.lotteryCandidates()
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ForEach(presets) { preset in
                    Button {
                        let options = preset.choices ?? Array(names.shuffled().prefix(Self.maxNameChoices))
                        guard options.count >= 2 else { return }
                        question = preset.question
                        choices = options
                        revealed = false
                        game.askPoll(question: preset.question, choices: options)
                    } label: {
                        Label(preset.question, systemImage: "chart.bar.fill")
                    }
                    // Le domande con i nomi servono almeno due partecipanti con un nome.
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
                let best = tally.max() ?? 0
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(choices.enumerated()), id: \.offset) { index, choice in
                        let votes = index < tally.count ? tally[index] : 0
                        PollBar(label: choice, votes: votes, fraction: revealed ? CGFloat(votes) / CGFloat(total) : 0,
                                highlighted: revealed && votes == best && votes > 0)
                    }
                    HStack(spacing: 24) {
                        Button(L("poll.reveal")) {
                            revealed = true
                            game.reveal()
                        }
                        .disabled(revealed)
                        Button(L("poll.new")) {
                            game.stop()
                            question = nil
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

private struct PollBar: View {
    let label: String
    let votes: Int
    let fraction: CGFloat
    let highlighted: Bool

    var body: some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(white: 0.14))
            GeometryReader { geometry in
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(highlighted ? Color.white : Color(white: 0.3))
                    .frame(width: geometry.size.width * min(max(fraction, 0), 1))
            }
            HStack {
                Text(label).font(.headline).lineLimit(1)
                Spacer(minLength: 16)
                if fraction > 0 || highlighted {
                    Text(LP("poll.votes", votes)).font(.headline)
                }
            }
            .foregroundStyle(highlighted ? Color.black : Color.white)
            .padding(.horizontal, 28)
        }
        .frame(height: 76)
        .animation(.easeOut(duration: 0.6), value: fraction)
    }
}

// MARK: - Estrazione a premi

/// Estrazione tra chi ha partecipato con un nome: i nomi scorrono veloci per qualche secondo e si
/// fermano sul vincitore. Chi ha già vinto non viene estratto di nuovo finché non si ricomincia.
struct LotteryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var winners: [String] = []
    @State private var shown: String?
    @State private var spinning = false
    @State private var exhausted = false

    var body: some View {
        let count = model.lotteryCandidates().count
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                ScreenTitle(title: L("games.lottery_title"), subtitle: count == 0 ? L("lottery.none") : LP("lottery.candidates", count))
                VStack(spacing: 24) {
                    Image(systemName: spinning ? "dice.fill" : "party.popper.fill")
                        .font(.system(size: 80, weight: .semibold))
                        .foregroundStyle(Theme.highlight)
                    Text(shown ?? (exhausted ? L("lottery.all_drawn") : L("lottery.ready")))
                        .font(.system(size: 96, weight: .bold))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .minimumScaleFactor(0.5)
                    if !winners.isEmpty, !spinning {
                        Text(L("lottery.winners", winners.joined(separator: " · ")))
                            .font(.title3)
                            .foregroundStyle(Theme.secondaryText)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 32, style: .continuous))
                HStack(spacing: 24) {
                    Button(winners.isEmpty ? L("lottery.draw") : L("lottery.again")) { draw() }
                        .disabled(spinning || count == 0)
                    if !winners.isEmpty {
                        Button(L("lottery.reset")) {
                            winners = []
                            shown = nil
                            exhausted = false
                        }
                        .disabled(spinning)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func draw() {
        guard !spinning else { return }
        let names = model.lotteryCandidates()
        guard let winner = PrizeDraw.draw(names, winners: winners) else {
            exhausted = !names.isEmpty
            shown = nil
            return
        }
        exhausted = false
        spinning = true
        Task {
            if !reduceMotion {
                let end = ContinuousClock.now + .seconds(2.4)
                while ContinuousClock.now < end {
                    shown = names.randomElement()
                    try? await Task.sleep(for: .milliseconds(85))
                }
            }
            shown = winner
            winners.append(winner)
            spinning = false
        }
    }
}

// MARK: - Libro degli ospiti

/// Tutte le dediche della festa, dalla più recente; in cima quelle da approvare.
struct GuestbookView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let guests = model.guests
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                HStack(alignment: .top) {
                    ScreenTitle(
                        title: L("guestbook.title"),
                        subtitle: guests.messages.isEmpty && guests.pendingMessages.isEmpty
                            ? L("guestbook.empty") : LP("guestbook.count", guests.messages.count)
                    )
                    Spacer()
                    if guests.pendingMessages.count > 1 {
                        Button(L("guestbook.approve_all")) { guests.approveAllMessages() }
                    }
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        ForEach(guests.pendingMessages) { message in
                            MessageRow(message: message, pending: true) {
                                Button(L("guestbook.show")) { guests.approveMessage(message) }
                                Button(L("guestbook.discard"), role: .destructive) { guests.discardMessage(message) }
                            }
                        }
                        ForEach(guests.messages.reversed()) { message in
                            MessageRow(message: message, pending: false) {
                                Button(L("guestbook.discard"), role: .destructive) { guests.discardMessage(message) }
                            }
                        }
                    }
                    .padding(.vertical, 20)
                }
                .scrollClipDisabled()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct MessageRow<Actions: View>: View {
    let message: PartyHub.Message
    let pending: Bool
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        HStack(alignment: .center, spacing: 32) {
            VStack(alignment: .leading, spacing: 8) {
                Text(pending ? "\(message.name ?? L("guestbook.anonymous")) · \(L("guestbook.pending"))" : (message.name ?? L("guestbook.anonymous")))
                    .font(.headline)
                    .foregroundStyle(pending ? Theme.highlight : Theme.secondaryText)
                Text(message.text).font(.title3).fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 20) { actions() }
        }
        .padding(28)
        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

// MARK: - Regia: annunci e regista di turno

/// Annuncio della regia, in grande al centro per quindici secondi dopo l'arrivo.
struct AnnouncementOverlay: View {
    let announcement: PartyHub.Announcement?
    @State private var visible: PartyHub.Announcement?

    private static let screenTime: TimeInterval = 15

    var body: some View {
        ZStack {
            if let visible {
                VStack(spacing: 14) {
                    Text(L("announce.kicker").uppercased())
                        .font(.headline)
                    Text(visible.text)
                        .font(.system(size: 64, weight: .bold))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(Color.black)
                .padding(.horizontal, 64)
                .padding(.vertical, 44)
                .frame(maxWidth: 1400)
                .background(Theme.highlight, in: RoundedRectangle(cornerRadius: 36, style: .continuous))
                .transition(.opacity.combined(with: .scale(scale: 0.95)))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.4), value: visible?.serial)
        .task(id: announcement?.serial) {
            guard let current = announcement else { return }
            // Un annuncio già vecchio (ad esempio aprendo lo slideshow dopo) non ricompare.
            let left = Self.screenTime - Date().timeIntervalSince(current.date)
            guard left > 0 else { return }
            visible = current
            try? await Task.sleep(for: .seconds(left))
            if visible?.serial == current.serial { visible = nil }
        }
    }
}

/// Chi dirige lo slideshow dal telefono, con il tempo che resta.
struct DirectorBadge: View {
    let director: PartyHub.Director

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let remaining = max(Int(director.until.timeIntervalSince(context.date)), 0)
            if remaining > 0 {
                Label(L("director.now", director.name, String(format: "%d:%02d", remaining / 60, remaining % 60)), systemImage: "video.fill")
                    .font(.headline)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 12)
                    .background(.regularMaterial, in: Capsule())
            }
        }
    }
}

/// Annuncio dalla TV: frasi pronte da scegliere con il telecomando, oppure un testo libero.
struct AnnounceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    private var presets: [String] {
        [L("announce.p_buffet"), L("announce.p_cake"), L("announce.p_group"), L("announce.p_dance"), L("announce.p_last")]
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                ScreenTitle(title: L("guests.announce"), subtitle: L("announce.body"))
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 460), spacing: 32)], alignment: .leading, spacing: 32) {
                    ForEach(presets, id: \.self) { preset in
                        Button { send(preset) } label: {
                            Label(preset, systemImage: "megaphone.fill").frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                HStack(spacing: 32) {
                    TextField(L("announce.custom"), text: $text)
                        .onSubmit { send(text) }
                    Button(L("announce.send")) { send(text) }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func send(_ value: String) {
        guard model.guests.announce(value) else {
            model.showMessage(L("announce.empty"))
            return
        }
        dismiss()
    }
}
