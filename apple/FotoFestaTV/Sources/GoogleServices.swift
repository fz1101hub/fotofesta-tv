import FotoFestaCore
import Foundation

/// Servizi Google condivisi dal modello dell'app e dal caricamento delle immagini: una sola istanza.
final class GoogleServices: Sendable {
    static let shared = GoogleServices()

    let auth: GoogleDeviceAuth
    let picker: PhotosPicker
    let selections: GoogleSelectionStore
    let stream: GooglePhotosStream
    let importFlow: GoogleImportFlow

    private init() {
        let http = URLSessionHTTPClient()
        let info = Bundle.main.infoDictionary ?? [:]
        auth = GoogleDeviceAuth(
            http: http,
            tokens: KeychainTokenStore(),
            clientID: (info["PhotosClientID"] as? String) ?? "",
            clientSecret: (info["PhotosClientSecret"] as? String) ?? ""
        )
        picker = PhotosPicker(http: http)
        selections = GoogleSelectionStore()
        stream = GooglePhotosStream(picker: picker, auth: auth, selections: selections)
        importFlow = GoogleImportFlow(auth: auth, picker: picker)
    }
}
