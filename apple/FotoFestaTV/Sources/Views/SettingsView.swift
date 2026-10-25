import FotoFestaCore
import SwiftUI
import UniformTypeIdentifiers

/// Impostazioni nello stile dell'app Impostazioni di tvOS: a sinistra l'icona dell'app (AppLogo, una
/// copia piatta dell'icona a livelli), a destra un elenco nativo diviso in sezioni.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @FocusState private var policyFocused: Bool
    #if os(macOS)
    @State private var exporting = false
    @State private var exportDocument = ConfigDocument(data: Data())
    @State private var importing = false
    #endif

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 32) {
                Image("AppLogo")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 600)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
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
                    Text(L("transition.zapping")).tag(TransitionStyle.zapping)
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
                Picker(selection: binding(\.layout)) {
                    Text(L("layout.single")).tag(SlideshowLayout.single)
                    Text(L("layout.mosaic")).tag(SlideshowLayout.mosaic)
                    Text(L("layout.stack")).tag(SlideshowLayout.stack)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.layout"))
                        Text(settings.layout == .mosaic ? L("layout.mosaic_body") : settings.layout == .stack ? L("layout.stack_body") : L("layout.single_body"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Toggle(L("settings.shuffle"), isOn: binding(\.shuffle))
                Toggle(isOn: binding(\.videos)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.videos"))
                        Text(L("settings.videos_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                if settings.videos {
                    Toggle(isOn: binding(\.videoSound)) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(L("settings.video_sound"))
                            Text(L("settings.video_sound_body")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                // Con "Riduci movimento" (dell'app o di sistema) il movimento lento resta spento.
                Toggle(L("settings.ken_burns"), isOn: binding(\.kenBurns))
                    .disabled(settings.reducedMotion || systemReduceMotion)
                Toggle(L("settings.reduced_motion"), isOn: binding(\.reducedMotion))
                Toggle(L("settings.clock"), isOn: binding(\.showClock))
                Picker(L("settings.filter"), selection: binding(\.filter)) {
                    ForEach(PhotoFilter.allCases, id: \.self) { filter in
                        Text(filterName(filter)).tag(filter)
                    }
                }
                Toggle(isOn: binding(\.panoramas)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.panoramas"))
                        Text(L("settings.panoramas_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: binding(\.rivalParty)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.rival_party"))
                        Text(L("settings.rival_party_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: binding(\.boredScreen)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.bored"))
                        Text(L("settings.bored_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: binding(\.confetti)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.confetti"))
                        Text(L("settings.confetti_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: binding(\.newsMode)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.news"))
                        Text(L("settings.news_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: binding(\.openingTitles)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.opening"))
                        Text(L("settings.opening_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: binding(\.portrait)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.portrait"))
                        Text(L("settings.portrait_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Picker(L("settings.sleep_timer"), selection: binding(\.sleepTimerMinutes)) {
                    Text(L("settings.off")).tag(0)
                    ForEach(sleepOptions(including: settings.sleepTimerMinutes), id: \.self) { minutes in
                        Text(L("settings.minutes", minutes)).tag(minutes)
                    }
                }
                Toggle(isOn: binding(\.countdown)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.countdown"))
                        Text(L("settings.countdown_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                if settings.countdown {
                    Picker(L("settings.countdown_time"), selection: binding(\.countdownMinutes)) {
                        ForEach(Countdown.timeOptions, id: \.self) { minutes in
                            Text(timeLabel(minutes)).tag(minutes)
                        }
                    }
                    Picker(L("settings.countdown_event"), selection: binding(\.countdownEvent)) {
                        ForEach(CountdownEvent.allCases, id: \.self) { event in
                            Text(countdownName(event)).tag(event)
                        }
                    }
                }
            }

            Section {
                Picker(selection: Binding(get: { settings.template }, set: { template in model.applyTemplate(template) })) {
                    ForEach(PartyTemplate.allCases, id: \.self) { template in
                        Text(templateName(template)).tag(template)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.template"))
                        Text(L("settings.template_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(L("guests.qr_in_slideshow"), isOn: Binding(get: { guests.qrInSlideshow }, set: { guests.setQRInSlideshow($0) }))
                Toggle(L("guests.approval"), isOn: Binding(get: { guests.approval }, set: { guests.setApproval($0) }))
                Toggle(isOn: Binding(get: { guests.reactionsEnabled }, set: { guests.setReactionsEnabled($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.phone_reactions"))
                        Text(L("settings.phone_reactions_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.hostEnabled }, set: { guests.setHostEnabled($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.phone_host"))
                        Text(L("settings.phone_host_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.messagesEnabled }, set: { guests.setMessagesEnabled($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.phone_messages"))
                        Text(L("settings.phone_messages_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.songsEnabled }, set: { guests.setSongsEnabled($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.song_requests"))
                        Text(L("settings.song_requests_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.albumEnabled }, set: { guests.setAlbumEnabled($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.guest_album"))
                        Text(L("settings.guest_album_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.videosEnabled }, set: { guests.setVideosEnabled($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.guest_videos"))
                        Text(L("settings.guest_videos_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Picker(selection: Binding(get: { guests.photoLimit }, set: { guests.setPhotoLimit($0) })) {
                    ForEach(PhotoLimit.allCases, id: \.self) { limit in
                        Text(limit.label).tag(limit)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.photo_limit"))
                        Text(L("settings.photo_limit_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.agingPhotos }, set: { guests.setAgingPhotos($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.aging"))
                        Text(L("settings.aging_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.noTraces }, set: { guests.setNoTraces($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.no_traces"))
                        Text(L("settings.no_traces_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: Binding(get: { guests.resumeParty }, set: { guests.setResumeParty($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.resume"))
                        Text(L("settings.resume_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                #if os(macOS)
                Toggle(isOn: Binding(get: { guests.backupCopy }, set: { guests.setBackupCopy($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("settings.backup"))
                        Text(L("settings.backup_body")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                #endif
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

            venueSection(settings)

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

    /// Evento e uso fisso in un locale: cornice, sponsor, margini, voce, chiosco, festa sempre attiva,
    /// azzeramento notturno e (sul Mac) configurazione da passare a un altro schermo.
    @ViewBuilder
    private func venueSection(_ settings: SlideshowSettings) -> some View {
        Section(L("settings.cat_venue")) {
            NavigationLink(value: Route.devices) {
                described(L("devices.title"), L("settings.devices_body"))
            }
            Toggle(isOn: binding(\.livePhotos)) {
                described(L("settings.live_photos"), L("settings.live_photos_body"))
            }
            Toggle(isOn: binding(\.hdrPhotos)) {
                described(L("settings.hdr"), L("settings.hdr_body"))
            }
            Picker(selection: binding(\.frameStyle)) {
                Text(L("settings.frame_none")).tag(FrameStyle.none)
                Text(L("settings.frame_band")).tag(FrameStyle.band)
                Text(L("settings.frame_border")).tag(FrameStyle.border)
            } label: {
                described(L("settings.frame"), L("settings.frame_body"))
            }
            Picker(selection: binding(\.sponsorEvery)) {
                ForEach(SlideshowSettings.sponsorOptions, id: \.self) { every in
                    Text(every == 0 ? L("settings.off") : LP("settings.sponsors_every", every)).tag(every)
                }
            } label: {
                described(L("settings.sponsors"), L("settings.sponsors_body"))
            }
            Picker(selection: binding(\.screenMargin)) {
                ForEach(SlideshowSettings.marginOptions, id: \.self) { margin in
                    Text(margin == 0 ? L("settings.off") : "\(margin)%").tag(margin)
                }
            } label: {
                described(L("settings.margin"), L("settings.margin_body"))
            }
            Toggle(isOn: binding(\.speakAnnouncements)) {
                described(L("settings.speak"), L("settings.speak_body"))
            }
            Toggle(isOn: Binding(get: { settings.kiosk && model.kioskLock.hasPin }, set: { _ in model.toggleKiosk() })) {
                described(L("settings.kiosk"), L("settings.kiosk_body"))
            }
            Toggle(isOn: binding(\.alwaysParty)) {
                described(L("settings.always_party"), L("settings.always_party_body"))
            }
            Picker(selection: binding(\.nightlyReset)) {
                ForEach(SlideshowSettings.resetOptions, id: \.self) { hour in
                    Text(hour < 0 ? L("settings.off") : String(format: "%02d:00", hour)).tag(hour)
                }
            } label: {
                described(L("settings.nightly_reset"), L("settings.nightly_reset_body"))
            }
            #if os(macOS)
            Button {
                exportDocument = ConfigDocument(data: model.exportConfig() ?? Data())
                exporting = true
            } label: {
                described(L("settings.export"), L("settings.export_body"))
            }
            Button { importing = true } label: {
                described(L("settings.import"), L("settings.import_body"))
            }
            #endif
        }
        #if os(macOS)
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .json, defaultFilename: VenueConfig.fileName) { result in
            if case .failure = result { model.showMessage(L("message.export_failed")) } else { model.showMessage(L("message.export_done")) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            guard case let .success(url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) { _ = model.importConfig(data) } else { model.showMessage(L("message.import_failed")) }
        }
        #endif
    }

    private func described(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
            Text(body).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<SlideshowSettings, Value>) -> Binding<Value> {
        Binding(
            get: { model.settings[keyPath: keyPath] },
            set: { newValue in model.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }

    private func filterName(_ filter: PhotoFilter) -> String {
        switch filter {
        case .none: L("filter.none")
        case .blackWhite: L("filter.bw")
        case .sepia: L("filter.sepia")
        case .warm: L("filter.warm")
        case .cool: L("filter.cool")
        case .vintage: L("filter.vintage")
        }
    }

    private func templateName(_ template: PartyTemplate) -> String {
        switch template {
        case .none: L("template.none")
        case .birthday: L("template.birthday")
        case .wedding: L("template.wedding")
        case .newYear: L("template.new_year")
        case .graduation: L("template.graduation")
        case .kids: L("template.kids")
        case .reunion: L("template.reunion")
        }
    }

    private func fitDescription(_ mode: FitMode) -> String {
        switch mode {
        case .blur: return L("fit.blur_body")
        case .contain: return L("fit.contain_body")
        case .crop: return L("fit.crop_body")
        }
    }

    /// Orario del conto alla rovescia nel formato del dispositivo (21:30 o 9:30 PM).
    private func timeLabel(_ minutesOfDay: Int) -> String {
        var components = DateComponents()
        components.hour = minutesOfDay / 60
        components.minute = minutesOfDay % 60
        guard let date = Calendar.current.date(from: components) else { return "" }
        return date.formatted(date: .omitted, time: .shortened)
    }

    /// Include anche un valore impostato altrove (es. da una versione precedente) per non perderlo.
    private func intervalOptions(including value: Int) -> [Int] {
        Array(Set(SlideshowSettings.intervalOptions + [value])).sorted()
    }

    private func sleepOptions(including value: Int) -> [Int] {
        Array(Set(SlideshowSettings.sleepTimerOptions + (value > 0 ? [value] : []))).sorted()
    }
}
