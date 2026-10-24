import FotoFestaCore
import SwiftUI

// MARK: - Effetti dello slideshow: filtri, zapping, coriandoli, TG, sigla, pila di polaroid, totem

extension View {
    /// Filtro sulla foto: bianco e nero, seppia, caldo, freddo o anni '70.
    @ViewBuilder
    func photoFilter(_ filter: PhotoFilter) -> some View {
        switch filter {
        case .none: self
        case .blackWhite: grayscale(1)
        case .sepia: grayscale(1).colorMultiply(Color(red: 1, green: 0.88, blue: 0.7))
        case .warm: colorMultiply(Color(red: 1, green: 0.93, blue: 0.82)).saturation(1.1)
        case .cool: colorMultiply(Color(red: 0.86, green: 0.93, blue: 1)).saturation(0.95)
        case .vintage:
            saturation(0.7)
                .colorMultiply(Color(red: 1, green: 0.92, blue: 0.78))
                .overlay(RadialGradient(colors: [.clear, .black.opacity(0.55)], center: .center, startRadius: 300, endRadius: 1300))
        }
    }

    /// TV in verticale (totem): il contenuto ruota di 90° con larghezza e altezza scambiate.
    @ViewBuilder
    func portrait(_ enabled: Bool) -> some View {
        if enabled {
            GeometryReader { geometry in
                self
                    .frame(width: geometry.size.height, height: geometry.size.width)
                    .rotationEffect(.degrees(90))
                    .frame(width: geometry.size.width, height: geometry.size.height)
            }
            .ignoresSafeArea()
        } else {
            self
        }
    }
}

/// Zapping: mezzo secondo di "neve" e il numero del canale a ogni cambio di foto.
struct ZappingOverlay: View {
    let key: String?
    let channel: Int
    @State private var until = Date.distantPast

    var body: some View {
        TimelineView(.animation(minimumInterval: 0.06)) { context in
            if context.date < until {
                ZStack(alignment: .topTrailing) {
                    Canvas { graphics, size in
                        var generator = SeededGenerator(seed: UInt64(context.date.timeIntervalSince1970 * 20))
                        let cell: CGFloat = 16
                        var y: CGFloat = 0
                        while y < size.height {
                            var x: CGFloat = 0
                            while x < size.width {
                                let gray = Double.random(in: 0...1, using: &generator)
                                graphics.fill(Path(CGRect(x: x, y: y, width: cell, height: cell)), with: .color(Color(white: gray)))
                                x += cell
                            }
                            y += cell
                        }
                    }
                    Text(String(format: "CH %02d", channel % 100))
                        .font(.system(size: 72, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(red: 0.42, green: 1, blue: 0.42))
                        .padding(60)
                }
                .ignoresSafeArea()
            }
        }
        .allowsHitTesting(false)
        .onChange(of: key, initial: true) { _, _ in until = Date().addingTimeInterval(0.45) }
    }
}

/// Generatore prevedibile: la "neve" e i coriandoli cambiano a ogni fotogramma ma senza costi.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// Coriandoli che cadono per qualche secondo a ogni `trigger` (foto nuova degli invitati, reazione 🎉).
struct ConfettiOverlay: View {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date.distantPast

    private static let duration: TimeInterval = 3.2
    private static let colors: [Color] = [Theme.highlight, .yellow, .green, .blue, .purple, .white]

    var body: some View {
        TimelineView(.animation) { context in
            let elapsed = context.date.timeIntervalSince(start)
            if trigger > 0, !reduceMotion, elapsed < Self.duration {
                Canvas { graphics, size in
                    var generator = SeededGenerator(seed: UInt64(trigger))
                    let t = elapsed / Self.duration
                    for _ in 0..<90 {
                        let x = Double.random(in: 0...1, using: &generator)
                        let speed = Double.random(in: 0.6...1.4, using: &generator)
                        let drift = Double.random(in: -0.1...0.1, using: &generator)
                        let spin = Double.random(in: 0...720, using: &generator)
                        let side = Double.random(in: 10...22, using: &generator)
                        let color = Self.colors[Int.random(in: 0..<Self.colors.count, using: &generator)]
                        let y = -40 + (size.height + 80) * t * speed * 1.4
                        guard y < size.height + 40 else { continue }
                        var piece = graphics
                        piece.translateBy(x: size.width * (x + drift * t), y: y)
                        piece.rotate(by: .degrees(spin * t))
                        piece.fill(Path(CGRect(x: -side / 2, y: -side / 4, width: side, height: side / 2)), with: .color(color))
                    }
                }
                .ignoresSafeArea()
            }
        }
        .allowsHitTesting(false)
        .onChange(of: trigger) { _, _ in start = Date() }
    }
}

/// TG della festa: logo in alto, titolo della foto nella fascia rossa in basso e notizie che scorrono.
struct NewsOverlay: View {
    let title: String
    let headline: String?
    let ticker: [String]

    var body: some View {
        VStack(alignment: .leading) {
            HStack(spacing: 14) {
                Text(L("news.logo")).font(.headline.weight(.black))
                Text(title).font(.subheadline).lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 22)
            .padding(.vertical, 10)
            .background(Color(red: 0.78, green: 0.16, blue: 0.16), in: RoundedRectangle(cornerRadius: 8))
            .padding(40)
            Spacer()
            HStack(spacing: 0) {
                Text(L("news.breaking"))
                    .font(.headline.weight(.black))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(Color(red: 0.78, green: 0.16, blue: 0.16))
                Text(headline ?? title)
                    .font(.headline.bold())
                    .foregroundStyle(Color(white: 0.07))
                    .lineLimit(1)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 10)
                    .background(Color.white)
            }
            .padding(.leading, 60)
            if !ticker.isEmpty { Ticker(text: ticker.joined(separator: "   •   ")) }
        }
        .padding(.bottom, 30)
        .allowsHitTesting(false)
    }
}

/// Testo che scorre da destra a sinistra, come le notizie in fondo allo schermo.
private struct Ticker: View {
    let text: String
    @State private var width: CGFloat = 1

    var body: some View {
        TimelineView(.animation) { context in
            GeometryReader { geometry in
                let speed: Double = 90
                let total = Double(width + geometry.size.width)
                let shift = (context.date.timeIntervalSinceReferenceDate * speed).truncatingRemainder(dividingBy: max(total, 1))
                Text(text)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .fixedSize()
                    .background(GeometryReader { text in Color.clear.onAppear { width = text.size.width } })
                    .offset(x: geometry.size.width - shift)
            }
        }
        .frame(height: 50)
        .frame(maxWidth: .infinity)
        .background(Color(red: 0.06, green: 0.08, blue: 0.13).opacity(0.9))
        .clipped()
    }
}

/// Sigla d'apertura: il titolo della festa per qualche secondo, quando parte lo slideshow.
struct OpeningTitles: View {
    let title: String
    let subtitle: String
    @State private var visible = true
    @State private var shown = false

    var body: some View {
        if visible {
            ZStack {
                Color.black.opacity(0.78)
                VStack(spacing: 20) {
                    Text(L("opening.presents")).font(.title3).foregroundStyle(Theme.highlight)
                    Text(title).font(.system(size: 110, weight: .black)).multilineTextAlignment(.center).minimumScaleFactor(0.5)
                    Text(subtitle).font(.title3).foregroundStyle(Theme.secondaryText)
                }
                .scaleEffect(shown ? 1 : 0.92)
            }
            .opacity(shown ? 1 : 0)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .task {
                withAnimation(.easeOut(duration: 0.9)) { shown = true }
                try? await Task.sleep(for: .seconds(4.4))
                withAnimation(.easeIn(duration: 0.9)) { shown = false }
                try? await Task.sleep(for: .seconds(0.9))
                visible = false
            }
        }
    }
}

/// Pila di polaroid: la foto nuova cade sopra le precedenti, ognuna un po' storta.
struct StackSlideView: View {
    let photo: Photo
    let previous: [Photo]
    let filter: PhotoFilter

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color(red: 0.11, green: 0.08, blue: 0.06)
                ForEach(Array((previous.prefix(3).reversed() + [photo]).enumerated()), id: \.element.id) { _, item in
                    Polaroid(photo: item, filter: filter, height: geometry.size.height * 0.78, isNew: item.id == photo.id)
                }
            }
        }
        .ignoresSafeArea()
    }
}

private struct Polaroid: View {
    let photo: Photo
    let filter: PhotoFilter
    let height: CGFloat
    let isNew: Bool
    @State private var dropped = false

    var body: some View {
        let hash = Self.stableHash(photo.id)
        PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 1600, placeholder: false)
            .photoFilter(filter)
            .frame(width: height * 0.86 - 36, height: height - 88)
            .clipped()
            .padding(.horizontal, 18)
            .padding(.top, 18)
            .padding(.bottom, 70)
            .background(Color(red: 0.97, green: 0.96, blue: 0.93), in: RoundedRectangle(cornerRadius: 4))
            .shadow(color: .black.opacity(0.5), radius: 20, y: 10)
            .rotationEffect(.degrees(Double(hash % 15) - 7))
            .offset(x: CGFloat((hash / 7) % 120) - 60)
            .scaleEffect(dropped || !isNew ? 1 : 1.35)
            .opacity(dropped || !isNew ? 1 : 0)
            .onAppear { withAnimation(.easeOut(duration: 0.65)) { dropped = true } }
    }

    /// Sempre lo stesso numero per la stessa foto: ogni polaroid resta storta allo stesso modo.
    private static func stableHash(_ text: String) -> Int {
        var hash = 5381
        for byte in text.utf8 { hash = (hash &* 33 &+ Int(byte)) & 0x7FFF_FFFF }
        return hash
    }
}
