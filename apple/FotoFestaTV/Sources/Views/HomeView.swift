import FotoFestaCore
import SwiftUI

/// Home: la raccolta in uso occupa lo schermo, con "Avvia slideshow" già a fuoco e "Avvia la festa"
/// accanto; sotto, la riga delle sorgenti (quella in uso segnata "In uso"), Giochi e Impostazioni.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Namespace private var namespace

    var body: some View {
        ZStack(alignment: .topLeading) {
            Theme.background
            HomeBackdrop(photos: Array(model.photos.prefix(12)))
            // Scuro a sinistra, dove ci sono i testi, e in basso, sotto le schede.
            LinearGradient(
                stops: [
                    .init(color: Theme.background.opacity(0.97), location: 0),
                    .init(color: Theme.background.opacity(0.86), location: 0.34),
                    .init(color: Theme.background.opacity(0.25), location: 0.66),
                    .init(color: Theme.background.opacity(0.1), location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            LinearGradient(
                stops: [
                    .init(color: Theme.background.opacity(0), location: 0.45),
                    .init(color: Theme.background.opacity(0.6), location: 0.7),
                    .init(color: Theme.background.opacity(0.98), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )

            HStack(spacing: 28) {
                BrandMark()
                Spacer()
                if model.guests.active {
                    PartyChip(text: LP("home.party_chip", model.approvedGuestCount))
                }
                ClockText()
            }
            .padding(.horizontal, 96)
            .padding(.top, 54)

            VStack(alignment: .leading, spacing: 24) {
                Kicker(text: L("home.collection_kicker"))
                Text(model.collectionTitle)
                    .font(.figtree(92, .extraBold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                Text(collectionDetail)
                    .font(.figtree(34))
                    .foregroundStyle(Theme.secondaryText)
                    .lineLimit(1)
                HStack(spacing: 28) {
                    Button {
                        model.play()
                    } label: {
                        Label(L("action.start_slideshow"), systemImage: "play.fill")
                    }
                    .buttonStyle(PillButtonStyle())
                    .prefersDefaultFocus(true, in: namespace)
                    Button {
                        if model.guests.active { model.openGuests() } else { model.startPartyAndPlay() }
                    } label: {
                        Label(model.guests.active ? L("home.party_running") : L("home.start_party"), systemImage: "person.2.fill")
                    }
                    .buttonStyle(PillButtonStyle())
                }
                .padding(.top, 20)
            }
            .frame(width: 1000, alignment: .leading)
            .padding(.leading, 96)
            .padding(.top, 208)

            VStack(alignment: .leading, spacing: 24) {
                Spacer()
                Text(L("home.choose_photos")).font(.figtree(30, .bold)).foregroundStyle(Theme.secondaryText)
                HStack(spacing: 28) {
                    Button {
                        model.openGuests()
                    } label: {
                        SourceTileLabel(
                            title: L("home.tile_guests_title"),
                            subtitle: L("home.tile_guests_body"),
                            systemImage: "person.2.fill",
                            badge: inUse(.guests)
                        )
                    }
                    .buttonStyle(TileButtonStyle())
                    // Senza client OAuth nella build la scheda non compare: l'app resta usabile con le foto demo.
                    if model.isGoogleConfigured {
                        Button {
                            model.startGoogleConnect()
                        } label: {
                            SourceTileLabel(
                                title: L("home.google_title"),
                                subtitle: L("home.tile_google_body"),
                                systemImage: "cloud.fill",
                                badge: inUse(.google)
                            )
                        }
                        .buttonStyle(TileButtonStyle())
                    }
                    Button {
                        model.startDemo()
                    } label: {
                        SourceTileLabel(
                            title: L("collection.demo"),
                            subtitle: L("home.tile_demo_body"),
                            systemImage: "photo",
                            badge: inUse(.demo)
                        )
                    }
                    .buttonStyle(TileButtonStyle())
                    NavigationLink(value: Route.games) {
                        SmallTileLabel(title: L("home.tile_games"), systemImage: "gamecontroller.fill")
                    }
                    .buttonStyle(TileButtonStyle())
                    .frame(width: 184)
                    NavigationLink(value: Route.settings) {
                        SmallTileLabel(title: L("action.settings"), systemImage: "gearshape.fill")
                    }
                    .buttonStyle(TileButtonStyle())
                    .frame(width: 184)
                }
                .frame(height: 224)
            }
            .padding(.horizontal, 96)
            .padding(.bottom, 96)
        }
        .ignoresSafeArea()
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

/// Scheda stretta con icona e nome, per Giochi e Impostazioni.
private struct SmallTileLabel: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage).font(.system(size: 46, weight: .semibold))
            Text(title).font(.figtree(28, .bold)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Sfondo della home: le foto della raccolta in uso, una alla volta, con una dissolvenza lenta.
private struct HomeBackdrop: View {
    let photos: [Photo]
    @State private var index = 0

    var body: some View {
        ZStack {
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
