import FotoFestaCore
import SwiftUI
import UIKit

/// Festa con le foto degli invitati. Prima di iniziare: come funziona, "Avvia la festa" e le due
/// impostazioni principali. Durante la festa: QR grande a sinistra, contatori, ultime foto e comandi.
struct GuestsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingEnd = false
    @Namespace private var namespace

    var body: some View {
        let guests = model.guests
        ZStack(alignment: .topLeading) {
            Theme.background
            if guests.active {
                inProgress(guests)
            } else {
                intro(guests)
            }
        }
        .ignoresSafeArea()
        .focusScope(namespace)
        .confirmationDialog(L("guests.end_confirm_title"), isPresented: $confirmingEnd, titleVisibility: .visible) {
            Button(L("guests.end"), role: .destructive) { model.endGuestParty() }
            Button(L("action.cancel"), role: .cancel) {}
        } message: {
            Text(L("guests.end_confirm_body", model.guests.photos.count))
        }
    }

    private func intro(_ guests: GuestParty) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            BrandMark()
            HStack(alignment: .top, spacing: 80) {
                VStack(alignment: .leading, spacing: 28) {
                    SectionTitle(kicker: L("guests.kicker"), title: L("guests.title"), large: true)
                    VStack(alignment: .leading, spacing: 20) {
                        StepRow(number: 1, text: L("guests.step_1"))
                        StepRow(number: 2, text: L("guests.step_2"))
                        StepRow(number: 3, text: L("guests.step_3"))
                    }
                    .padding(.vertical, 16)
                    Button {
                        model.startGuestParty()
                    } label: {
                        Label(L("home.start_party"), systemImage: "person.2.fill")
                    }
                    .buttonStyle(PillButtonStyle())
                    .prefersDefaultFocus(true, in: namespace)
                    Text(L("guests.same_network")).font(.figtree(30)).foregroundStyle(Theme.secondaryText)
                    // AirPlay lo gestisce tvOS sopra l'app: le foto trasmesse non entrano nella festa.
                    Label(L("guests.airplay_tip"), systemImage: "airplayvideo")
                        .font(.figtree(26))
                        .foregroundStyle(Theme.secondaryText)
                }
                VStack(alignment: .leading, spacing: 16) {
                    Button {
                        guests.setApproval(!guests.approval)
                    } label: {
                        SettingsRowLabel(title: L("guests.approval"), subtitle: L("guests.approval_body"), isOn: guests.approval)
                    }
                    .buttonStyle(RowButtonStyle())
                    NavigationLink(value: Route.guestWifi) {
                        SettingsRowLabel(title: L("guests.wifi"), subtitle: L("guests.wifi_body"), value: guests.wifi?.ssid ?? L("settings.wifi_none"))
                    }
                    .buttonStyle(RowButtonStyle())
                    Text(L("guests.intro")).font(.figtree(26)).foregroundStyle(Theme.secondaryText).padding(.top, 16)
                }
                .frame(width: 760)
                .padding(.top, 68)
            }
            .padding(.top, 72)
        }
        .padding(.horizontal, 96)
        .padding(.vertical, 54)
    }

    private func inProgress(_ guests: GuestParty) -> some View {
        HStack(spacing: 0) {
            SendPanel(guests: guests)
                .frame(width: 720)
                .frame(maxHeight: .infinity)
                .background(Theme.surface)
            ZStack(alignment: .topTrailing) {
                VStack(alignment: .leading, spacing: 28) {
                    Kicker(text: L("home.guests_title"))
                    Text(L("guests.party_started")).font(.figtree(76, .extraBold)).foregroundStyle(Theme.text)
                    HStack(spacing: 28) {
                        StatView(value: model.approvedGuestCount, label: LP("guests.stat_received", model.approvedGuestCount), highlight: false)
                        if guests.approval || !model.pendingGuestPhotos.isEmpty {
                            StatView(value: model.pendingGuestPhotos.count, label: LP("guests.stat_pending", model.pendingGuestPhotos.count), highlight: !model.pendingGuestPhotos.isEmpty)
                        }
                    }
                    if !recent.isEmpty {
                        HStack(spacing: 16) {
                            ForEach(recent) { photo in
                                PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 400, placeholder: false)
                                    .frame(width: 184, height: 124)
                                    .background(Theme.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                        }
                        .padding(.top, 12)
                    }
                    HStack(spacing: 24) {
                        Button {
                            model.play()
                        } label: {
                            Label(L("action.start_slideshow"), systemImage: "play.fill")
                        }
                        .buttonStyle(PillButtonStyle())
                        .prefersDefaultFocus(true, in: namespace)
                        if !model.pendingGuestPhotos.isEmpty {
                            NavigationLink(value: Route.guestReview) {
                                Text(LP("guests.approve_photos", model.pendingGuestPhotos.count))
                            }
                            .buttonStyle(PillButtonStyle())
                        }
                    }
                    .padding(.top, 16)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.leading, 88)
                .padding(.trailing, 96)
                .padding(.top, 120)
                Button(L("guests.end_short")) { confirmingEnd = true }
                    .buttonStyle(PillButtonStyle(kind: .ghost))
                    .padding(.horizontal, 96)
                    .padding(.top, 54)
            }
        }
    }

    /// Le ultime foto arrivate, dalla più recente.
    private var recent: [Photo] {
        Array(model.photos.filter { if case .guest = $0.source { return true } else { return false } }
            .sorted { ($0.receivedAt ?? .distantPast) > ($1.receivedAt ?? .distantPast) }
            .prefix(4))
    }
}

/// Passo numerato di come funziona la festa.
private struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(spacing: 24) {
            Text("\(number)")
                .font(.figtree(26, .extraBold))
                .foregroundStyle(Theme.text)
                .frame(width: 52, height: 52)
                .background(Circle().fill(Theme.surfaceRaised))
            Text(text).font(.figtree(34)).foregroundStyle(Theme.secondaryText)
        }
    }
}

/// Numero grande con la sua etichetta; `highlight` lo colora con l'accento (foto da approvare).
private struct StatView: View {
    let value: Int
    let label: String
    let highlight: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(value)").font(.figtree(60, .extraBold).monospacedDigit()).foregroundStyle(highlight ? Theme.accent : Theme.text)
            Text(label).font(.figtree(26)).foregroundStyle(Theme.secondaryText)
        }
        .padding(.horizontal, 36)
        .padding(.vertical, 24)
        .frame(minWidth: 300, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// QR per mandare le foto (con quello della Wi‑Fi, se impostata), oppure perché il dispositivo non è pronto.
private struct SendPanel: View {
    let guests: GuestParty

    var body: some View {
        VStack(spacing: 28) {
            if let url = guests.url {
                if let wifi = guests.wifi {
                    HStack(alignment: .top, spacing: 32) {
                        labeledQR(wifi.qrPayload, description: L("guests.qr_wifi"), label: L("guests.qr_step_wifi"))
                        labeledQR(url, description: L("guests.qr_send"), label: L("guests.qr_step_send"))
                    }
                } else {
                    QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 396)
                        .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                }
                Text(L("guests.send_title")).font(.figtree(36, .extraBold)).foregroundStyle(Theme.text).multilineTextAlignment(.center)
                Text(guests.wifi.map { L("guests.send_body_network", $0.ssid) } ?? L("guests.send_body"))
                    .font(.figtree(26))
                    .foregroundStyle(Theme.secondaryText)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 560)
            } else {
                GuestStatus(problem: guests.problem)
            }
        }
        .padding(48)
    }

    private func labeledQR(_ text: String, description: String, label: String) -> some View {
        VStack(spacing: 16) {
            QRCodeView(text: text, accessibilityLabel: description, size: 280)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            Text(label).font(.figtree(26, .bold)).foregroundStyle(Theme.text).multilineTextAlignment(.center)
        }
    }
}

/// Motivo per cui il dispositivo non può ancora ricevere foto, o l'avvio in corso.
private struct GuestStatus: View {
    let problem: GuestProblem?

    var body: some View {
        VStack(spacing: 24) {
            if let problem {
                Image(systemName: "wifi.exclamationmark").font(.system(size: 64)).foregroundStyle(Theme.error)
                Text(problem == .noNetwork ? L("guests.no_network") : L("guests.server_failed"))
                    .font(.figtree(32))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Theme.error)
            } else {
                ProgressView()
                Text(L("guests.starting")).font(.figtree(30)).foregroundStyle(Theme.secondaryText)
            }
        }
        .padding(32)
    }
}

/// Attesa della prima foto nello slideshow della festa: QR grande al centro.
struct WaitingForGuestsView: View {
    let guests: GuestParty

    var body: some View {
        VStack(spacing: 36) {
            Text(L("guests.waiting_title")).font(.figtree(76, .extraBold)).foregroundStyle(Theme.text)
            if let url = guests.url {
                QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 440)
                    .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                Text(L("guests.waiting_body")).font(.figtree(34)).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center)
            } else {
                GuestStatus(problem: guests.problem)
            }
        }
        .frame(maxWidth: 1280)
    }
}

/// QR piccolo nell'angolo dello slideshow, o l'avviso se la festa non riesce a partire.
struct SlideshowGuestQR: View {
    let guests: GuestParty

    var body: some View {
        HStack(spacing: 20) {
            if let url = guests.url {
                QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("guests.slideshow_cta")).font(.figtree(26, .bold)).foregroundStyle(Theme.text)
                    Text(L("slideshow.qr_body")).font(.figtree(23)).foregroundStyle(Theme.secondaryText)
                }
            } else if let problem = guests.problem {
                Text(problem == .noNetwork ? L("guests.no_network") : L("guests.server_failed"))
                    .font(.figtree(26))
                    .foregroundStyle(Theme.error)
                    .frame(maxWidth: 640, alignment: .leading)
            } else {
                ProgressView()
                Text(L("guests.starting")).font(.figtree(26)).foregroundStyle(Theme.secondaryText)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 28)
        .padding(.vertical, 16)
        .background(Theme.background.opacity(0.78), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Foto in attesa: si mostrano una alla volta, la più vecchia per prima.
struct GuestReviewView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let pending = model.pendingGuestPhotos
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 32) {
                SectionTitle(kicker: L("review.kicker"), title: L("review.title"), subtitle: L("guests.pending", pending.count))
                if let photo = pending.first {
                    HStack(alignment: .top, spacing: 48) {
                        PhotoImage(photo: photo, fitMode: .contain, maxPixelSize: 1920)
                            .background(Color.black)
                            .clipShape(RoundedRectangle(cornerRadius: 24))
                        VStack(alignment: .leading, spacing: 28) {
                            Text(photo.title).font(.figtree(34, .bold)).foregroundStyle(Theme.text).lineLimit(2)
                            Button {
                                model.approveGuestPhoto(photo)
                            } label: {
                                Label(L("review.show"), systemImage: "checkmark")
                            }
                            .buttonStyle(PillButtonStyle())
                            Button {
                                model.discardGuestPhoto(photo)
                            } label: {
                                Label(L("review.discard"), systemImage: "xmark")
                            }
                            .buttonStyle(PillButtonStyle())
                            if pending.count > 1 {
                                Button {
                                    model.guests.approveAll()
                                } label: {
                                    Label(L("review.approve_all"), systemImage: "checkmark.circle")
                                }
                                .buttonStyle(PillButtonStyle(kind: .ghost))
                            }
                        }
                        .frame(width: 520, alignment: .leading)
                    }
                } else {
                    Text(L("review.empty")).font(.figtree(34)).foregroundStyle(Theme.secondaryText)
                }
            }
            .padding(.horizontal, 96)
            .padding(.vertical, 60)
        }
    }
}

/// Nome e password della Wi‑Fi per il QR che fa entrare gli invitati nella rete di casa.
struct GuestWifiView: View {
    @Environment(AppModel.self) private var model
    @State private var ssid = ""
    @State private var password = ""
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                TextField(L("wifi.name"), text: $ssid)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                // La password resta visibile: il QR la mostra comunque a chi lo inquadra.
                TextField(L("wifi.password"), text: $password)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text(L("wifi.title"))
            } footer: {
                Text(L("wifi.subtitle"))
            }
            Section {
                Button(L("wifi.save")) {
                    model.saveGuestWifi(ssid: ssid, password: password)
                }
            }
        }
        .navigationTitle(L("wifi.title"))
        .onAppear {
            guard !loaded else { return }
            loaded = true
            ssid = model.guests.wifi?.ssid ?? ""
            password = model.guests.wifi?.password ?? ""
        }
    }
}
