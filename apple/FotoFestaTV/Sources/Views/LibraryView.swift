import FotoFestaCore
import SwiftUI

/// Foto di iCloud: gli album della libreria Foto della Apple TV (tutte le foto, preferiti, album e
/// album condivisi). Si sceglie un album e diventa la raccolta dello slideshow.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    private let columns = Array(repeating: GridItem(.fixed(400), spacing: 48), count: 4)

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("library.kicker")).font(.headline).foregroundStyle(Theme.secondaryText)
                    Text(L("library.title")).font(.largeTitle.bold())
                    Text(L("library.subtitle"))
                        .foregroundStyle(Theme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            .padding(80)
            if model.libraryLoading, !model.libraryAlbums.isEmpty {
                ProgressView()
                    .padding(40)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
        }
        // Tornando dalle Impostazioni il permesso può essere cambiato.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.refreshLibrary() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.libraryAccess {
        case .notDetermined:
            message(L("library.ask")) {
                Button(L("library.allow")) { model.requestLibraryAccess() }
            }
        case .denied:
            message(L("library.denied")) {
                Button(L("library.retry")) { model.refreshLibrary() }
            }
        case .authorized:
            if model.libraryAlbums.isEmpty {
                if model.libraryLoading {
                    ProgressView()
                } else {
                    message(L("library.none")) {
                        Button(L("library.retry")) { model.refreshLibrary() }
                    }
                }
            } else {
                albums
            }
        }
    }

    private func message<Actions: View>(_ text: String, @ViewBuilder actions: () -> Actions) -> some View {
        VStack(alignment: .leading, spacing: 32) {
            Text(text)
                .font(.title3)
                .frame(maxWidth: 1200, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            actions()
        }
        .padding(48)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
    }

    private var albums: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 48) {
                section(L("library.section_library"), kinds: [.all, .favorites])
                section(L("library.section_albums"), kinds: [.album])
                section(L("library.section_shared"), kinds: [.shared])
            }
            // Spazio per il sollevamento delle schede con il fuoco.
            .padding(.vertical, 30)
        }
        .scrollClipDisabled()
    }

    @ViewBuilder
    private func section(_ title: String, kinds: Set<LibraryAlbum.Kind>) -> some View {
        let items = model.libraryAlbums.filter { kinds.contains($0.kind) }
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 24) {
                Text(title).font(.headline).foregroundStyle(Theme.secondaryText)
                LazyVGrid(columns: columns, alignment: .leading, spacing: 48) {
                    ForEach(items) { album in
                        Button {
                            model.useLibraryAlbum(album)
                        } label: {
                            AlbumCard(album: album, inUse: model.libraryAlbumInUse == album.id)
                        }
                        .cardButtonStyle()
                        .disabled(model.libraryLoading)
                    }
                }
            }
        }
    }
}

/// Scheda di un album: copertina, nome e numero di elementi.
private struct AlbumCard: View {
    let album: LibraryAlbum
    let inUse: Bool

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if let cover = album.cover {
                PhotoImage(photo: cover, fitMode: .crop, maxPixelSize: 800, placeholder: false)
                    .accessibilityHidden(true)
            } else {
                LinearGradient(colors: [Color(white: 0.26), Color(white: 0.12)], startPoint: .topLeading, endPoint: .bottomTrailing)
            }
            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 4) {
                Text(album.title).font(.headline).lineLimit(1)
                Text(LP("library.count", album.count)).font(.caption).foregroundStyle(Theme.secondaryText)
            }
            .foregroundStyle(.white)
            .padding(24)
        }
        .frame(width: 400, height: 225)
        .clipped()
        .overlay(alignment: .topTrailing) {
            if inUse { Badge(text: L("home.in_use")).padding(18) }
        }
        .accessibilityElement(children: .combine)
    }
}
