import FotoFestaCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @FocusState private var policyFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    var body: some View {
        let settings = model.settings
        Form {
            Section(L("settings.slideshow")) {
                Picker(L("settings.interval"), selection: binding(\.intervalSeconds)) {
                    ForEach(intervalOptions(including: settings.intervalSeconds), id: \.self) { seconds in
                        Text(L("settings.seconds", seconds)).tag(seconds)
                    }
                }
                Picker(L("settings.transition"), selection: binding(\.transition)) {
                    Text(L("transition.fade")).tag(TransitionStyle.fade)
                    Text(L("transition.slide")).tag(TransitionStyle.slide)
                    Text(L("transition.zoom")).tag(TransitionStyle.zoom)
                }
                Picker(L("settings.fit"), selection: binding(\.fitMode)) {
                    Text(L("fit.contain")).tag(FitMode.contain)
                    Text(L("fit.crop")).tag(FitMode.crop)
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
                Button(L("settings.google_disconnect"), role: .destructive) {
                    model.disconnectGoogle()
                }
            } header: {
                Text(L("settings.google_data"))
            } footer: {
                Text(L("settings.privacy"))
            }

            if let url = model.privacyPolicyURL {
                Section(L("settings.privacy_policy")) {
                    // Su tvOS non si aprono link: l'informativa si legge sul telefono. La riga è
                    // focalizzabile perché il Form scorra fin qui con il telecomando.
                    HStack(spacing: 40) {
                        QRCodeView(text: url, accessibilityLabel: L("settings.privacy_policy"), size: 260)
                        VStack(alignment: .leading, spacing: 12) {
                            Text(L("settings.privacy_policy_hint"))
                            Text(url).font(.callout).foregroundStyle(Theme.secondaryText)
                        }
                    }
                    .padding(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(policyFocused ? Theme.amber : .clear, lineWidth: 4))
                    .focusable()
                    .focused($policyFocused)
                }
            }
        }
        .navigationTitle(L("settings.title"))
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<SlideshowSettings, Value>) -> Binding<Value> {
        Binding(
            get: { model.settings[keyPath: keyPath] },
            set: { newValue in model.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }

    /// Include anche un valore impostato altrove (es. da una versione precedente) per non perderlo.
    private func intervalOptions(including value: Int) -> [Int] {
        Array(Set(SlideshowSettings.intervalOptions + [value])).sorted()
    }

    private func sleepOptions(including value: Int) -> [Int] {
        Array(Set(SlideshowSettings.sleepTimerOptions + (value > 0 ? [value] : []))).sorted()
    }
}
