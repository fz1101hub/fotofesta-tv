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
        case "guests": path = [.guests]
        case "party":
            startGuestParty()
            path = [.guests]
        case "party_slideshow": startPartyAndPlay()
        default: break
        }
    }
}
#endif
