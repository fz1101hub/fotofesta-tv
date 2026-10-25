import AppIntents
import Foundation

/// Il modello dell'app aperta, per i Comandi rapidi e Siri (una sola finestra, una sola festa).
@MainActor
enum IntentTarget {
    static weak var model: AppModel?
}

struct StartPartyIntent: AppIntent {
    static let title: LocalizedStringResource = LocalizedStringResource("intent.start_party")
    static let description = IntentDescription(LocalizedStringResource("intent.start_party_body"))
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentTarget.model?.startPartyAndPlay()
        return .result()
    }
}

struct StartSlideshowIntent: AppIntent {
    static let title: LocalizedStringResource = LocalizedStringResource("intent.slideshow")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentTarget.model?.play()
        return .result()
    }
}

struct NextPhotoIntent: AppIntent {
    static let title: LocalizedStringResource = LocalizedStringResource("intent.next")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentTarget.model?.next()
        return .result()
    }
}

struct AnnounceIntent: AppIntent {
    static let title: LocalizedStringResource = LocalizedStringResource("intent.announce")
    static let openAppWhenRun = true

    @Parameter(title: LocalizedStringResource("intent.announce_text"))
    var text: String

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let model = IntentTarget.model, model.guests.active else { return .result() }
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !clean.isEmpty { model.guests.announce(String(clean.prefix(140))) }
        return .result()
    }
}

/// Fine della festa: con il chiosco acceso chiede il PIN sullo schermo, come il telecomando.
struct EndPartyIntent: AppIntent {
    static let title: LocalizedStringResource = LocalizedStringResource("intent.end_party")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let model = IntentTarget.model, model.guests.active else { return .result() }
        model.requestEndParty()
        return .result()
    }
}

struct FotoFestaShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartPartyIntent(),
            phrases: ["Start the party in \(.applicationName)", "Start \(.applicationName)"],
            shortTitle: LocalizedStringResource("intent.start_party"),
            systemImageName: "party.popper.fill"
        )
        AppShortcut(
            intent: StartSlideshowIntent(),
            phrases: ["Show the photos in \(.applicationName)"],
            shortTitle: LocalizedStringResource("intent.slideshow"),
            systemImageName: "play.rectangle.fill"
        )
        AppShortcut(
            intent: NextPhotoIntent(),
            phrases: ["Next photo in \(.applicationName)"],
            shortTitle: LocalizedStringResource("intent.next"),
            systemImageName: "forward.fill"
        )
        AppShortcut(
            intent: AnnounceIntent(),
            phrases: ["Make an announcement in \(.applicationName)"],
            shortTitle: LocalizedStringResource("intent.announce"),
            systemImageName: "megaphone.fill"
        )
        AppShortcut(
            intent: EndPartyIntent(),
            phrases: ["End the party in \(.applicationName)"],
            shortTitle: LocalizedStringResource("intent.end_party"),
            systemImageName: "stop.circle.fill"
        )
    }
}
