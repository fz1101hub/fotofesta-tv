import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 40) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(L("home.kicker").uppercased())
                            .font(.headline)
                            .foregroundStyle(Theme.coral)
                        Text(L("home.title"))
                            .font(.largeTitle.bold())
                        Text(model.sourceLabel)
                            .font(.title3)
                            .foregroundStyle(Theme.secondaryText)
                    }
                    Spacer(minLength: 40)
                    Button {
                        model.play()
                    } label: {
                        Label(L("action.start_slideshow"), systemImage: "play.fill")
                    }
                    .prefersDefaultFocus(true, in: namespace)
                }

                HStack(spacing: 48) {
                    // Senza client OAuth nella build la scheda non compare: l'app resta usabile con le foto demo.
                    if model.isGoogleConfigured {
                        SourceCard(title: L("home.google_title"), subtitle: L("home.google_body"), systemImage: "photo.on.rectangle.angled", tint: Theme.cyan) {
                            model.startGoogleConnect()
                        }
                    }
                    SourceCard(title: L("home.demo_title"), subtitle: L("home.demo_body"), systemImage: "sparkles", tint: Theme.coral) {
                        model.startDemo()
                    }
                }

                HStack(spacing: 32) {
                    NavigationLink(value: Route.settings) {
                        Label(L("action.settings"), systemImage: "gearshape")
                    }
                    NavigationLink(value: Route.games) {
                        Label(L("home.games"), systemImage: "gamecontroller")
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(80)
            .focusScope(namespace)
        }
    }

    @Namespace private var namespace
}

struct SourceCard: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: systemImage)
                    .font(.system(size: 56, weight: .semibold))
                    .foregroundStyle(tint)
                Text(title)
                    .font(.title2.bold())
                Text(subtitle)
                    .font(.callout)
                    .foregroundStyle(Theme.secondaryText)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
            .padding(36)
            .frame(width: 620, height: 300, alignment: .topLeading)
            .background(
                LinearGradient(colors: [tint.opacity(0.35), Theme.navySoft], startPoint: .topLeading, endPoint: .bottomTrailing)
            )
        }
        .buttonStyle(.card)
    }
}
