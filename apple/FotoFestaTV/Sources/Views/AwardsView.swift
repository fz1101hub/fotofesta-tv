import FotoFestaCore
import SwiftUI

/// Premi di fine festa: "Obiettivo d'oro", prima foto e gran finale, con il riepilogo della festa.
struct AwardsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let awards = PartyAwards(model.photos)
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 56) {
                ScreenTitle(title: L("awards.title"), subtitle: summary(awards))
                HStack(alignment: .top, spacing: 44) {
                    if let top = awards.topPhotographer {
                        AwardCard(systemImage: "trophy.fill", award: L("awards.golden_lens"), name: top.name,
                                  detail: LP("awards.photo_count", top.count), photo: top.photo)
                    }
                    if let first = awards.firstPhoto {
                        AwardCard(systemImage: "flag.fill", award: L("awards.first"), name: senderName(first),
                                  detail: arrival(first), photo: first)
                    }
                    if let last = awards.lastPhoto {
                        AwardCard(systemImage: "party.popper.fill", award: L("awards.last"), name: senderName(last),
                                  detail: arrival(last), photo: last)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func summary(_ awards: PartyAwards) -> String {
        var parts = [LP("awards.photo_count", awards.photos)]
        if awards.guests > 0 { parts.append(LP("awards.guest_count", awards.guests)) }
        return parts.joined(separator: " · ")
    }

    private func senderName(_ photo: Photo) -> String {
        let name = (photo.sender ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? L("awards.anonymous") : name
    }

    private func arrival(_ photo: Photo) -> String {
        guard let receivedAt = photo.receivedAt else { return "" }
        return L("awards.at", receivedAt.formatted(date: .omitted, time: .shortened))
    }
}

/// Scheda di un premio: la foto, il nome del premio, chi lo vince e un dettaglio.
private struct AwardCard: View {
    let systemImage: String
    let award: String
    let name: String
    let detail: String
    let photo: Photo

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PhotoImage(photo: photo, fitMode: .crop, maxPixelSize: 1000, placeholder: false)
                .frame(width: 520, height: 292)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .accessibilityHidden(true)
            Label(award, systemImage: systemImage)
                .font(.headline)
                .foregroundStyle(Theme.secondaryText)
            Text(name).font(.title2.bold()).lineLimit(1)
            if !detail.isEmpty {
                Text(detail).font(.callout).foregroundStyle(Theme.secondaryText)
            }
        }
        .frame(width: 520, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
