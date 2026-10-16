import FotoFestaCore
import SwiftUI

/// Impostazioni in due colonne: a sinistra le sezioni (spostando il fuoco si apre la sezione), a
/// destra le voci con il valore scritto e gli interruttori per i sì e no. Le voci con "›" aprono
/// l'elenco dei valori; Menu lo richiude.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @FocusState private var focusedCategory: SettingsCategory?
    @FocusState private var focusedOption: Int?
    @State private var picker: Picker?

    private enum Picker { case interval, transition, fit, sleep }

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                BrandMark()
                Text(L("settings.title"))
                    .font(.figtree(56, .extraBold))
                    .foregroundStyle(Theme.text)
                    .padding(.top, 40)
                    .padding(.bottom, 24)
                ForEach(SettingsCategory.allCases, id: \.self) { category in
                    Button {
                        model.settingsCategory = category
                    } label: {
                        Label(name(of: category), systemImage: icon(of: category))
                    }
                    .buttonStyle(CategoryButtonStyle(selected: model.settingsCategory == category))
                    .focused($focusedCategory, equals: category)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, 96)
            .padding(.trailing, 40)
            .padding(.vertical, 54)
            .frame(width: 536)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Theme.surface)
            .focusSection()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Kicker(text: name(of: model.settingsCategory)).padding(.bottom, 8)
                    if let picker {
                        optionsPanel(picker)
                    } else {
                        items(for: model.settingsCategory)
                    }
                }
                .padding(.leading, 72)
                .padding(.trailing, 96)
                .padding(.top, 164)
                .padding(.bottom, 60)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .focusSection()
        }
        .background(Theme.background)
        .ignoresSafeArea()
        .onChange(of: focusedCategory) { _, category in
            guard let category else { return }
            model.settingsCategory = category
            picker = nil
        }
        // Con l'elenco dei valori aperto, Menu lo chiude invece di uscire dalle impostazioni.
        .onExitCommand(perform: picker == nil ? nil : { picker = nil })
    }

    // MARK: - Voci

    @ViewBuilder
    private func items(for category: SettingsCategory) -> some View {
        let settings = model.settings
        switch category {
        case .slideshow:
            row(L("settings.interval"), value: LP("settings.interval_value", settings.intervalSeconds)) { picker = .interval }
            row(L("settings.transition"), value: transitionName(settings.transition)) { picker = .transition }
            row(L("settings.fit"), subtitle: fitDescription(settings.fitMode), value: fitName(settings.fitMode)) { picker = .fit }
            toggle(L("settings.shuffle"), isOn: settings.shuffle) { model.updateSettings { $0.shuffle.toggle() } }
            // Con "Riduci movimento" (dell'app o di sistema) il movimento lento resta spento.
            toggle(L("settings.ken_burns"), subtitle: L("settings.ken_burns_body"), isOn: settings.isKenBurnsActive && !systemReduceMotion) {
                model.updateSettings { $0.kenBurns.toggle() }
            }
            .disabled(settings.reducedMotion || systemReduceMotion)
            toggle(L("settings.reduced_motion"), subtitle: L("settings.reduced_motion_body"), isOn: settings.reducedMotion) {
                model.updateSettings { $0.reducedMotion.toggle() }
            }
            toggle(L("settings.clock"), isOn: settings.showClock) { model.updateSettings { $0.showClock.toggle() } }
            row(L("settings.sleep_timer"), subtitle: L("settings.sleep_timer_body"), value: sleepName(settings.sleepTimerMinutes)) { picker = .sleep }
        case .party:
            let guests = model.guests
            toggle(L("guests.qr_in_slideshow"), subtitle: L("guests.qr_in_slideshow_body"), isOn: guests.qrInSlideshow) {
                guests.setQRInSlideshow(!guests.qrInSlideshow)
            }
            toggle(L("guests.approval"), subtitle: L("guests.approval_body"), isOn: guests.approval) {
                guests.setApproval(!guests.approval)
            }
            NavigationLink(value: Route.guestWifi) {
                SettingsRowLabel(title: L("guests.wifi"), subtitle: L("guests.wifi_body"), value: guests.wifi?.ssid ?? L("settings.wifi_none"))
            }
            .buttonStyle(RowButtonStyle())
            if guests.wifi != nil {
                row(L("settings.wifi_remove_item")) { guests.saveWifi(nil) }
            }
        case .privacy:
            row(L("settings.google_disconnect"), subtitle: L("settings.google_data_body")) { model.disconnectGoogle() }
            // Selezionabile per poterlo leggere tutto scorrendo con il telecomando.
            Button {} label: {
                SecondaryText(L("settings.privacy"), size: 28)
            }
            .buttonStyle(RowButtonStyle())
            if let url = model.privacyPolicyURL {
                // Su tvOS non si aprono link: l'informativa si legge sul telefono.
                Button {} label: {
                    HStack(spacing: 40) {
                        QRCodeView(text: url, accessibilityLabel: L("settings.privacy_policy"), size: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        VStack(alignment: .leading, spacing: 12) {
                            Text(L("settings.privacy_policy")).font(.figtree(31, .bold))
                            SecondaryText(L("settings.privacy_policy_hint"))
                            SecondaryText(url, size: 24)
                        }
                    }
                }
                .buttonStyle(RowButtonStyle())
            }
        }
    }

    private func row(_ title: String, subtitle: String? = nil, value: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRowLabel(title: title, subtitle: subtitle, value: value)
        }
        .buttonStyle(RowButtonStyle())
    }

    private func toggle(_ title: String, subtitle: String? = nil, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRowLabel(title: title, subtitle: subtitle, isOn: isOn)
        }
        .buttonStyle(RowButtonStyle())
    }

    // MARK: - Elenco dei valori

    @ViewBuilder
    private func optionsPanel(_ picker: Picker) -> some View {
        let settings = model.settings
        switch picker {
        case .interval:
            let options = Array(Set(SlideshowSettings.intervalOptions + [settings.intervalSeconds])).sorted()
            optionList(L("settings.interval"), options.map { LP("settings.interval_value", $0) }, selected: options.firstIndex(of: settings.intervalSeconds)) { index in
                model.updateSettings { $0.intervalSeconds = options[index] }
            }
        case .transition:
            let options = TransitionStyle.allCases
            optionList(L("settings.transition"), options.map(transitionName), selected: options.firstIndex(of: settings.transition)) { index in
                model.updateSettings { $0.transition = options[index] }
            }
        case .fit:
            let options = FitMode.allCases
            optionList(L("settings.fit"), options.map(fitName), descriptions: options.map(fitDescription), selected: options.firstIndex(of: settings.fitMode)) { index in
                model.updateSettings { $0.fitMode = options[index] }
            }
        case .sleep:
            let options = [0] + Array(Set(SlideshowSettings.sleepTimerOptions + (settings.sleepTimerMinutes > 0 ? [settings.sleepTimerMinutes] : []))).sorted()
            optionList(L("settings.sleep_timer"), options.map(sleepName), selected: options.firstIndex(of: settings.sleepTimerMinutes)) { index in
                model.updateSettings { $0.sleepTimerMinutes = options[index] }
            }
        }
    }

    private func optionList(
        _ title: String,
        _ labels: [String],
        descriptions: [String]? = nil,
        selected: Int?,
        onSelect: @escaping (Int) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.figtree(48, .extraBold)).foregroundStyle(Theme.text).padding(.bottom, 8)
            ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
                Button {
                    onSelect(index)
                    self.picker = nil
                } label: {
                    OptionRowLabel(title: label, description: descriptions?[index], chosen: index == selected)
                }
                .buttonStyle(RowButtonStyle())
                .focused($focusedOption, equals: index)
            }
        }
        .defaultFocus($focusedOption, selected ?? 0)
    }

    // MARK: - Nomi

    private func name(of category: SettingsCategory) -> String {
        switch category {
        case .slideshow: return L("settings.cat_slideshow")
        case .party: return L("settings.cat_party")
        case .privacy: return L("settings.cat_privacy")
        }
    }

    private func icon(of category: SettingsCategory) -> String {
        switch category {
        case .slideshow: return "play.rectangle.fill"
        case .party: return "person.2.fill"
        case .privacy: return "lock.shield.fill"
        }
    }

    private func transitionName(_ style: TransitionStyle) -> String {
        switch style {
        case .fade: return L("transition.fade")
        case .slide: return L("transition.slide")
        case .zoom: return L("transition.zoom")
        }
    }

    private func fitName(_ mode: FitMode) -> String {
        switch mode {
        case .blur: return L("fit.blur")
        case .contain: return L("fit.contain")
        case .crop: return L("fit.crop")
        }
    }

    private func fitDescription(_ mode: FitMode) -> String {
        switch mode {
        case .blur: return L("fit.blur_body")
        case .contain: return L("fit.contain_body")
        case .crop: return L("fit.crop_body")
        }
    }

    private func sleepName(_ minutes: Int) -> String {
        minutes == 0 ? L("settings.off") : L("settings.minutes", minutes)
    }
}

/// Valore in un elenco di opzioni: quello in uso ha il segno di spunta.
private struct OptionRowLabel: View {
    @Environment(\.isFocused) private var focused
    let title: String
    let description: String?
    let chosen: Bool

    var body: some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.figtree(31, .bold))
                if let description { SecondaryText(description) }
            }
            Spacer(minLength: 0)
            if chosen {
                Image(systemName: "checkmark")
                    .font(.system(size: 34, weight: .bold))
                    .foregroundStyle(focused ? Theme.onFocus : Theme.accent)
                    .accessibilityLabel(L("settings.selected"))
            }
        }
    }
}
