import FotoFestaCore
import SwiftUI

/// Colore dell'evento scelto dalla regia ("#RRGGBB"); senza scelta, l'evidenziazione dell'app.
func eventAccentColor(_ hex: String?) -> Color {
    guard let hex, PartySetup.validColor(hex), let value = UInt32(hex.dropFirst(), radix: 16) else { return Theme.highlight }
    return Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
}

/// Logo dell'evento come immagine, riletto solo quando cambia (`stamp`).
struct EventLogo: View {
    let stamp: Int
    let load: () -> Data?
    @State private var image: PlatformImage?

    var body: some View {
        Group {
            if let image {
                Image(platformImage: image).resizable().scaledToFit()
            }
        }
        .task(id: stamp) { image = load().flatMap { PlatformImage(data: $0) } }
    }
}

/// Cornice dell'evento sullo slideshow: una fascia in alto con logo e nome, o un bordo del colore
/// dell'evento con il nome in basso.
struct EventFrameOverlay: View {
    let style: FrameStyle
    let brand: PartySetup.Brand
    let loadLogo: () -> Data?

    var body: some View {
        let accent = eventAccentColor(brand.accent)
        switch style {
        case .none:
            EmptyView()
        case .band:
            if !brand.name.isEmpty || brand.hasLogo {
                HStack(spacing: 24) {
                    if brand.hasLogo { EventLogo(stamp: brand.logoStamp, load: loadLogo).frame(height: 70) }
                    if !brand.name.isEmpty { Text(brand.name).font(.title2.bold()).lineLimit(1) }
                }
                .padding(.horizontal, 36)
                .padding(.vertical, 14)
                .background(accent.opacity(0.85), in: Capsule())
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .padding(.top, 40)
                .allowsHitTesting(false)
            }
        case .border:
            ZStack(alignment: .bottom) {
                Rectangle().strokeBorder(accent, lineWidth: 28)
                HStack(spacing: 18) {
                    if brand.hasLogo { EventLogo(stamp: brand.logoStamp, load: loadLogo).frame(height: 54) }
                    if !brand.name.isEmpty { Text(brand.name).font(.headline.bold()).lineLimit(1) }
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 10)
                .background(accent, in: Capsule())
                .foregroundStyle(.white)
                .padding(.bottom, 8)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
    }
}

/// Sponsor tra le foto: ogni `every` cambi di foto uno sponsor a tutto schermo per qualche secondo, a turno.
struct SponsorOverlay: View {
    let tick: Int
    let every: Int
    let sponsors: [String]
    let load: (String) -> Data?
    @State private var shown: PlatformImage?

    private static let screenTime: Duration = .seconds(6)

    var body: some View {
        ZStack {
            if let shown {
                Color.black.ignoresSafeArea()
                Image(platformImage: shown).resizable().scaledToFit().padding(80)
                Text(L("sponsor.thanks")).font(.headline).foregroundStyle(Theme.secondaryText)
                    .frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 30)
            }
        }
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.5), value: shown != nil)
        .task(id: tick) {
            guard every > 0, !sponsors.isEmpty, tick > 0, tick % every == 0 else { return }
            let id = sponsors[(tick / every - 1) % sponsors.count]
            guard let data = load(id), let image = PlatformImage(data: data) else { return }
            shown = image
            try? await Task.sleep(for: Self.screenTime)
            shown = nil
        }
    }
}

/// Il prossimo momento della scaletta, se manca meno di mezz'ora.
struct ScheduleBadge: View {
    let schedule: [PartySetup.Slot]

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: context.date)
            let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
            if let next = schedule.first(where: { $0.minuteOfDay >= minute && $0.minuteOfDay - minute <= 30 }) {
                VStack(alignment: .leading, spacing: 4) {
                    Text((next.minuteOfDay == minute ? L("schedule.now") : L("schedule.soon")).uppercased())
                        .font(.caption.bold()).foregroundStyle(Theme.secondaryText)
                    Text("\(next.time) · \(next.title)").font(.headline).lineLimit(2)
                }
                .padding(.horizontal, 22)
                .padding(.vertical, 12)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
        }
        .allowsHitTesting(false)
    }
}

/// Margini dello schermo per i proiettori e le TV che tagliano i bordi.
struct ScreenMargin: ViewModifier {
    let percent: Int

    func body(content: Content) -> some View {
        content.scaleEffect(1 - CGFloat(percent) * 2 / 100)
    }
}

/// La "festa rivale": ogni `every` foto arriva, per gioco, un messaggio di sfida da una festa che
/// non esiste. Messaggi pronti, a turno.
struct RivalPartyOverlay: View {
    let tick: Int
    @State private var message: String?
    private static let every = 30
    private static let messages = [
        L("rival.1"), L("rival.2"), L("rival.3"), L("rival.4"), L("rival.5"), L("rival.6"), L("rival.7"), L("rival.8"),
    ]

    var body: some View {
        ZStack {
            if let message {
                HStack(spacing: 18) {
                    Text("📡").font(.largeTitle)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("rival.from").uppercased()).font(.caption.bold()).foregroundStyle(Color(hex: 0xFF4F9A))
                        Text(message).font(.title3.bold())
                    }
                }
                .padding(.horizontal, 26)
                .padding(.vertical, 16)
                .frame(maxWidth: 820)
                .background(Color(hex: 0x1B1E25).opacity(0.95), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color(hex: 0xFF4F9A), lineWidth: 3))
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.top, 40)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.5), value: message)
        .task(id: tick / Self.every) {
            guard tick > 0, tick % Self.every == 0 else { return }
            message = Self.messages[(tick / Self.every - 1) % Self.messages.count]
            try? await Task.sleep(for: .seconds(9))
            message = nil
        }
    }
}

/// La TV che si annoia: senza foto nuove dagli invitati da dieci minuti, ogni tre minuti compare per
/// mezzo minuto un invito a mandarne una.
struct BoredOverlay: View {
    let lastPhoto: Date?
    @State private var since = Date()
    private static let after: TimeInterval = 10 * 60
    private static let cycle: TimeInterval = 3 * 60
    private static let shown: TimeInterval = 30

    var body: some View {
        TimelineView(.periodic(from: .now, by: 10)) { context in
            let quiet = context.date.timeIntervalSince(max(lastPhoto ?? .distantPast, since))
            let visible = quiet >= Self.after && (quiet - Self.after).truncatingRemainder(dividingBy: Self.cycle) < Self.shown
            ZStack {
                if visible {
                    HStack(spacing: 16) {
                        Text("🥱").font(.system(size: 60))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(L("bored.title")).font(.title3.bold())
                            Text(L("bored.body", Int(quiet / 60))).font(.callout).foregroundStyle(Theme.secondaryText)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
                    .frame(maxWidth: 680)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.6), value: visible)
        }
        .allowsHitTesting(false)
    }
}

/// Panoramica a tutta altezza che scorre da sinistra a destra durante la permanenza della foto,
/// invece di restare una striscia sottile al centro. Compare sopra la foto intera quando è pronta.
struct PanoramaOverlay: View {
    let photo: Photo
    let playing: Bool
    let seconds: Int
    @State private var image: PlatformImage?
    @State private var aspect: CGFloat = 0
    @State private var progress: CGFloat = 0

    /// Da questo rapporto larghezza/altezza in su la foto è una panoramica e scorre.
    static let minAspect: CGFloat = 2.2
    /// Oltre questo rapporto la panoramica si taglia ai lati.
    private static let maxAspect: CGFloat = 4

    var body: some View {
        GeometryReader { geometry in
            if let image {
                let width = geometry.size.height * min(aspect, Self.maxAspect)
                let travel = max(width - geometry.size.width, 0)
                Image(platformImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: width, height: geometry.size.height)
                    .offset(x: -travel * progress)
                    .frame(width: geometry.size.width, height: geometry.size.height, alignment: .leading)
                    .clipped()
                    .transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .animation(.easeIn(duration: 0.6), value: image != nil)
        .task(id: photo.id) {
            guard let loaded = await ImageLoader.shared.image(for: photo, maxPixelSize: 3840),
                  let cgImage = loaded.platformCGImage, cgImage.height > 0 else { return }
            let ratio = CGFloat(cgImage.width) / CGFloat(cgImage.height)
            guard ratio >= Self.minAspect else { return }
            aspect = ratio
            image = loaded
        }
        .onChange(of: image != nil && playing) { _, run in
            guard run, progress == 0 else { return }
            withAnimation(.easeInOut(duration: Double(max(seconds, 1)))) { progress = 1 }
        }
    }
}
