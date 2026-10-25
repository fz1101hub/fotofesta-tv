import AVFoundation
import FotoFestaCore
import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// Cosa chiede il tastierino del chiosco.
enum PinPurpose: String, Identifiable {
    case set, disable, settings, endParty
    var id: String { rawValue }

    var title: String {
        switch self {
        case .set: L("pin.set")
        case .disable: L("pin.disable")
        case .settings: L("pin.settings")
        case .endParty: L("pin.end_party")
        }
    }
}

/// Voce di sistema per gli annunci, nella lingua dell'app.
@MainActor
final class AnnouncementSpeaker {
    private let synthesizer = AVSpeechSynthesizer()

    func speak(_ text: String) {
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Bundle.main.preferredLocalizations.first)
        synthesizer.speak(utterance)
    }
}

/// Uso fisso in un locale: chiosco con il PIN, festa sempre attiva, azzeramento notturno, scaletta
/// annunciata all'ora giusta, annunci letti ad alta voce e configurazione da passare a un altro schermo.
extension AppModel {
    /// Chiosco acceso, PIN scelto e nessuno sblocco negli ultimi minuti.
    var kioskLocked: Bool {
        _ = kioskUnlockedUntil
        return kioskLock.locked(enabled: settings.kiosk)
    }

    func requestEndParty() {
        guard kioskLocked else { return endGuestParty() }
        // Il tastierino si apre dopo che la richiesta di conferma si è chiusa.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            self?.pinRequest = .endParty
        }
    }

    /// Accende (scegliendo il PIN) o spegne (con il PIN) il chiosco.
    func toggleKiosk() {
        pinRequest = settings.kiosk && kioskLock.hasPin ? .disable : .set
    }

    func cancelPin() {
        pinRequest = nil
    }

    /// PIN scritto per `purpose`: false se è sbagliato (il tastierino resta aperto).
    func submitPin(_ pin: String, for purpose: PinPurpose) -> Bool {
        if purpose == .set {
            guard kioskLock.save(pin) else { return false }
            unlockKiosk()
            pinRequest = nil
            updateSettings { $0.kiosk = true }
            showMessage(L("message.kiosk_on"))
            return true
        }
        guard kioskLock.matches(pin) else { return false }
        unlockKiosk()
        if pinRequest == purpose { pinRequest = nil }
        switch purpose {
        case .disable:
            kioskLock.save(nil)
            updateSettings { $0.kiosk = false }
            showMessage(L("message.kiosk_off"))
        case .endParty:
            endGuestParty()
        case .settings, .set:
            break
        }
        return true
    }

    private func unlockKiosk() {
        kioskLock.unlock()
        kioskUnlockedUntil = kioskLock.unlockedUntil
    }

    /// Ogni venti secondi: momenti della scaletta da annunciare e azzeramento notturno.
    func startVenueClock() {
        venueClock?.cancel()
        venueClock = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                self?.tickClock(now: Date())
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }

    func tickClock(now: Date) {
        let calendar = Calendar.current
        let parts = calendar.dateComponents([.year, .hour, .minute], from: now)
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let day = (parts.year ?? 0) * 1000 + (calendar.ordinality(of: .day, in: .year, for: now) ?? 0)
        if guests.active {
            for slot in guests.setup.schedule where slot.minuteOfDay == minute {
                if announcedSlots.insert("\(day)-\(minute)-\(slot.title)").inserted { guests.announce(slot.title) }
            }
        }
        let reset = settings.nightlyReset
        guard reset >= 0, minute == reset * 60, lastResetDay != day else { return }
        lastResetDay = day
        guard guests.active else { return }
        endGuestParty()
        if settings.alwaysParty {
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(3))
                self?.startPartyAndPlay()
            }
        }
    }

    /// Annuncio nuovo: letto ad alta voce se l'impostazione lo chiede. Un annuncio vecchio ripreso
    /// con la festa all'apertura dell'app non si legge.
    func announcementArrived(_ announcement: PartyHub.Announcement) {
        guard settings.speakAnnouncements, announcement.date > Date().addingTimeInterval(-30) else { return }
        speaker.speak(announcement.text)
    }

    /// Impostazioni, preparazione della festa e Wi‑Fi degli invitati in un file.
    func exportConfig() -> Data? {
        VenueConfig.make(settings: settings, setup: guests.partySetup.exportJSON(), wifi: guests.wifi)
    }

    func importConfig(_ data: Data) -> Bool {
        guard let contents = VenueConfig.parse(data) else {
            showMessage(L("message.import_failed"))
            return false
        }
        // Il chiosco resta com'è su questo schermo: il PIN non viaggia nel file.
        let kiosk = settings.kiosk
        updateSettings { settings in
            settings = contents.settings
            settings.kiosk = kiosk
        }
        if let setup = contents.setup { guests.partySetup.importJSON(setup) }
        if let wifi = contents.wifi { guests.saveWifi(wifi) }
        guests.refreshWallAndSetup()
        showMessage(L("message.import_done"))
        return true
    }
}

/// File di configurazione da salvare con il pannello del Mac.
struct ConfigDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
