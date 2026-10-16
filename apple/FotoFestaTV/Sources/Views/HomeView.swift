import FotoFestaCore
import SwiftUI

/// Home nello stile di tvOS: la raccolta in uso a tutto schermo, come le copertine dell'app TV, con
/// titolo, descrizione e i pulsanti di sistema; sotto, una fila di schede (sorgenti, giochi,
/// impostazioni) che con il fuoco si sollevano.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Namespace private var namespace

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            HomeBackdrop(photos: Array(model.photos.prefix(12)))
                .ignoresSafeArea()
            // Scuro in basso e a sinistra, dove ci sono testi e schede.
            LinearGradient(colors: [.black.opacity(0.85), .black.opacity(0.15)], startPoint: .leading, endPoint: .trailing)
                .ignoresSafeArea()
            LinearGradient(colors: [.clear, .black.opacity(0.92)], startPoint: .center, endPoint: .bottom)
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Spacer()
                    if model.guests.active {
                        Label(LP("home.party_chip", model.approvedGuestCount), systemImage: "person.2.fill")
                            .font(.callout.weight(.semibold))
                            .padding(.horizontal, 24)
                            .padding(.vertical, 12)
                            .background(.regularMaterial, in: Capsule())
                    }
                    ClockText()
                        .padding(.horizontal, 24)
                        .padding(.vertical, 10)
                        .background(.regularMaterial, in: Capsule())
                }
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 14) {
                    Text(L("home.collection_kicker"))
                        .font(.headline)
                        .foregroundStyle(Theme.secondaryText)
                    Text(model.collectionTitle)
                        .font(.system(size: 80, weight: .bold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.7)
                    Text(collectionDetail)
                        .font(.title3)
                        .foregroundStyle(Theme.secondaryText)
                        .lineLimit(1)
                }
                .frame(maxWidth: 1100, alignment: .leading)
                HStack(spacing: 32) {
                    Button {
                        model.play()
                    } label: {
                        Label(L("action.start_slideshow"), systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .prefersDefaultFocus(true, in: namespace)
                    Button {
                        if model.guests.active { model.openGuests() } else { model.startPartyAndPlay() }
                    } label: {
                        Label(model.guests.active ? L("home.party_running") : L("home.start_party"), systemImage: "person.2.fill")
                    }
                }
                .padding(.top, 36)

                Text(L("home.choose_photos"))
                    .font(.headline)
                    .foregroundStyle(Theme.secondaryText)
                    .padding(.top, 56)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 48) {
                        ShelfCard(
                            title: L("home.tile_guests_title"),
                            subtitle: L("home.tile_guests_body"),
                            systemImage: "person.2.fill",
                            artwork: "demo_birthday.jpg",
                            badge: inUse(.guests)
                        ) { model.openGuests() }
                        // Senza client OAuth nella build la scheda non compare: l'app resta usabile con le foto demo.
                        if model.isGoogleConfigured {
                            ShelfCard(
                                title: L("home.google_title"),
                                subtitle: L("home.tile_google_body"),
                                systemImage: "photo.on.rectangle.angled",
                                artwork: "demo_mountains.jpg",
                                badge: inUse(.google)
                            ) { model.startGoogleConnect() }
                        }
                        ShelfCard(
                            title: L("collection.demo"),
                            subtitle: L("home.tile_demo_body"),
                            systemImage: "sparkles",
                            artwork: "demo_picnic.jpg",
                            badge: inUse(.demo)
                        ) { model.startDemo() }
                        NavigationLink(value: Route.games) {
                            ShelfCardLabel(title: L("home.tile_games"), subtitle: L("games.title"), systemImage: "gamecontroller.fill")
                        }
                        .buttonStyle(.card)
                        NavigationLink(value: Route.settings) {
                            ShelfCardLabel(title: L("action.settings"), subtitle: L("home.tile_settings_body"), systemImage: "gearshape.fill")
                        }
                        .buttonStyle(.card)
                    }
                    // Spazio per il sollevamento delle schede con il fuoco.
                    .padding(.vertical, 40)
                }
                .scrollClipDisabled()
            }
        }
        .focusScope(namespace)
    }

    private func inUse(_ source: CollectionSource) -> String? {
        model.collectionSource == source ? L("home.in_use") : nil
    }

    /// "12 foto · Google Foto · 5 dagli invitati": quante foto, da dove, e quelle degli invitati in più.
    private var collectionDetail: String {
        var parts = [LP("home.collection_count", model.collectionCount)]
        switch model.collectionSource {
        case .google:
            parts.append(L("home.google_title"))
            if model.approvedGuestCount > 0 { parts.append(LP("home.collection_guests", model.approvedGuestCount)) }
        case .demo: parts.append(L("home.tile_demo_body"))
        case .guests: parts.append(L("home.tile_guests_body"))
        }
        return parts.joined(separator: " · ")
    }
}

/// Sfondo della home: le foto della raccolta in uso, una alla volta, con una dissolvenza lenta.
private struct HomeBackdrop: View {
    let photos: [Photo]
    @State private var index = 0

    var body: some View {
        ZStack {
            Color.black
            if photos.indices.contains(index) {
                PhotoImage(photo: photos[index], fitMode: .crop, maxPixelSize: 1920, placeholder: false)
                    .id(photos[index].id)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 1.5), value: index)
        .accessibilityHidden(true)
        .task(id: photos.map(\.id)) {
            index = 0
            guard photos.count > 1 else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 9_000_000_000)
                guard !Task.isCancelled else { return }
                index = (index + 1) % photos.count
            }
        }
    }
}
