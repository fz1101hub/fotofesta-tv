#if DEBUG
import Foundation

/// Solo nelle build di debug: apre una schermata scelta con la variabile d'ambiente
/// FOTOFESTA_SCREENSHOT, per le schermate fatte dal simulatore in CI (vedi .github/workflows/apple.yml).
extension AppModel {
    func applyScreenshotScenario() {
        guard let scenario = ProcessInfo.processInfo.environment["FOTOFESTA_SCREENSHOT"] else { return }
        switch scenario {
        case "slideshow": startDemo()
        case "settings": path = [.settings]
        case "games": path = [.games]
        case "memory": path = [.games, .memory]
        case "mystery": path = [.games, .mystery]
        // "puzzle_game" apre anche il livello medio (vedi PuzzleGameView).
        case "puzzle", "puzzle_game": path = [.games, .puzzle]
        case "guests": path = [.guests]
        case "party":
            startGuestParty()
            path = [.guests]
        case "party_slideshow": startPartyAndPlay()
        case "party_upload":
            // Lo script manda due foto come il telefono di un invitato; poco dopo si passa alla
            // schermata della festa, con le miniature.
            startPartyAndPlay()
            shareGuestURL()
            Task {
                await waitUntil { self.approvedGuestCount >= 2 }
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                self.path = [.guests]
            }
        case "who_game":
            // Lo script manda due foto firmate; quando ci sono, si apre "Chi l'ha scattata?".
            startGuestParty()
            shareGuestURL()
            Task {
                await waitUntil { self.approvedGuestCount >= 2 }
                self.path = [.games, .whoTookIt]
            }
        case "awards":
            // Lo script manda tre foto firmate; poi si aprono i premi della festa.
            startGuestParty()
            shareGuestURL()
            Task {
                await waitUntil { self.approvedGuestCount >= 3 }
                self.path = [.guests, .awards]
            }
        case "mosaic":
            // Ultimo scenario: chiude la festa (le foto demo tornano nella raccolta) e lascia il mosaico.
            if guests.active { endGuestParty() }
            // Conto alla rovescia della torta tra 15-30 minuti, sull'orario del quarto d'ora.
            let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
            let minutes = ((now.hour ?? 0) * 60 + (now.minute ?? 0)) / 15 * 15 + 30
            updateSettings {
                $0.layout = .mosaic
                $0.countdown = true
                $0.countdownMinutes = minutes % (24 * 60)
                $0.countdownEvent = .cake
            }
            startDemo()
        case "review":
            guests.setApproval(true)
            startGuestParty()
            path = [.guests]
            shareGuestURL()
            Task {
                await waitUntil { !self.pendingGuestPhotos.isEmpty }
                self.path = [.guests, .guestReview]
            }
        default: break
        }
    }

    /// Scrive l'indirizzo per gli invitati in Caches, dove lo script lo legge per mandare le foto.
    private func shareGuestURL() {
        Task {
            await waitUntil { self.guests.url != nil }
            guard let url = self.guests.url else { return }
            let file = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("screenshot-guest-url.txt")
            try? url.write(to: file, atomically: true, encoding: .utf8)
        }
    }

    /// Attende al massimo un minuto che la condizione sia vera.
    private func waitUntil(_ condition: () -> Bool) async {
        var attempts = 0
        while !condition(), attempts < 120 {
            attempts += 1
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }
}
#endif
