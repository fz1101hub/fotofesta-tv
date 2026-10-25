import FotoFestaCore
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Festa con le foto degli invitati, nello stile delle schermate di accesso di tvOS: QR su una scheda
/// bianca a sinistra, a destra titolo, stato e pulsanti di sistema.
struct GuestsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingEnd = false
    @Namespace private var namespace

    var body: some View {
        let guests = model.guests
        ZStack {
            // Sfondo: l'ultima foto arrivata, sfocata; altrimenti nero.
            if let latest = recent.first {
                BlurredBackdrop(photo: latest).ignoresSafeArea()
            } else {
                Color.black.ignoresSafeArea()
            }
            if guests.active {
                inProgress(guests)
            } else {
                intro(guests)
            }
        }
        .focusScope(namespace)
        .confirmationDialog(L("guests.end_confirm_title"), isPresented: $confirmingEnd, titleVisibility: .visible) {
            Button(L("guests.end"), role: .destructive) { model.endGuestParty() }
            Button(L("action.cancel"), role: .cancel) {}
        } message: {
            Text(L("guests.end_confirm_body", model.guests.photos.count))
        }
    }

    private func intro(_ guests: GuestParty) -> some View {
        HStack(alignment: .center, spacing: 100) {
            Image(systemName: "person.2.wave.2.fill")
                .font(.system(size: 240))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.white)
                .frame(width: 560)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 36) {
                ScreenTitle(title: L("guests.title"), subtitle: L("guests.same_network"))
                VStack(alignment: .leading, spacing: 18) {
                    StepRow(number: 1, text: L("guests.step_1"))
                    StepRow(number: 2, text: L("guests.step_2"))
                    StepRow(number: 3, text: L("guests.step_3"))
                }
                HStack(spacing: 32) {
                    Button {
                        model.startGuestParty()
                    } label: {
                        Label(L("home.start_party"), systemImage: "person.2.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .prefersDefaultFocus(true, in: namespace)
                    NavigationLink(value: Route.settings) {
                        Label(L("action.settings"), systemImage: "gearshape")
                    }
                    // Due minuti con telefoni finti, per provare schermo e audio prima della festa.
                    Button { model.startRehearsal() } label: {
                        Label(L("guests.rehearsal"), systemImage: "theatermasks.fill")
                    }
                }
                Text(L("guests.intro")).font(.callout).foregroundStyle(Theme.secondaryText)
                // AirPlay lo gestisce tvOS sopra l'app: le foto trasmesse non entrano nella festa.
                Label(L("guests.airplay_tip"), systemImage: "airplayvideo")
                    .font(.callout)
                    .foregroundStyle(Theme.secondaryText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func inProgress(_ guests: GuestParty) -> some View {
        HStack(alignment: .center, spacing: 100) {
            SendPanel(guests: guests)
                .frame(width: 640)
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("guests.party_started"), subtitle: stats)
                if !recent.isEmpty {
                    HStack(spacing: 24) {
                        ForEach(recent) { photo in
                            PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 400, placeholder: false)
                                .frame(width: 220, height: 124)
                                .background(Color(white: 0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                }
                HStack(spacing: 32) {
                    Button {
                        model.play()
                    } label: {
                        Label(L("action.start_slideshow"), systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .prefersDefaultFocus(true, in: namespace)
                    if !model.pendingGuestPhotos.isEmpty {
                        NavigationLink(value: Route.guestReview) {
                            Label(LP("guests.approve_photos", model.pendingGuestPhotos.count), systemImage: "checkmark.circle")
                        }
                    }
                    if model.approvedGuestCount > 0 {
                        NavigationLink(value: Route.awards) {
                            Label(L("guests.awards"), systemImage: "trophy.fill")
                        }
                    }
                    // Senza ruolo "distruttivo": su tvOS 26 il pulsante rosso senza fuoco nasconde il
                    // testo. La conferma, nel dialogo di sistema, resta rossa.
                    Button {
                        confirmingEnd = true
                    } label: {
                        Label(L("guests.end_short"), systemImage: "xmark.circle")
                    }
                }
                HStack(spacing: 32) {
                    NavigationLink(value: Route.announce) {
                        Label(L("guests.announce"), systemImage: "megaphone.fill")
                    }
                    NavigationLink(value: Route.door) {
                        Label(L("door.button"), systemImage: "door.left.hand.open")
                    }
                    NavigationLink(value: Route.phoneWall) {
                        Label(L("phone_wall.button"), systemImage: "rectangle.split.3x1")
                    }
                    if !guests.developed {
                        Button { model.develop() } label: {
                            Label(LP("guests.develop", guests.shotsTaken), systemImage: "camera.aperture")
                        }
                    }
                    if !model.guests.messages.isEmpty || !model.guests.pendingMessages.isEmpty {
                        NavigationLink(value: Route.guestbook) {
                            if model.guests.pendingMessages.isEmpty {
                                Label(L("guestbook.title"), systemImage: "book.fill")
                            } else {
                                Label(LP("guestbook.approve", model.guests.pendingMessages.count), systemImage: "book.fill")
                            }
                        }
                    }
                }
                if let pin = guests.hostPIN {
                    VStack(alignment: .leading, spacing: 8) {
                        // Il PIN si legge a gruppi di tre, come un codice di verifica.
                        Text(L("host.pin", "\(pin.prefix(3)) \(pin.suffix(3))")).font(.headline)
                        Text(L("host.pin_hint"))
                            .font(.callout)
                            .foregroundStyle(Theme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// "23 foto ricevute · 4 da approvare".
    private var stats: String {
        var parts = ["\(model.approvedGuestCount) " + LP("guests.stat_received", model.approvedGuestCount)]
        let pending = model.pendingGuestPhotos.count
        if pending > 0 { parts.append("\(pending) " + LP("guests.stat_pending", pending)) }
        return parts.joined(separator: " · ")
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
                .font(.headline)
                .frame(width: 52, height: 52)
                .background(Circle().fill(.regularMaterial))
            Text(text).font(.title3).fixedSize(horizontal: false, vertical: true)
        }
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
                    QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 440)
                        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .shadow(color: .black.opacity(0.5), radius: 30, y: 12)
                }
                Text(L("guests.send_title")).font(.title3.bold()).multilineTextAlignment(.center)
                Text(guests.wifi.map { L("guests.send_body_network", $0.ssid) } ?? L("guests.send_body"))
                    .font(.callout)
                    .foregroundStyle(Theme.secondaryText)
                    .multilineTextAlignment(.center)
            } else {
                GuestStatus(problem: guests.problem)
            }
        }
    }

    private func labeledQR(_ text: String, description: String, label: String) -> some View {
        VStack(spacing: 16) {
            QRCodeView(text: text, accessibilityLabel: description, size: 290)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            Text(label).font(.headline).multilineTextAlignment(.center)
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
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Theme.error)
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
        VStack(spacing: 36) {
            Text(L("guests.waiting_title")).font(.system(size: 64, weight: .bold))
            if let url = guests.url {
                QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 440)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                Text(L("guests.waiting_body")).font(.title3).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center)
            } else {
                GuestStatus(problem: guests.problem)
            }
        }
        .frame(maxWidth: 1200)
    }
}

/// QR piccolo nell'angolo dello slideshow, o l'avviso se la festa non riesce a partire.
struct SlideshowGuestQR: View {
    let guests: GuestParty

    var body: some View {
        HStack(spacing: 20) {
            if let url = guests.url {
                QRCodeView(text: url, accessibilityLabel: L("guests.qr_send"), size: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("guests.slideshow_cta")).font(.headline)
                    Text(L("slideshow.qr_body")).font(.caption).foregroundStyle(Theme.secondaryText)
                }
            } else if let problem = guests.problem {
                Text(problem == .noNetwork ? L("guests.no_network") : L("guests.server_failed"))
                    .font(.callout)
                    .foregroundStyle(Theme.error)
                    .frame(maxWidth: 640, alignment: .leading)
            } else {
                ProgressView()
                Text(L("guests.starting")).font(.callout).foregroundStyle(Theme.secondaryText)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 28)
        .padding(.vertical, 16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Foto in attesa: si mostrano una alla volta, la più vecchia per prima.
struct GuestReviewView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let pending = model.pendingGuestPhotos
        VStack(alignment: .leading, spacing: 40) {
            ScreenTitle(title: L("guests.title"), subtitle: L("guests.pending", pending.count))
            if let photo = pending.first {
                HStack(alignment: .top, spacing: 64) {
                    // Come nello slideshow: la foto intera, con la stessa foto sfocata ai lati.
                    ZStack {
                        BlurredBackdrop(photo: photo)
                        PhotoImage(photo: photo, fitMode: .contain, maxPixelSize: 1920)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    VStack(alignment: .leading, spacing: 28) {
                        Text(photo.title).font(.title3.bold()).lineLimit(2)
                        Button {
                            model.approveGuestPhoto(photo)
                        } label: {
                            Label(L("review.show"), systemImage: "checkmark")
                        }
                        .buttonStyle(.borderedProminent)
                        Button {
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
                Text(L("review.empty")).font(.title3).foregroundStyle(Theme.secondaryText)
            }
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
                    .plainTextInput()
                // La password resta visibile: il QR la mostra comunque a chi lo inquadra.
                TextField(L("wifi.password"), text: $password)
                    .plainTextInput()
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
