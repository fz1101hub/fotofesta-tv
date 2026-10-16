import FotoFestaCore
import SwiftUI
import UIKit

/// Festa con le foto degli invitati: QR da inquadrare, foto da approvare e impostazioni.
struct GuestsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingEnd = false

    var body: some View {
        let guests = model.guests
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 36) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("guests.kicker").uppercased()).font(.headline).foregroundStyle(Theme.coral)
                    Text(L("guests.title")).font(.largeTitle.bold())
                    Text(L("guests.subtitle")).foregroundStyle(Theme.secondaryText)
                }
                if guests.active {
                    activeContent(guests)
                } else {
                    VStack(alignment: .leading, spacing: 28) {
                        Text(L("guests.intro")).foregroundStyle(Theme.secondaryText)
                        Text(L("guests.same_network")).foregroundStyle(Theme.secondaryText)
                        Button {
                            model.startGuestParty()
                        } label: {
                            Label(L("guests.start"), systemImage: "person.3.fill")
                        }
                    }
                    .padding(48)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.navySoft, in: RoundedRectangle(cornerRadius: 32))
                }
                Spacer(minLength: 0)
            }
            .padding(80)
        }
        // Il QR resta visibile mentre gli invitati lo inquadrano.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = guests.active }
        .onChange(of: guests.active) { _, active in UIApplication.shared.isIdleTimerDisabled = active }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .confirmationDialog(L("guests.end_confirm_title"), isPresented: $confirmingEnd, titleVisibility: .visible) {
            Button(L("guests.end"), role: .destructive) { model.endGuestParty() }
            Button(L("action.cancel"), role: .cancel) {}
        } message: {
            Text(L("guests.end_confirm_body", model.guests.photos.count))
        }
    }

    private func activeContent(_ guests: GuestParty) -> some View {
        HStack(alignment: .top, spacing: 56) {
            HStack(alignment: .top, spacing: 32) {
                if let wifi = guests.wifi {
                    QRCard(text: wifi.qrPayload, label: L("guests.qr_wifi"), caption: L("guests.scan_wifi", wifi.ssid), size: 300)
                }
                if let url = guests.url {
                    QRCard(text: url, label: L("guests.qr_send"), caption: L("guests.scan_send"), detail: url, size: guests.wifi == nil ? 380 : 300)
                } else {
                    GuestStatus(problem: guests.problem)
                        .frame(width: 380, height: 380)
                        .background(Theme.navySoft, in: RoundedRectangle(cornerRadius: 24))
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    Text(L("guests.shown", model.approvedGuestCount)).font(.title2.bold())
                    Button {
                        model.play()
                    } label: {
                        Label(L("action.start_slideshow"), systemImage: "play.fill")
                    }
                    .prefersDefaultFocus(true, in: namespace)
                    if !model.pendingGuestPhotos.isEmpty {
                        NavigationLink(value: Route.guestReview) {
                            Label(L("guests.pending", model.pendingGuestPhotos.count), systemImage: "checkmark.circle")
                        }
                    }
                    Toggle(L("guests.approval"), isOn: Binding(get: { guests.approval }, set: { guests.setApproval($0) }))
                    Text(L("guests.approval_body")).font(.callout).foregroundStyle(Theme.secondaryText)
                    Toggle(L("guests.qr_in_slideshow"), isOn: Binding(get: { guests.qrInSlideshow }, set: { guests.setQRInSlideshow($0) }))
                    HStack(spacing: 24) {
                        NavigationLink(value: Route.guestWifi) {
                            Label(guests.wifi == nil ? L("guests.wifi_set") : L("guests.wifi_change"), systemImage: "wifi")
                        }
                        if guests.wifi != nil {
                            Button(L("guests.wifi_remove")) { guests.saveWifi(nil) }
                        }
                    }
                    Text(L("guests.wifi_body")).font(.callout).foregroundStyle(Theme.secondaryText)
                    Button(L("guests.end"), role: .destructive) { confirmingEnd = true }
                    Text(L("guests.intro")).font(.callout).foregroundStyle(Theme.secondaryText)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .focusScope(namespace)
        }
    }

    @Namespace private var namespace
}

/// QR con una didascalia, per la pagina degli invitati e per la Wi‑Fi.
private struct QRCard: View {
    let text: String
    let label: String
    let caption: String
    var detail: String?
    var size: CGFloat = 360

    var body: some View {
        VStack(spacing: 18) {
            QRCodeView(text: text, accessibilityLabel: label, size: size)
            Text(caption).font(.headline).multilineTextAlignment(.center)
            if let detail {
                Text(detail).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(2).multilineTextAlignment(.center)
            }
        }
        .frame(width: size + 40)
        .padding(24)
        .background(Theme.navySoft, in: RoundedRectangle(cornerRadius: 24))
    }
}

/// Motivo per cui il dispositivo non può ancora ricevere foto, o l'avvio in corso.
private struct GuestStatus: View {
    let problem: GuestProblem?

    var body: some View {
        VStack(spacing: 20) {
            if let problem {
                Image(systemName: "wifi.exclamationmark").font(.system(size: 64)).foregroundStyle(Theme.amber)
                Text(problem == .noNetwork ? L("guests.no_network") : L("guests.server_failed"))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Theme.amber)
            } else {
                ProgressView()
                Text(L("guests.starting")).foregroundStyle(Theme.secondaryText)
            }
        }
        .padding(32)
    }
}

/// Attesa della prima foto nello slideshow della festa: QR grande al centro.
struct WaitingForGuestsView: View {
    let guests: GuestParty

    var body: some View {
        VStack(spacing: 32) {
            Text(L("guests.waiting_title")).font(.system(size: 64, weight: .bold))
            if let url = guests.url {
                QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 420)
                Text(L("guests.waiting_body")).font(.title3).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center)
            } else {
                GuestStatus(problem: guests.problem)
            }
        }
        .frame(maxWidth: 1200)
    }
}

/// QR piccolo nell'angolo dello slideshow.
struct SlideshowGuestQR: View {
    let url: String

    var body: some View {
        VStack(spacing: 10) {
            QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 200)
            Text(L("guests.slideshow_cta")).font(.callout.bold())
        }
        .padding(16)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 20))
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
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("review.kicker").uppercased()).font(.headline).foregroundStyle(Theme.coral)
                    Text(L("review.title")).font(.largeTitle.bold())
                    Text(L("guests.pending", pending.count)).foregroundStyle(Theme.secondaryText)
                }
                if let photo = pending.first {
                    HStack(alignment: .top, spacing: 48) {
                        PhotoImage(photo: photo, fitMode: .contain, maxPixelSize: 1920)
                            .background(Color.black)
                            .clipShape(RoundedRectangle(cornerRadius: 24))
                        VStack(alignment: .leading, spacing: 28) {
                            Text(photo.title).font(.title3.bold()).lineLimit(2)
                            Button {
                                model.approveGuestPhoto(photo)
                            } label: {
                                Label(L("review.show"), systemImage: "checkmark")
                            }
                            Button(role: .destructive) {
                                model.discardGuestPhoto(photo)
                            } label: {
                                Label(L("review.discard"), systemImage: "xmark")
                            }
                            if pending.count > 1 {
                                Button {
                                    model.guests.approveAll()
                                } label: {
                                    Label(L("review.approve_all"), systemImage: "checkmark.circle")
                                }
                            }
                        }
                        .frame(width: 520, alignment: .leading)
                    }
                } else {
                    Text(L("review.empty")).foregroundStyle(Theme.secondaryText)
                }
            }
            .padding(80)
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
