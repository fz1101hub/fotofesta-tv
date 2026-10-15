import FotoFestaCore
import SwiftUI

struct GoogleConnectView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("google.kicker").uppercased()).font(.headline).foregroundStyle(Theme.coral)
                    Text(L("google.title")).font(.largeTitle.bold())
                    Text(L("google.subtitle")).foregroundStyle(Theme.secondaryText)
                }
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(48)
                    .background(Theme.navySoft, in: RoundedRectangle(cornerRadius: 32))
            }
            .padding(80)
        }
        .onExitCommand { cancel() }
        // Se la schermata si chiude in altro modo (Menu senza elementi a fuoco), il collegamento non
        // deve proseguire in background e sostituire più tardi le foto.
        .onDisappear {
            if case .working = model.googleState { model.cancelGoogleConnect() }
        }
    }

    private func cancel() {
        model.cancelGoogleConnect()
        dismiss()
    }

    /// Pulsante a fuoco durante l'attesa: senza un elemento focalizzabile il tasto Menu non arriva qui.
    private var cancelButton: some View {
        Button(L("action.cancel")) { cancel() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.googleState {
        case .idle, .working(nil):
            VStack(spacing: 24) {
                ProgressView()
                Text(L("google.preparing")).font(.title3)
                cancelButton
            }
        case let .working(.waitingForAuthorization(url, code, _)?):
            HStack(spacing: 64) {
                QRCodeView(text: url, accessibilityLabel: L("google.qr_authorize"))
                VStack(alignment: .leading, spacing: 20) {
                    Text(L("google.step_scan")).font(.title2.bold())
                    Text(L("google.open_url", url)).foregroundStyle(Theme.secondaryText)
                    Text(code)
                        .font(.system(size: 64, weight: .bold, design: .monospaced))
                        .foregroundStyle(Theme.amber)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 14)
                        .background(Theme.navy, in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityLabel(Text(code.map(String.init).joined(separator: " ")))
                    Text(L("google.scope_note")).font(.callout).foregroundStyle(Theme.secondaryText)
                    cancelButton
                }
            }
        case let .working(.waitingForPhotos(pickerURI)?):
            HStack(spacing: 64) {
                QRCodeView(text: pickerURI, accessibilityLabel: L("google.qr_pick"))
                VStack(alignment: .leading, spacing: 20) {
                    Text(L("google.step_pick")).font(.title2.bold())
                    Text(L("google.pick_instructions"))
                    Text(L("google.pick_hint")).font(.callout).foregroundStyle(Theme.secondaryText)
                    ProgressView()
                    cancelButton
                }
            }
        case let .working(.downloading(completed, total)?):
            VStack(spacing: 24) {
                Text(L("google.downloading")).font(.title2.bold())
                Text(L("google.progress", completed, total)).font(.title3).foregroundStyle(Theme.amber)
                ProgressView(value: Double(completed), total: Double(max(total, 1)))
                    .frame(maxWidth: 900)
                Text(L("google.cache_note")).font(.callout).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center)
                cancelButton
            }
        case let .failed(message):
            VStack(spacing: 24) {
                Text(L("google.unavailable")).font(.title2.bold())
                Text(message).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center)
                HStack(spacing: 32) {
                    Button(L("action.retry")) { model.startGoogleConnect() }
                    cancelButton
                }
            }
        case let .complete(count):
            VStack(spacing: 24) {
                Text(L("google.photos_ready", count)).font(.largeTitle.bold()).foregroundStyle(Theme.cyan)
                Text(L("google.complete_body")).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center)
                Button {
                    model.play()
                } label: {
                    Label(L("action.start_slideshow"), systemImage: "play.fill")
                }
            }
        }
    }
}
