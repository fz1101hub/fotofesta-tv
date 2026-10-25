import FotoFestaCore
import SwiftUI

/// Colore dell'evento scelto dalla regia ("#RRGGBB"); senza scelta, l'evidenziazione dell'app.
func accentColor(_ hex: String?) -> Color {
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
        let accent = accentColor(brand.accent)
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
