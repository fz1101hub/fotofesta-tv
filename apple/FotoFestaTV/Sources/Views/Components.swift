import SwiftUI

// Componenti dello stile ad alto contrasto. Le misure sono in punti tvOS (schermo 1920×1080): il
// doppio dei dp dell'app Android. Con il fuoco ogni elemento diventa bianco con il testo scuro, si
// ingrandisce leggermente e proietta un'ombra, così si vede da lontano dove si trova.

/// Pulsante a pillola: grigio scuro a riposo, corallo per `accent`, trasparente con bordo per `ghost`.
struct PillButtonStyle: ButtonStyle {
    enum Kind { case normal, accent, ghost }
    var kind: Kind = .normal

    func makeBody(configuration: Configuration) -> some View {
        PillButtonBody(configuration: configuration, kind: kind)
    }
}

private struct PillButtonBody: View {
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    let configuration: ButtonStyle.Configuration
    let kind: PillButtonStyle.Kind

    var body: some View {
        configuration.label
            .font(.figtree(kind == .ghost ? 28 : 32, .bold))
            .lineLimit(1)
            .padding(.horizontal, kind == .ghost ? 36 : 44)
            .padding(.vertical, kind == .ghost ? 18 : 22)
            .foregroundStyle(foreground)
            .background(Capsule().fill(background))
            .overlay(Capsule().stroke(kind == .ghost && !focused ? Theme.text.opacity(0.28) : .clear, lineWidth: 2))
            .scaleEffect(focused ? 1.06 : (configuration.isPressed ? 0.97 : 1))
            .shadow(color: .black.opacity(focused ? 0.55 : 0), radius: 24, y: 12)
            .opacity(enabled ? 1 : 0.45)
            .animation(.easeOut(duration: 0.15), value: focused)
    }

    private var background: Color {
        if focused { return Theme.focus }
        switch kind {
        case .normal: return Theme.surfaceRaised.opacity(0.92)
        case .accent: return Theme.accent
        case .ghost: return .clear
        }
    }

    private var foreground: Color {
        if focused { return Theme.onFocus }
        return kind == .accent ? Theme.onAccent : Theme.text
    }
}

/// Scheda (sorgenti della home, giochi): superficie scura con bordo leggero, bianca con il fuoco.
struct TileButtonStyle: ButtonStyle {
    var cornerRadius: CGFloat = 28
    var focusScale: CGFloat = 1.06

    func makeBody(configuration: Configuration) -> some View {
        TileButtonBody(configuration: configuration, cornerRadius: cornerRadius, focusScale: focusScale)
    }
}

private struct TileButtonBody: View {
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    let configuration: ButtonStyle.Configuration
    let cornerRadius: CGFloat
    let focusScale: CGFloat

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        configuration.label
            .foregroundStyle(focused ? Theme.onFocus : Theme.text)
            .background(shape.fill(focused ? Theme.focus : Theme.surface.opacity(0.94)))
            .overlay(shape.stroke(focused ? .clear : Theme.text.opacity(0.08), lineWidth: 2))
            .clipShape(shape)
            .scaleEffect(focused ? focusScale : (configuration.isPressed ? 0.98 : 1))
            .shadow(color: .black.opacity(focused ? 0.55 : 0), radius: 28, y: 14)
            .opacity(enabled ? 1 : 0.45)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// Voce di un elenco (impostazioni, opzioni): superficie scura, bianca con il fuoco.
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        RowButtonBody(configuration: configuration)
    }
}

private struct RowButtonBody: View {
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    let configuration: ButtonStyle.Configuration

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
        configuration.label
            .padding(.horizontal, 36)
            .padding(.vertical, 22)
            .frame(maxWidth: .infinity, minHeight: 104, alignment: .leading)
            .foregroundStyle(focused ? Theme.onFocus : Theme.text)
            .background(shape.fill(focused ? Theme.focus : Theme.surface))
            .scaleEffect(focused ? 1.025 : 1)
            .shadow(color: .black.opacity(focused ? 0.55 : 0), radius: 24, y: 12)
            .opacity(enabled ? 1 : 0.45)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// Sezione nella colonna sinistra delle impostazioni: quella aperta ha lo sfondo più chiaro e una barra corallo.
struct CategoryButtonStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        CategoryButtonBody(configuration: configuration, selected: selected)
    }
}

private struct CategoryButtonBody: View {
    @Environment(\.isFocused) private var focused
    let configuration: ButtonStyle.Configuration
    let selected: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        configuration.label
            .font(.figtree(30, .semibold))
            .lineLimit(1)
            .padding(.horizontal, 28)
            .padding(.vertical, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .foregroundStyle(focused ? Theme.onFocus : (selected ? Theme.text : Theme.secondaryText))
            .background(shape.fill(focused ? Theme.focus : (selected ? Theme.surfaceRaised : .clear)))
            .overlay(alignment: .leading) {
                if selected, !focused { Rectangle().fill(Theme.accent).frame(width: 6) }
            }
            .clipShape(shape)
            .scaleEffect(focused ? 1.03 : 1)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// Testo secondario che diventa grigio scuro quando l'elemento che lo contiene ha il fuoco.
struct SecondaryText: View {
    @Environment(\.isFocused) private var focused
    let text: String
    var size: CGFloat = 26
    var weight: FigtreeWeight = .regular

    init(_ text: String, size: CGFloat = 26, weight: FigtreeWeight = .regular) {
        self.text = text
        self.size = size
        self.weight = weight
    }

    var body: some View {
        Text(text)
            .font(.figtree(size, weight))
            .foregroundStyle(focused ? Theme.onFocusSecondary : Theme.secondaryText)
    }
}

/// Contenuto di una voce: titolo, descrizione e a destra il valore o un interruttore.
struct SettingsRowLabel: View {
    let title: String
    var subtitle: String?
    var value: String?
    var isOn: Bool?

    var body: some View {
        HStack(spacing: 32) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.figtree(31, .bold))
                if let subtitle { SecondaryText(subtitle) }
            }
            Spacer(minLength: 0)
            if let isOn {
                SwitchView(isOn: isOn)
            } else if let value {
                SecondaryText("\(value) ›", size: 28, weight: .semibold).lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(isOn.map { $0 ? L("settings.switch_on") : L("settings.switch_off") } ?? value ?? "")
    }
}

/// Interruttore solo visivo: lo comanda la voce che lo contiene.
struct SwitchView: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? Theme.accent : Theme.switchOff)
            .frame(width: 80, height: 44)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle().fill(isOn ? Theme.text : Theme.switchThumbOff).frame(width: 32, height: 32).padding(6)
            }
            .accessibilityHidden(true)
    }
}

/// Contenuto di una scheda delle sorgenti nella home.
struct SourceTileLabel: View {
    let title: String
    let subtitle: String
    let systemImage: String
    var badge: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Image(systemName: systemImage).font(.system(size: 46, weight: .semibold))
            Spacer(minLength: 0)
            Text(title).font(.figtree(32, .bold)).lineLimit(1)
            SecondaryText(subtitle).lineLimit(1)
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .overlay(alignment: .topTrailing) {
            if let badge { Badge(text: badge).padding(24) }
        }
    }
}

/// Etichetta piccola con l'accento, come "In uso".
struct Badge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.figtree(22, .extraBold))
            .foregroundStyle(Theme.onAccent)
            .padding(.horizontal, 18)
            .padding(.vertical, 6)
            .background(Capsule().fill(Theme.accent))
    }
}

/// Logo e nome dell'app, in alto a sinistra.
struct BrandMark: View {
    var body: some View {
        HStack(spacing: 20) {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Theme.accent)
                .frame(width: 60, height: 60)
                .overlay(Image(systemName: "tv").font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.onAccent))
            Text("FotoFesta TV").font(.figtree(34, .bold)).foregroundStyle(Theme.text)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Etichetta maiuscola sopra un titolo.
struct Kicker: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.figtree(24, .bold))
            .tracking(3.4)
            .foregroundStyle(Theme.secondaryText)
            .lineLimit(1)
    }
}

/// Indicatore della festa in corso, con il pallino dell'accento.
struct PartyChip: View {
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(Theme.accent).frame(width: 14, height: 14)
            Text(text).font(.figtree(25, .semibold)).foregroundStyle(Theme.text).lineLimit(1)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 10)
        .background(Capsule().fill(Theme.background.opacity(0.72)))
        .overlay(Capsule().stroke(Theme.text.opacity(0.18), lineWidth: 2))
    }
}

/// Ora attuale, aggiornata a ogni minuto.
struct ClockText: View {
    var body: some View {
        TimelineView(.everyMinute) { context in
            Text(context.date.formatted(date: .omitted, time: .shortened))
                .font(.figtree(36, .semibold).monospacedDigit())
                .foregroundStyle(Theme.text)
        }
    }
}

/// Intestazione delle schermate: etichetta, titolo ed eventuale descrizione.
struct SectionTitle: View {
    let kicker: String
    let title: String
    var subtitle: String?
    var large = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Kicker(text: kicker)
            Text(title).font(.figtree(large ? 76 : 56, .extraBold)).foregroundStyle(Theme.text)
            if let subtitle {
                Text(subtitle).font(.figtree(34)).foregroundStyle(Theme.secondaryText)
            }
        }
    }
}

/// Sfondo delle schermate.
struct ScreenBackground: View {
    var body: some View {
        Theme.background.ignoresSafeArea()
    }
}
