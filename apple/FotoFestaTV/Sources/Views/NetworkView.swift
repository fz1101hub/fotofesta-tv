import FotoFestaCore
import SwiftUI

/// Cartelle di rete: server multimediali (DLNA) della rete di casa, trovati da soli o aggiunti con
/// l'indirizzo, e le loro cartelle. "Usa questa cartella" ne fa la raccolta dello slideshow.
struct NetworkView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 40), count: 3)

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 36) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("network.kicker")).font(.headline).foregroundStyle(Theme.secondaryText)
                    Text(L("network.title")).font(.largeTitle.bold())
                    Text(L("network.subtitle"))
                        .foregroundStyle(Theme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let browse = model.networkBrowse {
                    folder(browse)
                } else {
                    servers
                }
            }
            .padding(80)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        // Il tasto Menu risale le cartelle prima di uscire.
        .onExitCommand {
            if model.networkBrowse != nil { model.networkUp() } else { dismiss() }
        }
    }

    private var servers: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                if let saved = model.networkSaved {
                    Button {
                        model.openSavedNetworkFolder()
                    } label: {
                        Label(L("network.in_use", saved.title), systemImage: "checkmark.circle.fill")
                            .frame(maxWidth: 1000, alignment: .leading)
                    }
                }
                Text(L("network.servers")).font(.headline).foregroundStyle(Theme.secondaryText)
                ForEach(model.networkServers, id: \.udn) { server in
                    Button {
                        model.openNetworkServer(server)
                    } label: {
                        Label(server.name, systemImage: "server.rack")
                            .frame(maxWidth: 1000, alignment: .leading)
                    }
                }
                if model.networkSearching {
                    HStack(spacing: 20) {
                        ProgressView()
                        Text(L("network.searching")).foregroundStyle(Theme.secondaryText)
                    }
                } else {
                    if model.networkServers.isEmpty {
                        Text(L("network.none"))
                            .foregroundStyle(Theme.secondaryText)
                            .frame(maxWidth: 1200, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button {
                        model.searchNetwork()
                    } label: {
                        Label(L("network.search_again"), systemImage: "arrow.clockwise")
                    }
                }
                Text(L("network.add_title")).font(.headline).foregroundStyle(Theme.secondaryText).padding(.top, 12)
                HStack(spacing: 32) {
                    TextField(L("network.address_placeholder"), text: $address)
                        #if os(tvOS)
                        .keyboardType(.numbersAndPunctuation)
                        .textContentType(.URL)
                        #endif
                        .autocorrectionDisabled()
                        .frame(width: 560)
                        .onSubmit { model.addNetworkAddress(address) }
                    Button {
                        model.addNetworkAddress(address)
                    } label: {
                        Label(L("network.add"), systemImage: "plus")
                    }
                    .disabled(model.networkLoading)
                    if model.networkLoading { ProgressView() }
                }
                Text(L("network.add_hint"))
                    .font(.callout)
                    .foregroundStyle(Theme.secondaryText)
                    .frame(maxWidth: 1200, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 20)
        }
        .scrollClipDisabled()
    }

    private func folder(_ browse: NetworkBrowse) -> some View {
        VStack(alignment: .leading, spacing: 28) {
            Text(browse.trail.map(\.title).joined(separator: " › "))
                .font(.title3)
                .foregroundStyle(Theme.secondaryText)
                .lineLimit(1)
                .truncationMode(.head)
            HStack(spacing: 32) {
                Button {
                    model.useNetworkFolder()
                } label: {
                    Label(L("network.use_folder"), systemImage: "checkmark.circle.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.networkLoading)
                Button {
                    model.networkUp()
                } label: {
                    Label(L("network.up"), systemImage: "arrow.up")
                }
                Text(LP("network.photos_here", browse.photosHere)).foregroundStyle(Theme.secondaryText)
                if model.networkLoading { ProgressView() }
            }
            if browse.folders.isEmpty {
                Text(L("network.no_subfolders")).foregroundStyle(Theme.secondaryText)
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 32) {
                        ForEach(browse.folders, id: \.id) { container in
                            Button {
                                model.openNetworkFolder(container)
                            } label: {
                                Label(container.title, systemImage: container.musicOrVideo ? "music.note.list" : "folder.fill")
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .disabled(model.networkLoading)
                        }
                    }
                    .padding(.vertical, 20)
                }
                .scrollClipDisabled()
            }
        }
    }
}
