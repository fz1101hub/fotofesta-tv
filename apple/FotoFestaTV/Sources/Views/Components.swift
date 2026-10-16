import FotoFestaCore
import SwiftUI

/// Scheda di una sorgente o di una sezione nella home, nello stile delle schede di tvOS: immagine a
/// tutta superficie, titolo in basso e, con il fuoco, sollevamento e ombra del sistema (`.card`).
struct ShelfCard: View {
    let title: String
    let subtitle: String
    let systemImage: String
    /// Foto demo usata come immagine della scheda (nome del file nel bundle).
    var artwork: String?
    var badge: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ShelfCardLabel(title: title, subtitle: subtitle, systemImage: systemImage, artwork: artwork, badge: badge)
        }
        .buttonStyle(.card)
    }
}

/// Contenuto di una scheda, riusato anche dai NavigationLink.
struct ShelfCardLabel: View {
    let title: String
    let subtitle: String
    let systemImage: String
    var artwork: String?
    var badge: String?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if let artwork {
                PhotoImage(
                    photo: Photo(id: "artwork:\(artwork)", title: title, source: .bundled(fileName: artwork)),
                    fitMode: .crop,
                    maxPixelSize: 900,
                    placeholder: false
                )
                .accessibilityHidden(true)
            } else {
                LinearGradient(colors: [Color(white: 0.26), Color(white: 0.12)], startPoint: .topLeading, endPoint: .bottomTrailing)
            }
            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: systemImage).font(.system(size: 40, weight: .semibold))
                Spacer(minLength: 0)
                Text(title).font(.headline).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(28)
        }
        .frame(width: 420, height: 236)
        .overlay(alignment: .topTrailing) {
            if let badge { Badge(text: badge).padding(20) }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Etichetta piccola con la tinta dell'app, come "In uso".
struct Badge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.bold())
            .foregroundStyle(Theme.onAccent)
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(Capsule().fill(Theme.accent))
    }
}

/// Ora attuale, aggiornata a ogni minuto.
struct ClockText: View {
    var body: some View {
        TimelineView(.everyMinute) { context in
            Text(context.date.formatted(date: .omitted, time: .shortened))
                .font(.title3.monospacedDigit())
        }
    }
}

/// Titolo di una schermata nello stile di tvOS: titolo grande ed eventuale descrizione.
struct ScreenTitle: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.largeTitle.bold())
            if let subtitle {
                Text(subtitle)
                    .font(.title3)
                    .foregroundStyle(Theme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
