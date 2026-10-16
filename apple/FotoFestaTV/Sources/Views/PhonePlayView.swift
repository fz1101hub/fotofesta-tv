import FotoFestaCore
import SwiftUI

/// Pannello del gioco con i telefoni, sotto la foto: QR della festa, quante risposte sono arrivate
/// e, dopo la risposta giusta, la classifica. Si aggiorna da solo mentre gli invitati rispondono.
struct PhonePlayPanel: View {
    let game: PhoneGame
    let url: String
    let revealed: Bool

    /// Sulla TV la classifica mostra i primi tre: il pannello resta alto quanto il QR.
    private static let leaderboardSize = 3

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let answers = game.answerCount()
            let leaderboard = game.leaderboard(limit: Self.leaderboardSize)
            HStack(alignment: .top, spacing: 28) {
                QRCodeView(text: url, accessibilityLabel: L("phone.scan"), size: 170)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("phone.title")).font(.headline)
                    Text(L("phone.scan"))
                        .font(.caption)
                        .foregroundStyle(Theme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(LP("phone.answers", answers)).font(.headline).padding(.top, 4)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if revealed, !leaderboard.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("phone.leaderboard")).font(.subheadline).foregroundStyle(Theme.secondaryText)
                        ForEach(Array(leaderboard.enumerated()), id: \.offset) { index, score in
                            HStack {
                                Text("\(index + 1). \(score.name)").lineLimit(1)
                                Spacer(minLength: 16)
                                Text(LP("phone.points", score.points)).foregroundStyle(Theme.secondaryText)
                            }
                            .font(.callout)
                        }
                    }
                    .frame(width: 380, alignment: .leading)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
    }
}

/// Risposta mostrata sulla TV durante il gioco con i telefoni: si sceglie sui telefoni; dopo la
/// rivelazione quella giusta è evidenziata.
struct ChoiceLabel: View {
    let text: String
    let highlighted: Bool

    var body: some View {
        Label {
            Text(text).lineLimit(1)
        } icon: {
            Image(systemName: highlighted ? "checkmark.circle.fill" : "circle")
        }
        .font(.headline)
        .foregroundStyle(highlighted ? Color.black : Color.primary)
        .padding(.horizontal, 24)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(highlighted ? AnyShapeStyle(Color.white) : AnyShapeStyle(.regularMaterial), in: Capsule())
    }
}
