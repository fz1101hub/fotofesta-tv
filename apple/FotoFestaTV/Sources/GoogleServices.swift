import FotoFestaCore
import Foundation

/// Servizi Google condivisi dal modello dell'app e dal caricamento delle immagini: una sola istanza.
final class GoogleServices: Sendable {
    static let shared = GoogleServices()

    let auth: GoogleAuth
    let picker: PhotosPicker
    let selections: GoogleSelectionStore
    let stream: GooglePhotosStream
    let importFlow: GoogleImportFlow

    private init() {
        let http = URLSessionHTTPClient()
        let info = Bundle.main.infoDictionary ?? [:]
        auth = GoogleAuth(
            http: http,
            tokens: KeychainTokenStore(),
            clientID: (info["PhotosClientID"] as? String) ?? "",
            clientSecret: (info["PhotosClientSecret"] as? String) ?? "",
            redirectURI: (info["PhotosRedirectURI"] as? String) ?? ""
        )
        picker = PhotosPicker(http: http)
        selections = GoogleSelectionStore()
        stream = GooglePhotosStream(picker: picker, auth: auth, selections: selections)
        // Il consenso arriva dal telefono, con la pagina (shared/assets/guest/google.html) servita dalla Apple TV.
        importFlow = GoogleImportFlow(auth: auth, picker: picker) {
            LANPhoneConsent(page: {
                Bundle.main.url(forResource: "google", withExtension: "html")
                    .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
            })
        }
    }
}
