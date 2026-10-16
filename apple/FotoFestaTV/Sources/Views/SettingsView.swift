import FotoFestaCore
import SwiftUI

/// Impostazioni nello stile dell'app Impostazioni di tvOS: a sinistra l'icona dell'app, a destra un
/// elenco nativo diviso in sezioni, con selettori, interruttori e collegamenti di sistema.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @FocusState private var policyFocused: Bool

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 32) {
                Image(systemName: "photo.on.rectangle.angled")
                    .font(.system(size: 220, weight: .regular))
                    .foregroundStyle(Theme.accent)
                Text("FotoFesta TV").font(.title2.bold())
                Text(L("settings.subtitle_local"))
                    .font(.callout)
                    .foregroundStyle(Theme.secondaryText)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 520)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityHidden(true)

            form
                .frame(maxWidth: .infinity)
        }
        .navigationTitle(L("settings.title"))
    }

    private var form: some View {
        let settings = model.settings
        let guests = model.guests
        return Form {
            Section(L("settings.cat_slideshow")) {
                Picker(L("settings.interval"), selection: binding(\.intervalSeconds)) {
                    ForEach(intervalOptions(including: settings.intervalSeconds), id: \.self) { seconds in
                        Text(LP("settings.interval_value", seconds)).tag(seconds)
                    }
                }
                Picker(L("settings.transition"), selection: binding(\.transition)) {
                    Text(L("transition.fade")).tag(TransitionStyle.fade)
                    Text(L("transition.slide")).tag(TransitionStyle.slide)
                    Text(L("transition.zoom")).tag(TransitionStyle.zoom)
                }
                Picker(selection: binding(\.fitMode)) {
                    Text(L("fit.blur")).tag(FitMode.blur)
                    Text(L("fit.contain")).tag(FitMode.contain)
                    Text(L("fit.crop")).tag(FitMode.crop)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.fit"))
                        Text(fitDescription(settings.fitMode)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(L("settings.shuffle"), isOn: binding(\.shuffle))
                // Con "Riduci movimento" (dell'app o di sistema) il movimento lento resta spento.
                Toggle(L("settings.ken_burns"), isOn: binding(\.kenBurns))
                    .disabled(settings.reducedMotion || systemReduceMotion)
                Toggle(L("settings.reduced_motion"), isOn: binding(\.reducedMotion))
                Toggle(L("settings.clock"), isOn: binding(\.showClock))
                Picker(L("settings.sleep_timer"), selection: binding(\.sleepTimerMinutes)) {
                    Text(L("settings.off")).tag(0)
                    ForEach(sleepOptions(including: settings.sleepTimerMinutes), id: \.self) { minutes in
                        Text(L("settings.minutes", minutes)).tag(minutes)
                    }
                }
            }

            Section {
                Toggle(L("guests.qr_in_slideshow"), isOn: Binding(get: { guests.qrInSlideshow }, set: { guests.setQRInSlideshow($0) }))
                Toggle(L("guests.approval"), isOn: Binding(get: { guests.approval }, set: { guests.setApproval($0) }))
                NavigationLink(value: Route.guestWifi) {
                    LabeledContent(L("guests.wifi"), value: guests.wifi?.ssid ?? L("settings.wifi_none"))
                }
                if guests.wifi != nil {
                    Button(L("settings.wifi_remove_item")) { guests.saveWifi(nil) }
                }
            } header: {
                Text(L("settings.cat_party"))
            } footer: {
                Text(L("guests.approval_body"))
            }

            Section {
                Button(L("settings.google_disconnect"), role: .destructive) {
                    model.disconnectGoogle()
                }
            } header: {
                Text(L("settings.cat_privacy"))
            } footer: {
                Text(L("settings.privacy"))
            }

            if let url = model.privacyPolicyURL {
                Section(L("settings.privacy_policy")) {
                    // Su tvOS non si aprono link: l'informativa si legge sul telefono. La riga è
                    // focalizzabile perché l'elenco scorra fin qui con il telecomando.
                    HStack(spacing: 40) {
                        QRCodeView(text: url, accessibilityLabel: L("settings.privacy_policy"), size: 240)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        VStack(alignment: .leading, spacing: 12) {
                            Text(L("settings.privacy_policy_hint"))
                            Text(url).font(.callout).foregroundStyle(.secondary)
                        }
                    }
                    .padding(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(policyFocused ? Color.white : .clear, lineWidth: 4))
                    .focusable()
                    .focused($policyFocused)
                }
            }
        }
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<SlideshowSettings, Value>) -> Binding<Value> {
        Binding(
            get: { model.settings[keyPath: keyPath] },
            set: { newValue in model.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }

    private func fitDescription(_ mode: FitMode) -> String {
        switch mode {
        case .blur: return L("fit.blur_body")
        case .contain: return L("fit.contain_body")
        case .crop: return L("fit.crop_body")
        }
    }

    /// Include anche un valore impostato altrove (es. da una versione precedente) per non perderlo.
    private func intervalOptions(including value: Int) -> [Int] {
        Array(Set(SlideshowSettings.intervalOptions + [value])).sorted()
    }

    private func sleepOptions(including value: Int) -> [Int] {
        Array(Set(SlideshowSettings.sleepTimerOptions + (value > 0 ? [value] : []))).sorted()
    }
}
