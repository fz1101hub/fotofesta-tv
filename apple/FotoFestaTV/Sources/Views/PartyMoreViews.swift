import FotoFestaCore
import SwiftUI

/// Ingresso e parete delle foto: il QR da mettere alla porta (chi arriva tocca "Eccomi!" e la TV lo
/// saluta) e l'indirizzo della parete per un secondo schermo (tablet, computer) sulla stessa Wi‑Fi.
struct DoorView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let guests = model.guests
        HStack(alignment: .top, spacing: 80) {
            if let url = guests.url {
                VStack(spacing: 20) {
                    QRCodeView(text: url + "&arrivo=1", accessibilityLabel: L("door.entrance"), size: 420)
                        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    Text(L("door.entrance")).font(.title3.bold())
                    Text(L("door.entrance_body")).font(.callout).foregroundStyle(Theme.secondaryText)
                        .multilineTextAlignment(.center).frame(width: 420)
                }
            }
            VStack(alignment: .leading, spacing: 32) {
                ScreenTitle(title: L("door.title"), subtitle: L("door.subtitle"))
                Text(LP("door.arrived", guests.arrivals.count)).font(.title2.bold())
                if !guests.arrivals.isEmpty {
                    // Gli ultimi arrivati, dal più recente.
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(guests.arrivals.suffix(6).reversed(), id: \.player) { arrival in
                            Text("\(arrival.order). \(arrival.name)").font(.headline)
                        }
                    }
                }
                if let url = guests.url {
                    let wall = url.replacingOccurrences(of: "/?k=", with: "/wall?k=")
                    HStack(alignment: .center, spacing: 32) {
                        QRCodeView(text: wall, accessibilityLabel: L("door.wall"), size: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("door.wall")).font(.title3.bold())
                            Text(L("door.wall_body", wall)).font(.callout).foregroundStyle(Theme.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(80)
    }
}

/// Muro di telefoni: gli invitati mettono i telefoni in fila e ognuno mostra un pezzo della stessa foto.
struct PhoneWallView: View {
    @Environment(AppModel.self) private var model
    @Namespace private var namespace

    var body: some View {
        let guests = model.guests
        let state = guests.phoneWallState
        VStack(alignment: .leading, spacing: 40) {
            ScreenTitle(title: L("phone_wall.title"), subtitle: L("phone_wall.subtitle"))
            HStack(spacing: 32) {
                Button { showRandomPhoto() } label: {
                    Label(state?.photoID == nil ? L("phone_wall.show") : L("phone_wall.another"), systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.borderedProminent)
                .prefersDefaultFocus(true, in: namespace)
                Button { guests.rearrangePhoneWall() } label: {
                    Label(L("phone_wall.rearrange"), systemImage: "arrow.triangle.2.circlepath")
                }
                if state?.phase == .showing {
                    Button { guests.phoneWallFinale() } label: {
                        Label(L("phone_wall.finale"), systemImage: "sparkles")
                    }
                }
            }
            if let state {
                Text(state.players.isEmpty ? L("phone_wall.waiting") : LP("phone_wall.count", state.players.count))
                    .font(.title2.bold())
                // La disposizione come la vedono i telefoni sul tavolo: numeri in fila, righe dall'alto.
                VStack(spacing: 16) {
                    ForEach(0..<state.rows, id: \.self) { row in
                        HStack(spacing: 16) {
                            ForEach(0..<state.columns, id: \.self) { column in
                                let slot = row * state.columns + column
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .fill(slot < state.players.count ? Color.white.opacity(0.18) : Color.white.opacity(0.05))
                                    .frame(width: 90, height: 160)
                                    .overlay(Text(slot < state.players.count ? "\(slot + 1)" : "").font(.title.bold()))
                            }
                        }
                    }
                }
            }
            Text(L("phone_wall.tip")).font(.callout).foregroundStyle(Theme.secondaryText)
            Spacer()
        }
        .padding(80)
        .focusScope(namespace)
        .onAppear { if guests.phoneWallState == nil { guests.startPhoneWall() } }
        .onDisappear { guests.stopPhoneWall() }
    }

    /// Una foto approvata degli invitati a caso, diversa da quella già sul muro se possibile.
    private func showRandomPhoto() {
        let guests = model.guests
        let approved = guests.photos.filter(\.approved).map(\.id)
        guard !approved.isEmpty else {
            model.showMessage(L("message.phone_wall_no_photos"))
            return
        }
        let current = guests.phoneWallState?.photoID
        let choice = approved.filter { $0 != current }.randomElement() ?? approved[0]
        if !guests.showOnPhoneWall(choice) { model.showMessage(L("message.phone_wall_nobody")) }
    }
}
