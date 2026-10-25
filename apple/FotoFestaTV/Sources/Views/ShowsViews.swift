import FotoFestaCore
import SwiftUI

// MARK: - Spettacoli sullo schermo: riavvolgi la festa, codice a barre, fotomosaico, titoli di coda,
// Televideo, cielo della festa e la festa in numeri

private struct ShowTile: Identifiable {
    let id: Route
    let title: String
    let body: String
    let systemImage: String
}

struct ShowsView: View {
    @Environment(AppModel.self) private var model

    private var tiles: [ShowTile] {
        [
            ShowTile(id: .rewind, title: L("show.rewind"), body: L("show.rewind_body"), systemImage: "backward.fill"),
            ShowTile(id: .barcode, title: L("show.barcode"), body: L("show.barcode_body"), systemImage: "barcode"),
            ShowTile(id: .photoMosaic, title: L("show.mosaic"), body: L("show.mosaic_body"), systemImage: "square.grid.3x3.fill"),
            ShowTile(id: .credits, title: L("show.credits"), body: L("show.credits_body"), systemImage: "film.fill"),
            ShowTile(id: .teletext, title: L("show.teletext"), body: L("show.teletext_body"), systemImage: "tv.fill"),
            ShowTile(id: .sky, title: L("show.sky"), body: L("show.sky_body"), systemImage: "sparkles"),
            ShowTile(id: .stats, title: L("show.stats"), body: L("show.stats_body"), systemImage: "chart.bar.xaxis"),
            ShowTile(id: .absent, title: L("show.absent"), body: L("show.absent_body"), systemImage: "person.fill.questionmark"),
            ShowTile(id: .artwork, title: L("show.artwork"), body: L("show.artwork_body"), systemImage: "paintpalette.fill"),
            ShowTile(id: .soundShow, title: L("show.sound"), body: L("show.sound_body"), systemImage: "music.note"),
            ShowTile(id: .livingQR, title: L("show.living_qr"), body: L("show.living_qr_body"), systemImage: "qrcode"),
            ShowTile(id: .dream, title: L("show.dream"), body: L("show.dream_body"), systemImage: "cloud.fill"),
            ShowTile(id: .museum, title: L("show.museum"), body: L("show.museum_body"), systemImage: "building.columns.fill"),
            ShowTile(id: .chapters, title: L("show.chapters"), body: L("show.chapters_body"), systemImage: "book.fill"),
            ShowTile(id: .onThisDay, title: L("show.on_this_day"), body: L("show.on_this_day_body"), systemImage: "clock.arrow.circlepath"),
        ]
    }

    var body: some View {
        let enoughPhotos = model.stillPhotos.count >= 2
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("games.shows_title"), subtitle: L("games.shows_body"))
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 32), count: 4), spacing: 32) {
                        ForEach(tiles) { tile in
                            NavigationLink(value: tile.id) {
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
                            // Le foto servono a tutti tranne Televideo, cielo, numeri e titoli di coda.
                            .disabled(!enoughPhotos && [.rewind, .barcode, .photoMosaic, .artwork, .soundShow, .dream, .museum].contains(tile.id))
                        }
                    }
                    .padding(.vertical, 30)
                }
                .scrollClipDisabled()
            }
        }
    }
}

/// Pixel RGBA8 della foto ridotta a `width`×`height`, per i colori medi.
func pixels(of photo: Photo, width: Int, height: Int) async -> (rgba: [UInt8], image: CGImage)? {
    guard let image = await ImageLoader.shared.image(for: photo, maxPixelSize: max(width, height) * 4)?.platformCGImage else { return nil }
    var data = [UInt8](repeating: 0, count: width * height * 4)
    let drawn: CGImage? = data.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(
            data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        // Riempie la casella come "crop": la foto copre tutto, i bordi in più si tagliano.
        let scale = max(CGFloat(width) / CGFloat(image.width), CGFloat(height) / CGFloat(image.height))
        let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: (CGFloat(width) - size.width) / 2, y: (CGFloat(height) - size.height) / 2, width: size.width, height: size.height))
        return context.makeImage()
    }
    guard let drawn else { return nil }
    return (data, drawn)
}

private func color(_ value: UInt32) -> Color {
    Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
}

// MARK: - Riavvolgi la festa

/// La festa al contrario, veloce, come una videocassetta riavvolta: dall'ultima foto alla prima.
struct RewindView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var ordered: [Photo] = []
    @State private var index = 0
    @State private var run = 0

    var body: some View {
        let done = index >= ordered.count - 1
        ZStack {
            Color.black.ignoresSafeArea()
            if ordered.indices.contains(index) {
                PhotoImage(photo: ordered[index], fitMode: .contain, maxPixelSize: 1920, placeholder: false)
                    .offset(x: reduceMotion || done ? 0 : CGFloat.random(in: -3...3))
                    .ignoresSafeArea()
            }
            // Righe di scansione.
            Canvas { context, size in
                var y: CGFloat = 0
                while y < size.height {
                    context.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 2)), with: .color(.black.opacity(0.18)))
                    y += 6
                }
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            VStack {
                HStack {
                    VhsText(done ? "■ " + L("rewind.end") : "◀◀ REW")
                    Spacer()
                    if ordered.indices.contains(index), let date = ordered[index].receivedAt {
                        VhsText(date.formatted(date: .omitted, time: .standard))
                    }
                }
                Spacer()
                HStack(spacing: 24) {
                    Button(L("rewind.again")) { run += 1 }
                    Spacer()
                }
            }
            .padding(60)
        }
        .onAppear {
            ordered = model.stillPhotos.sorted { ($0.receivedAt ?? .distantPast) > ($1.receivedAt ?? .distantPast) }
        }
        .task(id: run) {
            index = 0
            while index < ordered.count - 1 {
                try? await Task.sleep(for: .milliseconds(reduceMotion ? 1000 : ordered.count > 60 ? 220 : 420))
                if Task.isCancelled { return }
                index += 1
            }
        }
    }
}

private struct VhsText: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text).font(.system(size: 48, weight: .bold, design: .monospaced)).foregroundStyle(.white).shadow(color: .black, radius: 4)
    }
}

// MARK: - Codice a barre e fotomosaico

/// Il codice a barre della festa: una striscia per ogni foto, del suo colore medio, in ordine.
struct BarcodeView: View {
    @Environment(AppModel.self) private var model
    @State private var colors: [UInt32] = []
    @State private var total = 0

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(
                    title: L("show.barcode"),
                    subtitle: colors.count < total ? L("barcode.loading", colors.count, total) : LP("barcode.done", colors.count)
                )
                Canvas { context, size in
                    guard !colors.isEmpty else { return }
                    let stripe = size.width / CGFloat(colors.count)
                    for (index, value) in colors.enumerated() {
                        context.fill(Path(CGRect(x: CGFloat(index) * stripe, y: 0, width: stripe + 1, height: size.height)), with: .color(color(value)))
                    }
                }
                .background(Color.black, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
        }
        .task {
            let ordered = Array(model.stillPhotos.sorted { ($0.receivedAt ?? .distantPast) < ($1.receivedAt ?? .distantPast) }.prefix(600))
            total = ordered.count
            for photo in ordered {
                if Task.isCancelled { return }
                guard let result = await pixels(of: photo, width: 8, height: 8) else { continue }
                colors.append(PartyShows.average(rgba: result.rgba))
            }
        }
    }
}

/// Fotomosaico: una foto grande fatta di tante piccole foto della festa, ognuna messa dove il suo
/// colore medio somiglia di più a quel punto della foto grande.
struct PhotoMosaicView: View {
    @Environment(AppModel.self) private var model
    @State private var target: Photo?
    @State private var cells: [CGImage]?
    @State private var loaded = 0
    @State private var hint = true

    private static let columns = 40
    private static let rows = 22
    private static let maxTiles = 120

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 28) {
                ScreenTitle(
                    title: L("show.mosaic"),
                    subtitle: cells == nil ? L("mosaic.loading", loaded, min(model.stillPhotos.count, Self.maxTiles)) : L("show.mosaic_body")
                )
                ZStack {
                    if let cells {
                        Canvas { context, size in
                            let width = size.width / CGFloat(Self.columns)
                            let height = size.height / CGFloat(Self.rows)
                            for (index, image) in cells.enumerated() {
                                let rect = CGRect(x: CGFloat(index % Self.columns) * width, y: CGFloat(index / Self.columns) * height, width: width + 1, height: height + 1)
                                context.draw(Image(decorative: image, scale: 1), in: rect)
                            }
                        }
                        if hint, let target {
                            PhotoImage(photo: target, fitMode: .crop, maxPixelSize: 1920, placeholder: false).opacity(0.3)
                        }
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                HStack(spacing: 24) {
                    Button(L("mosaic.new")) { target = model.stillPhotos.randomElement() }
                    Button(hint ? L("mosaic.hint_off") : L("mosaic.hint_on")) { hint.toggle() }
                }
            }
        }
        .onAppear { if target == nil { target = model.stillPhotos.randomElement() } }
        .task(id: target?.id) {
            guard let target else { return }
            cells = nil
            loaded = 0
            var tiles: [(color: UInt32, image: CGImage)] = []
            for photo in model.stillPhotos.shuffled().prefix(Self.maxTiles) {
                if Task.isCancelled { return }
                if let result = await pixels(of: photo, width: 64, height: 64) { tiles.append((PartyShows.average(rgba: result.rgba), result.image)) }
                loaded += 1
            }
            guard !tiles.isEmpty, let grid = await pixels(of: target, width: Self.columns, height: Self.rows) else { return }
            let cellColors = (0..<(Self.columns * Self.rows)).map { index in
                UInt32(grid.rgba[index * 4]) << 16 | UInt32(grid.rgba[index * 4 + 1]) << 8 | UInt32(grid.rgba[index * 4 + 2])
            }
            cells = PartyShows.mosaic(cells: cellColors, tiles: tiles.map(\.color)).map { tiles[$0].image }
        }
    }
}

// MARK: - Titoli di coda

/// Titoli di coda della festa, che scorrono come al cinema.
struct CreditsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var sections: [PartyShows.CreditSection] = []
    @State private var offset: CGFloat = 0
    @State private var contentHeight: CGFloat = 1

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                Color.black.ignoresSafeArea()
                VStack(spacing: 56) {
                    Text(model.partyTitle).font(.system(size: 90, weight: .black)).multilineTextAlignment(.center)
                    ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                        VStack(spacing: 12) {
                            Text(creditTitle(section.kind).uppercased()).font(.headline).tracking(3).foregroundStyle(Theme.highlight)
                            ForEach(section.names, id: \.self) { Text($0).font(.title2) }
                        }
                    }
                    Text(L("credits.directed")).font(.title3).foregroundStyle(Theme.secondaryText)
                    Text(L("credits.thanks")).font(.title.bold()).multilineTextAlignment(.center)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .background(GeometryReader { content in Color.clear.onAppear { contentHeight = content.size.height } })
                .offset(y: reduceMotion ? 0 : offset)
            }
            .clipped()
            .task(id: contentHeight) {
                guard !reduceMotion else { return }
                while !Task.isCancelled {
                    offset = geometry.size.height
                    let distance = geometry.size.height + contentHeight
                    let duration = max(Double(distance) / 60, 8)
                    withAnimation(.linear(duration: duration)) { offset = -contentHeight }
                    try? await Task.sleep(for: .seconds(duration + 2))
                }
            }
        }
        .onAppear { sections = model.credits() }
    }

    private func creditTitle(_ kind: PartyShows.CreditKind) -> String {
        switch kind {
        case .photographers: L("credits.photographers")
        case .messages: L("credits.messages")
        case .champions: L("credits.champions")
        case .guests: L("credits.guests")
        }
    }
}

// MARK: - Televideo

/// Pagina 888 del Televideo della festa: notizie, classifica e numeri, con le sottopagine che girano.
struct TeletextView: View {
    @Environment(AppModel.self) private var model

    private static let yellow = Color(red: 1, green: 1, blue: 0)
    private static let cyan = Color(red: 0, green: 1, blue: 1)
    private static let green = Color(red: 0, green: 1, blue: 0)
    private static let red = Color(red: 1, green: 0, blue: 0)
    private static let magenta = Color(red: 1, green: 0, blue: 1)
    private static let blue = Color(red: 0, green: 0, blue: 1)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let page = Int(context.date.timeIntervalSince1970 / 8) % 3
            let stats = model.partyStats()
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("P888").foregroundStyle(.white)
                        Text(model.partyTitle.uppercased()).foregroundStyle(Self.yellow).lineLimit(1)
                        Spacer()
                        Text(context.date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).hour().minute().second()).uppercased()).foregroundStyle(.white)
                    }
                    Text(" " + [L("teletext.news"), L("teletext.ranking"), L("teletext.numbers")][page].uppercased() + " ")
                        .font(.system(size: 52, weight: .bold, design: .monospaced))
                        .foregroundStyle(Self.yellow)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Self.blue)
                    switch page {
                    case 0:
                        let messages = model.guests.messages.suffix(8).reversed()
                        if messages.isEmpty {
                            Text(L("teletext.no_news")).foregroundStyle(Self.cyan)
                        } else {
                            ForEach(Array(messages.enumerated()), id: \.offset) { index, message in
                                Text((message.name.map { $0.uppercased() + ": " } ?? "") + message.text)
                                    .foregroundStyle(index % 2 == 0 ? Self.cyan : .white)
                                    .lineLimit(2)
                            }
                        }
                    case 1:
                        if model.guests.coins.isEmpty {
                            Text(L("coins.empty")).foregroundStyle(Self.cyan)
                        } else {
                            ForEach(Array(model.guests.coins.prefix(9).enumerated()), id: \.offset) { index, entry in
                                HStack {
                                    Text("\(index + 1).").foregroundStyle(Self.yellow).frame(width: 90, alignment: .leading)
                                    Text(entry.name.uppercased()).foregroundStyle(.white).lineLimit(1)
                                    Spacer()
                                    Text("\(entry.coins)").foregroundStyle(Self.green)
                                }
                            }
                        }
                    default:
                        line(L("stats.photos"), "\(stats.guestPhotos)", Self.green)
                        line(L("stats.photographers"), "\(stats.photographers)", Self.cyan)
                        line(L("stats.messages"), "\(stats.messages)", Self.magenta)
                        line(L("stats.reactions"), "\(stats.reactions)", Self.yellow)
                        if let hour = stats.busiestHour { line(L("stats.busiest"), String(format: "%02d:00", hour), Self.red) }
                        line(L("teletext.weather"), L("teletext.weather_value"), Self.yellow)
                    }
                    Spacer()
                    HStack(spacing: 16) {
                        ForEach([Self.red, Self.green, Self.yellow, Self.cyan], id: \.self) { Rectangle().fill($0).frame(width: 140, height: 30) }
                        Spacer()
                        Text("\(page + 1)/3").foregroundStyle(.white)
                    }
                }
                .font(.system(size: 34, weight: .bold, design: .monospaced))
                .padding(.horizontal, 80)
                .padding(.vertical, 50)
            }
        }
        .focusable()
    }

    private func line(_ label: String, _ value: String, _ color: Color) -> some View {
        HStack {
            Text(label.uppercased()).foregroundStyle(.white).lineLimit(1)
            Spacer()
            Text(value).foregroundStyle(color)
        }
    }
}

// MARK: - Cielo della festa

/// Cielo della festa (anche da proiettare sul soffitto): ogni invitato è una stella con il suo nome,
/// unite in una costellazione nell'ordine di arrivo; a ogni foto nuova passa una stella cadente.
struct SkyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shootingAt = Date.distantPast
    @State private var shooting = 0

    private static let background: [(x: Double, y: Double, seed: Double)] = {
        var generator = SeededGenerator(seed: 42)
        return (0..<220).map { _ in (Double.random(in: 0...1, using: &generator), Double.random(in: 0...1, using: &generator), Double.random(in: 0...1, using: &generator)) }
    }()

    var body: some View {
        let names = model.lotteryCandidates()
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
            let phase = context.date.timeIntervalSinceReferenceDate / 6 * 2 * .pi
            ZStack(alignment: .bottomLeading) {
                Color(red: 0.01, green: 0.01, blue: 0.04).ignoresSafeArea()
                Canvas { graphics, size in
                    for star in Self.background {
                        let alpha = reduceMotion ? 0.6 : 0.35 + 0.45 * (0.5 + 0.5 * sin(phase + star.seed * 12))
                        let radius = 1.2 + star.seed * 1.6
                        graphics.fill(Path(ellipseIn: CGRect(x: star.x * size.width - radius, y: star.y * size.height - radius, width: radius * 2, height: radius * 2)), with: .color(.white.opacity(alpha)))
                    }
                    let points = names.map { Self.position($0, in: size) }
                    if points.count > 1 {
                        var path = Path()
                        path.addLines(points)
                        graphics.stroke(path, with: .color(Color(red: 0.63, green: 0.77, blue: 1).opacity(0.35)), lineWidth: 2)
                    }
                    for (name, point) in zip(names, points) {
                        graphics.fill(Path(ellipseIn: CGRect(x: point.x - 7, y: point.y - 7, width: 14, height: 14)), with: .color(Color(red: 1, green: 0.96, blue: 0.76)))
                        graphics.draw(Text(name).font(.callout).foregroundColor(Color(red: 0.87, green: 0.9, blue: 1)), at: CGPoint(x: point.x + 14, y: point.y - 12), anchor: .leading)
                    }
                    let t = context.date.timeIntervalSince(shootingAt) / 1.4
                    if !reduceMotion, t >= 0, t < 1 {
                        var generator = SeededGenerator(seed: UInt64(shooting))
                        let start = CGPoint(x: size.width * Double.random(in: 0.1...0.6, using: &generator), y: size.height * Double.random(in: 0...0.4, using: &generator))
                        let head = CGPoint(x: start.x + size.width * 0.35 * t, y: start.y + size.height * 0.25 * t)
                        var tail = Path()
                        tail.move(to: CGPoint(x: start.x + (head.x - start.x) * 0.6, y: start.y + (head.y - start.y) * 0.6))
                        tail.addLine(to: head)
                        graphics.stroke(tail, with: .color(.white.opacity(1 - t)), lineWidth: 4)
                    }
                }
                .ignoresSafeArea()
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("show.sky")).font(.title2.bold())
                    Text(LP("sky.stars", names.count)).foregroundStyle(Theme.secondaryText)
                }
                .padding(60)
            }
        }
        .focusable()
        .onChange(of: model.approvedGuestCount) { _, _ in
            shooting += 1
            shootingAt = Date()
        }
    }

    /// Posizione fissa della stella di un invitato, calcolata dal nome (resta uguale a ogni apertura).
    private static func position(_ name: String, in size: CGSize) -> CGPoint {
        var hash = 5381
        for byte in name.lowercased().utf8 { hash = (hash &* 33 &+ Int(byte)) & 0x7FFF_FFFF }
        let x = 0.08 + Double(hash % 1000) / 1000 * 0.84
        let y = 0.1 + Double((hash / 1000) % 1000) / 1000 * 0.7
        return CGPoint(x: x * size.width, y: y * size.height)
    }
}

// MARK: - La festa in numeri

struct StatsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let stats = model.partyStats()
        let favorite = stats.favorite.flatMap { id in model.photos.first { $0.id == id } }
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("show.stats"), subtitle: L("show.stats_body"))
                HStack(spacing: 24) {
                    StatCard(value: "\(stats.guestPhotos)", label: L("stats.photos"))
                    StatCard(value: "\(stats.photographers)", label: L("stats.photographers"))
                    StatCard(value: "\(stats.messages)", label: L("stats.messages"))
                    StatCard(value: "\(stats.reactions)" + (stats.topReaction.flatMap { PartyHub.Reaction(rawValue: $0)?.emoji }.map { " " + $0 } ?? ""), label: L("stats.reactions"))
                    StatCard(value: "\(stats.coins)", label: L("stats.coins"))
                }
                HStack(alignment: .top, spacing: 32) {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(stats.busiestHour.map { L("stats.busiest_value", String(format: "%02d:00", $0)) } ?? L("stats.no_photos")).font(.title3.bold())
                        if let first = stats.hourly.firstIndex(where: { $0 > 0 }), let last = stats.hourly.lastIndex(where: { $0 > 0 }) {
                            let maximum = max(stats.hourly[first...last].max() ?? 1, 1)
                            HStack(alignment: .bottom, spacing: 8) {
                                ForEach(first...last, id: \.self) { hour in
                                    VStack(spacing: 6) {
                                        Spacer(minLength: 0)
                                        RoundedRectangle(cornerRadius: 6)
                                            .fill(hour == stats.busiestHour ? Theme.highlight : Color(white: 0.3))
                                            .frame(height: CGFloat(stats.hourly[hour]) / CGFloat(maximum) * 300)
                                        Text(String(format: "%02d", hour)).font(.caption2).foregroundStyle(Theme.secondaryText)
                                    }
                                }
                            }
                            .frame(height: 340)
                        }
                    }
                    .padding(28)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    if let favorite {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(L("stats.favorite").uppercased()).font(.headline).foregroundStyle(Theme.secondaryText)
                            PhotoImage(photo: favorite, fitMode: .crop, maxPixelSize: 1200)
                                .frame(width: 640, height: 360)
                                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .focusable()
    }
}

private struct StatCard: View {
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(value).font(.system(size: 64, weight: .black)).foregroundStyle(Theme.highlight).lineLimit(1).minimumScaleFactor(0.5)
            Text(label).font(.callout).foregroundStyle(Theme.secondaryText).lineLimit(2)
        }
        .padding(28)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}
