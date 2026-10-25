import FotoFestaCore
import SwiftUI

// MARK: - Spettacoli e giochi dell'ondata 11: chi non c'è, opera d'arte, la festa suona, QR vivente,
// sogno, museo, capitoli, accadde oggi, trova le differenze e i quiz con i telefoni

/// Colori medi di qualche foto a caso, letti uno alla volta (opera d'arte, QR vivente).
private func photoColors(_ photos: [Photo], limit: Int) async -> [UInt32] {
    var colors: [UInt32] = []
    for photo in photos.shuffled().prefix(limit) {
        if Task.isCancelled { break }
        if let result = await pixels(of: photo, width: 8, height: 8) { colors.append(PartyShows.average(rgba: result.rgba)) }
    }
    return colors
}

/// Spettacolo a tutto schermo con titolo e sottotitolo in alto.
private struct ShowPage<Content: View>: View {
    let title: String
    let subtitle: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 28) {
                ScreenTitle(title: title, subtitle: subtitle)
                content().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}

// MARK: Chi non c'è

/// Invitati attesi (dalla regia) contro chi si è fatto vivo: arrivati, foto, reazioni, giochi.
struct AbsentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let roll = model.absentRoll()
        ShowPage(title: L("show.absent"), subtitle: L("show.absent_body")) {
            if roll.present.isEmpty, roll.missing.isEmpty {
                Text(L("absent.empty")).font(.title3).foregroundStyle(Theme.secondaryText)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                HStack(alignment: .top, spacing: 60) {
                    NameColumn(title: LP("absent.missing", roll.missing.count), names: roll.missing, color: Color(hex: 0xFFC145))
                    NameColumn(title: LP("absent.present", roll.present.count), names: roll.present, color: Color(hex: 0x4CD787))
                }
                .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        // Per far scorrere lo schermo con il telecomando.
        .focusable()
    }
}

private struct NameColumn: View {
    let title: String
    let names: [String]
    let color: Color
    private static let shown = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title2.bold()).foregroundStyle(color)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], alignment: .leading, spacing: 14) {
                ForEach(Array(names.prefix(Self.shown).enumerated()), id: \.offset) { _, name in
                    Text(name).font(.headline).lineLimit(1)
                        .padding(.horizontal, 18).padding(.vertical, 10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
            if names.count > Self.shown {
                Text("+\(names.count - Self.shown)").font(.headline).foregroundStyle(Theme.secondaryText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

// MARK: L'opera d'arte della festa

/// Un quadro astratto alla Mondrian, dipinto con i colori medi delle foto della festa.
struct ArtworkView: View {
    @Environment(AppModel.self) private var model
    @State private var colors: [UInt32] = []
    @State private var seed: UInt64 = 1

    var body: some View {
        let blocks = PartyGames.artwork(colors, seed: seed)
        ShowPage(title: L("show.artwork"), subtitle: L("artwork.caption", model.guests.setup.brand.name.isEmpty ? model.partyTitle : model.guests.setup.brand.name, colors.count)) {
            HStack(spacing: 56) {
                Canvas { context, size in
                    for block in blocks {
                        let rect = CGRect(x: block.left * size.width, y: block.top * size.height,
                                          width: (block.right - block.left) * size.width, height: (block.bottom - block.top) * size.height)
                        context.fill(Path(rect), with: .color(Color(hex: block.color)))
                        context.stroke(Path(rect), with: .color(Color(hex: 0x111111)), lineWidth: 7)
                    }
                }
                .padding(14)
                .background(Color(hex: 0xF4EEDF))
                .padding(22)
                .background(Color(hex: 0x2A1E12))
                .aspectRatio(1.25, contentMode: .fit)
                VStack(alignment: .leading, spacing: 16) {
                    Text(L("artwork.title")).font(.title2.italic())
                    Text(L("artwork.technique", colors.count)).font(.callout).foregroundStyle(Theme.secondaryText)
                    Button(L("artwork.again")) { seed &+= 1 }.padding(.top, 12)
                }
                .frame(maxWidth: 520, alignment: .leading)
            }
        }
        .task { colors = await photoColors(model.stillPhotos, limit: 60) }
    }
}

// MARK: La festa suona

/// Ogni foto diventa una nota: si vedono scorrere mentre suona la melodia della festa.
struct SoundShowView: View {
    @Environment(AppModel.self) private var model
    @State private var tracks: [(photo: Photo, color: UInt32)] = []
    @State private var index = -1
    @State private var run = 0

    var body: some View {
        let notes = PartyGames.notes(tracks.map { $0.color })
        ShowPage(title: L("show.sound"), subtitle: L("show.sound_body")) {
            VStack(spacing: 24) {
                ZStack {
                    Color.black
                    if tracks.indices.contains(index) {
                        PhotoImage(photo: tracks[index].photo, fitMode: .contain, maxPixelSize: 1920, placeholder: false)
                    } else if !tracks.isEmpty {
                        Button(L("sound.again")) { run += 1 }
                    } else {
                        Text(L("sound.loading")).font(.title3).foregroundStyle(Theme.secondaryText)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                // La melodia come barre colorate: alte le note acute.
                Canvas { context, size in
                    guard let top = notes.map(\.frequency).max(), !tracks.isEmpty else { return }
                    let bar = size.width / CGFloat(tracks.count)
                    for (i, track) in tracks.enumerated() {
                        let height = CGFloat(notes[i].frequency / top) * size.height
                        context.fill(Path(CGRect(x: CGFloat(i) * bar, y: size.height - height, width: max(bar - 2, 1), height: height)),
                                     with: .color(Color(hex: track.color).opacity(i == index ? 1 : 0.55)))
                    }
                }
                .frame(height: 80)
            }
        }
        .task {
            var loaded: [(photo: Photo, color: UInt32)] = []
            for photo in model.stillPhotos.shuffled().prefix(40) {
                if Task.isCancelled { return }
                if let result = await pixels(of: photo, width: 8, height: 8) { loaded.append((photo: photo, color: PartyShows.average(rgba: result.rgba))) }
            }
            tracks = loaded
            run = 1
        }
        .task(id: run) {
            guard run > 0, !tracks.isEmpty else { return }
            let notes = PartyGames.notes(tracks.map { $0.color })
            PartySynth.shared.play(notes)
            for (i, note) in notes.enumerated() {
                index = i
                try? await Task.sleep(for: .milliseconds(note.milliseconds))
                if Task.isCancelled { return }
            }
            index = -1
        }
        .onDisappear { PartySynth.shared.stop() }
    }
}

// MARK: QR vivente

/// Il QR della festa fatto con i colori delle foto: ogni modulo scuro prende il colore di una foto,
/// scurito quanto basta perché i telefoni lo leggano ancora, e pulsa piano.
struct LivingQRView: View {
    @Environment(AppModel.self) private var model
    @State private var colors: [UInt32] = []
    @State private var matrix: [[Bool]]?

    var body: some View {
        ShowPage(title: L("show.living_qr"), subtitle: L("show.living_qr_body")) {
            if let matrix {
                TimelineView(.animation(minimumInterval: 1 / 15)) { context in
                    let pulse = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 4) / 4
                    Canvas { canvas, size in
                        let cell = size.width / CGFloat(matrix.count)
                        for (y, row) in matrix.enumerated() {
                            for (x, dark) in row.enumerated() where dark {
                                let base = colors.isEmpty ? 0 : colors[(x * 31 + y * 17) % colors.count]
                                let wave = sin(Double(x + y) * 0.35 + pulse * 2 * .pi) * 0.5 + 0.5
                                canvas.fill(Path(CGRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell + 0.5, height: cell + 0.5)),
                                            with: .color(Self.darkened(base, maxLuminance: 0.25 + 0.1 * wave)))
                            }
                        }
                    }
                    .padding(24)
                    .background(Color.white)
                    .aspectRatio(1, contentMode: .fit)
                }
                .accessibilityLabel(L("show.living_qr"))
            } else {
                Text(L("living_qr.start")).font(.title3).foregroundStyle(Theme.secondaryText)
            }
        }
        .task(id: model.guests.url) {
            matrix = model.guests.url.flatMap { QRSVG.modules($0, correction: "H") }
            colors = await photoColors(model.stillPhotos, limit: 60)
        }
    }

    /// Colore scurito fino a `maxLuminance`: i moduli del QR devono restare ben più scuri del bianco.
    private static func darkened(_ color: UInt32, maxLuminance: Double) -> Color {
        let r = Double((color >> 16) & 0xFF) / 255
        let g = Double((color >> 8) & 0xFF) / 255
        let b = Double(color & 0xFF) / 255
        let luminance = 0.299 * r + 0.587 * g + 0.114 * b
        let scale = luminance > maxLuminance ? maxLuminance / luminance : 1
        return Color(red: r * scale, green: g * scale, blue: b * scale)
    }
}

// MARK: Il sogno della festa

/// Foto che si dissolvono una nell'altra, grandi, lente e sfumate, come in un sogno.
struct DreamView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var stills: [Photo] = []
    @State private var index = 0
    @State private var drift = false
    private static let step: Duration = .seconds(7)

    var body: some View {
        ZStack {
            Color(hex: 0x07060F).ignoresSafeArea()
            if stills.isEmpty {
                ShowPage(title: L("show.dream"), subtitle: L("slideshow.empty")) { EmptyView() }
            } else {
                PhotoImage(photo: stills[index], fitMode: .crop, maxPixelSize: 1920, placeholder: false)
                    .id(stills[index].id)
                    .scaleEffect(reduceMotion ? 1.05 : (drift ? 1.14 : 1.06))
                    .offset(x: reduceMotion ? 0 : (drift ? 30 : -30))
                    .transition(.opacity)
                    .ignoresSafeArea()
                RadialGradient(colors: [.clear, Color(hex: 0x1A0B2E).opacity(0.8)], center: .center, startRadius: 200, endRadius: 1400)
                    .ignoresSafeArea()
                LinearGradient(colors: [Color(hex: 0x8B6CFF).opacity(0.2), Color(hex: 0xFF4F9A).opacity(0.2)], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
                Text(L("show.dream")).font(.title2.italic()).foregroundStyle(.white.opacity(0.8))
                    .frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 40)
            }
        }
        .animation(.easeInOut(duration: 3), value: index)
        .task {
            stills = model.stillPhotos.shuffled()
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 14).repeatForever(autoreverses: true)) { drift = true }
        }
        .task(id: stills.count) {
            while stills.count > 1, !Task.isCancelled {
                try? await Task.sleep(for: Self.step)
                index = (index + 1) % stills.count
            }
        }
    }
}

// MARK: Il museo della festa

/// Le foto appese in cornici dorate su una parete da museo, con la targhetta di ogni opera.
struct MuseumView: View {
    @Environment(AppModel.self) private var model
    @State private var stills: [Photo] = []
    @State private var index = 0

    var body: some View {
        let title = model.guests.setup.brand.name.isEmpty ? model.partyTitle : model.guests.setup.brand.name
        ZStack {
            LinearGradient(colors: [Color(hex: 0x3B2F2A), Color(hex: 0x231B17)], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                ScreenTitle(title: L("show.museum"), subtitle: L("museum.subtitle", title))
                if stills.indices.contains(index) {
                    let photo = stills[index]
                    HStack(spacing: 56) {
                        PhotoImage(photo: photo, fitMode: .contain, maxPixelSize: 1920, placeholder: false)
                            .padding(26)
                            .background(Color(hex: 0xF7F2E7))
                            .padding(22)
                            .background(LinearGradient(colors: [Color(hex: 0xC9A44C), Color(hex: 0x8A6A22), Color(hex: 0xE8CF7A)], startPoint: .topLeading, endPoint: .bottomTrailing))
                            .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .id(photo.id)
                            .transition(.opacity)
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("museum.work", index + 1)).font(.title3.italic())
                            Text(photo.sender.flatMap { $0.isEmpty ? nil : $0 } ?? L("museum.unknown")).font(.headline)
                            Text(L("museum.technique")).font(.caption)
                            Text(L("museum.collection", title)).font(.caption)
                        }
                        .foregroundStyle(Color(hex: 0x231B17))
                        .padding(26)
                        .frame(width: 400, alignment: .leading)
                        .background(Color(hex: 0xF2EBDD), in: RoundedRectangle(cornerRadius: 4))
                    }
                } else {
                    Text(L("slideshow.empty")).font(.title3).foregroundStyle(Theme.secondaryText)
                    Spacer()
                }
            }
        }
        .animation(.easeInOut(duration: 1.2), value: index)
        .task {
            stills = Array(model.stillPhotos.prefix(40))
            while stills.count > 1, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(9))
                index = (index + 1) % stills.count
            }
        }
    }
}

// MARK: La festa a capitoli

/// Le foto degli invitati divise nei capitoli segnati dalla regia, ognuno con il suo titolo.
struct ChaptersView: View {
    @Environment(AppModel.self) private var model
    @State private var sections: [(title: String, photos: [Photo])] = []
    @State private var section = 0
    @State private var photoIndex = -1
    @State private var loaded = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if sections.indices.contains(section) {
                let current = sections[section]
                let title = current.title.isEmpty ? L("chapters.first") : current.title
                if current.photos.indices.contains(photoIndex) {
                    PhotoImage(photo: current.photos[photoIndex], fitMode: .contain, maxPixelSize: 1920, placeholder: false)
                        .id(current.photos[photoIndex].id)
                        .transition(.opacity)
                        .ignoresSafeArea()
                    Text(title).font(.headline)
                        .padding(.horizontal, 20).padding(.vertical, 10)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .padding(40)
                } else {
                    VStack(spacing: 16) {
                        Text(L("chapters.number", section + 1).uppercased()).font(.headline).foregroundStyle(Theme.highlight)
                        Text(title).font(.system(size: 76, weight: .bold)).multilineTextAlignment(.center)
                        Text(LP("awards.photo_count", current.photos.count)).foregroundStyle(Theme.secondaryText)
                    }
                    .transition(.opacity)
                }
            } else if loaded {
                ShowPage(title: L("show.chapters"), subtitle: L("chapters.empty")) { EmptyView() }
            }
        }
        .animation(.easeInOut(duration: 0.8), value: photoIndex)
        .animation(.easeInOut(duration: 0.8), value: section)
        .task {
            let guestPhotos = model.stillPhotos.filter { $0.receivedAt != nil }.sorted { $0.receivedAt! < $1.receivedAt! }
            let marks = [PartyHub.ChapterMark(title: "", date: .distantPast)] + model.guests.partyHub.chapters()
            sections = marks.enumerated().compactMap { i, mark in
                let end = i + 1 < marks.count ? marks[i + 1].date : .distantFuture
                let photos = guestPhotos.filter { $0.receivedAt! >= mark.date && $0.receivedAt! < end }
                return photos.isEmpty ? nil : (title: mark.title, photos: photos)
            }
            loaded = true
            while !sections.isEmpty, !Task.isCancelled {
                for s in sections.indices {
                    section = s
                    photoIndex = -1
                    try? await Task.sleep(for: .milliseconds(3_500))
                    for p in sections[s].photos.prefix(12).indices {
                        if Task.isCancelled { return }
                        photoIndex = p
                        try? await Task.sleep(for: .seconds(4))
                    }
                }
            }
        }
    }
}

// MARK: Accadde oggi

/// Foto della libreria Foto scattate in questo stesso giorno negli anni passati.
struct OnThisDayView: View {
    @Environment(AppModel.self) private var model
    @State private var found: [(photo: Photo, taken: Date)]?
    @State private var index = 0

    var body: some View {
        let subtitle: String = switch found {
        case nil: L("on_this_day.loading")
        case let found? where found.isEmpty: L("on_this_day.empty")
        case let found?: LP("awards.photo_count", found.count)
        }
        ShowPage(title: L("show.on_this_day"), subtitle: subtitle) {
            if let found, found.indices.contains(index) {
                let current = found[index]
                ZStack(alignment: .bottomLeading) {
                    Color.black
                    PhotoImage(photo: current.photo, fitMode: .contain, maxPixelSize: 1920, placeholder: false)
                        .id(current.photo.id)
                        .transition(.opacity)
                    Text(LP("on_this_day.years", PartyGames.yearsAgo(current.taken, now: Date())))
                        .font(.title3.bold())
                        .foregroundStyle(Theme.onHighlight)
                        .padding(.horizontal, 20).padding(.vertical, 10)
                        .background(Theme.highlight, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .padding(30)
                }
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
        }
        .animation(.easeInOut(duration: 1), value: index)
        .task {
            if PhotoLibrary.access == .notDetermined { _ = await PhotoLibrary.requestAccess() }
            let result = await Task.detached(priority: .userInitiated) { PhotoLibrary.onThisDay() }.value
            found = result
            while result.count > 1, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(6))
                index = (index + 1) % result.count
            }
        }
    }
}

// MARK: Trova le differenze

/// La stessa foto due volte: in quella di destra cinque differenze, svelate dopo un minuto.
struct DifferencesView: View {
    @Environment(AppModel.self) private var model
    @State private var round = 0
    @State private var photo: Photo?
    @State private var differences: [PartyGames.Difference] = []
    @State private var revealed = false
    @State private var left = DifferencesView.seconds
    private static let seconds = 60

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .center, spacing: 24) {
                    ScreenTitle(title: L("show.differences"), subtitle: L("differences.subtitle", differences.count))
                    Spacer()
                    if !revealed { Text("\(left)″").font(.title.monospacedDigit().bold()).foregroundStyle(Theme.highlight) }
                    Button {
                        if revealed { round += 1 } else { revealed = true }
                    } label: {
                        Label(revealed ? L("differences.next") : L("differences.reveal"), systemImage: "eye.fill")
                    }
                }
                if let photo {
                    HStack(spacing: 24) {
                        PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 1920, placeholder: false)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 1920, placeholder: false)
                            .overlay { DifferenceMarks(differences: differences, revealed: revealed) }
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                } else {
                    Text(L("games.need_photos", 1)).font(.title3).foregroundStyle(Theme.secondaryText)
                    Spacer()
                }
            }
        }
        .task(id: round) {
            photo = model.stillPhotos.randomElement()
            differences = PartyGames.differences(seed: UInt64.random(in: .min ... .max))
            revealed = false
            left = Self.seconds
            while left > 0, !revealed {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                left -= 1
            }
            revealed = true
        }
    }
}

private struct DifferenceMarks: View {
    let differences: [PartyGames.Difference]
    let revealed: Bool

    var body: some View {
        Canvas { context, size in
            let minSide = min(size.width, size.height)
            for diff in differences {
                let center = CGPoint(x: diff.x * size.width, y: diff.y * size.height)
                let radius = diff.radius * minSide
                let color = Color(hex: diff.color)
                func circle(_ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)) }
                switch diff.kind {
                case .dot:
                    context.fill(circle(radius * 0.55), with: .color(color))
                case .star:
                    var path = Path()
                    for k in 0..<5 {
                        let angle = (Double(k) * 72 - 90) * .pi / 180
                        path.move(to: center)
                        path.addLine(to: CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius))
                    }
                    context.stroke(path, with: .color(color), lineWidth: radius / 4)
                case .mirror:
                    context.fill(Path(CGRect(x: center.x - radius, y: center.y - radius / 2, width: radius * 2, height: radius)), with: .color(color.opacity(0.55)))
                case .tint:
                    context.fill(circle(radius), with: .color(color.opacity(0.45)))
                case .hide:
                    context.fill(circle(radius * 0.7), with: .color(.black.opacity(0.7)))
                }
                if revealed { context.stroke(circle(radius * 1.5), with: .color(.white), lineWidth: 6) }
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: Quiz con i telefoni: sui festeggiati e chi è da piccolo

/// Domanda del quiz; `kidID` è la foto da piccolo da mostrare.
struct QuizItem: Equatable {
    let question: String
    let choices: [String]
    let correct: Int
    var kidID: String?
}

enum QuizKind: Hashable {
    case hosts, kids

    var title: String { self == .hosts ? L("games.quiz_hosts_title") : L("games.quiz_kids_title") }
    var emptyText: String { self == .hosts ? L("quiz.hosts_empty") : L("quiz.kids_empty") }
}

/// Una domanda dopo l'altra: i telefoni rispondono, poi la risposta giusta e la classifica.
struct QuizView: View {
    @Environment(AppModel.self) private var model
    let kind: QuizKind
    @State private var items: [QuizItem] = []
    @State private var index = 0
    @State private var revealed = false
    @State private var started = false

    private var phoneURL: String? { model.guests.active ? model.guests.url : nil }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .center, spacing: 24) {
                    ScreenTitle(title: kind.title, subtitle: items.isEmpty ? nil : L("quiz.progress", index + 1, items.count))
                    Spacer()
                    if !items.isEmpty {
                        Button(revealed ? (index + 1 < items.count ? L("quiz.next") : L("quiz.restart")) : L("quiz.reveal")) {
                            if revealed {
                                index = index + 1 < items.count ? index + 1 : 0
                            } else {
                                revealed = true
                                model.guests.phoneGame.reveal()
                            }
                        }
                    }
                }
                if items.indices.contains(index) {
                    let item = items[index]
                    HStack(alignment: .top, spacing: 40) {
                        VStack(alignment: .leading, spacing: 18) {
                            Text(item.question).font(.title2.bold()).fixedSize(horizontal: false, vertical: true)
                            if let kidID = item.kidID {
                                EventLogo(stamp: kidID.hashValue) { model.guests.partySetup.kidPhoto(kidID) }
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            }
                            ForEach(Array(item.choices.enumerated()), id: \.offset) { i, choice in
                                ChoiceLabel(text: "\(String(UnicodeScalar(UInt8(65 + i))))  \(choice)", highlighted: revealed && i == item.correct)
                            }
                            if item.kidID == nil { Spacer(minLength: 0) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if let phoneURL {
                            PhonePlayPanel(game: model.guests.phoneGame, url: phoneURL, revealed: revealed).frame(width: 720)
                        }
                    }
                } else if started {
                    Text(kind.emptyText).font(.title3).foregroundStyle(Theme.secondaryText)
                    Spacer()
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            items = model.quizItems(kind)
            ask()
        }
        .onChange(of: index) { _, _ in
            revealed = false
            ask()
        }
        .onDisappear { model.guests.phoneGame.stop() }
    }

    private func ask() {
        guard phoneURL != nil, items.indices.contains(index) else { return }
        let item = items[index]
        model.guests.phoneGame.ask(question: item.question, choices: item.choices, correct: item.correct)
    }
}

// MARK: - Dati per spettacoli e quiz

extension AppModel {
    /// Invitati attesi contro chi si è fatto vivo: arrivati, foto, nomi sui telefoni, giochi.
    func absentRoll() -> PartyGames.Roll {
        let seen = guests.arrivals.map(\.name) + guests.photos.compactMap(\.sender)
            + guests.partyHub.participants().map(\.name) + guests.phoneGame.names()
        return PartyGames.roll(expected: guests.setup.guestList, seen: seen)
    }

    /// Domande per i quiz con i telefoni.
    func quizItems(_ kind: QuizKind) -> [QuizItem] {
        let setup = guests.partySetup.state
        switch kind {
        case .hosts:
            return PartyGames.parseQuiz(setup.quiz).shuffled().map { question in
                let shuffled = PartyGames.shuffle(question)
                return QuizItem(question: question.text, choices: shuffled.choices, correct: shuffled.right)
            }
        case .kids:
            let pool = setup.kids.map(\.name) + setup.guestList + guests.partyHub.participants().map(\.name)
            return setup.kids.shuffled().compactMap { kid in
                let result = PartyGames.nameChoices(correct: kid.name, pool: pool)
                return result.choices.count < 2 ? nil : QuizItem(question: L("quiz.kids_question"), choices: result.choices, correct: result.right, kidID: kid.id)
            }
        }
    }
}
