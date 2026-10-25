import FotoFestaCore
import SwiftUI

/// Dispositivi della festa: luci che seguono le foto, stampante delle foto, stampante degli
/// scontrini e fotocamere Wi‑Fi. Tutto nella rete di casa.
struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var receiptHost = ""
    @State private var connecting: Task<Void, Never>?

    var body: some View {
        let devices = model.devices
        let settings = model.settings
        Form {
            Section {
                Toggle(isOn: binding(\.partyLights)) {
                    described(L("devices.lights_follow"), L("devices.lights_follow_body"))
                }
                ForEach(devices.config.lights) { light in
                    HStack {
                        Text(L("devices.light_connected", light.name))
                        Spacer()
                        Button(L("devices.test")) { devices.testLight(light) }
                        Button(L("devices.remove_named", light.name), role: .destructive) { devices.removeLight(light) }
                    }
                }
                if let pairing = devices.pairing {
                    HStack(spacing: 20) {
                        ProgressView()
                        described(L("devices.pairing", pairing.name), L("devices.pairing_body"))
                        Spacer()
                        Button(L("action.cancel")) {
                            connecting?.cancel()
                            devices.cancelPairing()
                        }
                    }
                }
                let saved = Set(devices.config.lights.map(\.host))
                ForEach(devices.foundLights.filter { !saved.contains($0.host) }) { light in
                    Button {
                        connect(light)
                    } label: {
                        described("\(L("devices.connect")) · \(light.name)", hint(light.kind))
                    }
                    .disabled(devices.pairing != nil)
                }
                if devices.foundLights.isEmpty, devices.searching {
                    Text(L("devices.lights_searching")).foregroundStyle(.secondary)
                }
            } header: {
                Text(L("devices.lights"))
            }

            Section {
                if let printer = devices.config.printer {
                    LabeledContent(L("devices.printer"), value: printer.name)
                    Button(L("devices.test")) { printTest() }
                    Toggle(isOn: binding(\.autoPrint)) {
                        described(L("devices.auto_print"), L("devices.auto_print_body"))
                    }
                    Button(L("devices.remove_named", printer.name), role: .destructive) {
                        Task { _ = await devices.usePrinter(nil) }
                    }
                }
                ForEach(devices.foundPrinters.filter { $0 != devices.config.printer }, id: \.self) { printer in
                    Button("\(L("devices.use")) · \(printer.name)") { use(printer) }
                }
                if devices.foundPrinters.isEmpty, devices.searching {
                    Text(L("devices.printer_searching")).foregroundStyle(.secondary)
                }
            } header: {
                Text(L("devices.printer"))
            } footer: {
                Text(L("devices.printer_test_body"))
            }

            Section {
                TextField(L("devices.receipt_address"), text: $receiptHost)
                    .onSubmit { devices.setReceiptHost(receiptHost) }
                Picker(L("devices.receipt_paper"), selection: Binding(get: { devices.config.paper }, set: { devices.setPaper($0) })) {
                    Text(verbatim: "58 mm").tag(EscPos.Paper.mm58)
                    Text(verbatim: "80 mm").tag(EscPos.Paper.mm80)
                }
                if !devices.config.receiptHost.isEmpty {
                    Button(L("devices.test")) { receiptTest() }
                    Toggle(isOn: binding(\.autoReceipt)) {
                        described(L("devices.auto_receipt"), L("devices.auto_receipt_body"))
                    }
                }
            } header: {
                Text(L("devices.receipt"))
            } footer: {
                Text(L("devices.receipt_hint", Int(EscPos.port)))
            }

            Section {
                Toggle(isOn: binding(\.ftpServer)) {
                    described(L("devices.ftp"), L("devices.ftp_body"))
                }
                if settings.ftpServer {
                    if let port = devices.ftpPort, let host = model.guests.url.flatMap({ URL(string: $0)?.host }) {
                        Text(L("devices.ftp_details", host, Int(port), devices.ftpUser, devices.ftpPassword))
                            .font(.callout.monospaced())
                    } else {
                        Text(L("devices.ftp_waiting")).foregroundStyle(.secondary)
                    }
                }
                #if os(macOS)
                CameraSection()
                #endif
            } header: {
                Text(L("devices.cameras"))
            }
        }
        .navigationTitle(L("devices.title"))
        .onAppear {
            receiptHost = devices.config.receiptHost
            devices.startSearch()
        }
        .onDisappear {
            devices.stopSearch()
            devices.setReceiptHost(receiptHost)
        }
    }

    private func hint(_ kind: PartyLights.Kind) -> String {
        switch kind {
        case .hue: L("devices.hue_hint")
        case .nanoleaf: L("devices.nanoleaf_hint")
        case .wled: L("devices.wled_hint")
        }
    }

    private func connect(_ light: PartyLights.Device) {
        connecting = Task {
            let done = await model.devices.connect(light)
            model.showMessage(done ? L("message.light_paired", light.name) : L("message.light_not_paired"))
        }
    }

    private func use(_ printer: Ipp.Printer) {
        Task {
            let ok = await model.devices.usePrinter(printer)
            model.showMessage(ok ? L("message.printer_ready", printer.name) : L("message.printer_unsupported", printer.name))
        }
    }

    private func printTest() {
        guard let photo = model.currentPhoto ?? model.stillPhotos.first else { return }
        Task {
            let ok = await model.devices.print(photo, automatic: false)
            model.showMessage(ok ? L("message.print_sent") : L("message.print_failed"))
        }
    }

    private func receiptTest() {
        model.devices.setReceiptHost(receiptHost)
        guard let photo = model.currentPhoto ?? model.stillPhotos.first else { return }
        let title = model.guests.setup.brand.name.isEmpty ? model.partyTitle : model.guests.setup.brand.name
        Task {
            let ok = await model.devices.receipt(photo, title: title, automatic: false)
            model.showMessage(ok ? L("message.print_sent") : L("message.print_failed"))
        }
    }

    private func described(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
            Text(body).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<SlideshowSettings, Value>) -> Binding<Value> {
        Binding(get: { model.settings[keyPath: keyPath] }, set: { value in model.updateSettings { $0[keyPath: keyPath] = value } })
    }
}
