import AVFoundation
import FotoFestaCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Da dove un video in streaming prende i byte: indirizzo, intestazioni, dove può portare un
/// reindirizzamento e, se la credenziale scade, come rinnovarla.
struct VideoOrigin {
    var url: URL
    var headers: [String: String] = [:]
    /// Reindirizzamenti ammessi; altrove la risposta 3xx arriva com'è e il video fallisce.
    var allowRedirect: @Sendable (URL) -> Bool
    /// Rinnova dopo un 401/403 (token o indirizzo scaduti); nil se non c'è nulla da rinnovare.
    var renew: (@Sendable () async throws -> VideoOrigin)?
}

/// Legge per AVPlayer i video in streaming: quelli di Google Foto, il cui indirizzo vuole il token
/// dell'utente in un'intestazione che AVPlayer non permette di aggiungere, e quelli dei server della
/// rete di casa, che AVPlayer da solo seguirebbe anche fuori dalla rete se il server li rimandasse
/// altrove. I byte passano da qui, a pezzi, con le richieste Range che AVPlayer fa per spostarsi nel
/// file. Nulla viene salvato sul dispositivo (sessione effimera).
final class StreamingVideoLoader: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate, @unchecked Sendable {
    private static let scheme = "fotofesta-stream"

    private let fileExtension: String
    private let origin: @Sendable () async throws -> VideoOrigin
    private let queue = DispatchQueue(label: "it.fotofesta.video-loader")
    /// Tiene il caricatore finché `invalidate()` non la chiude.
    private var session: URLSession!

    /// Richiesta di AVPlayer in corso. `skip`: byte da scartare se il server risponde con il file
    /// intero invece che dal punto chiesto.
    private struct Pending {
        let task: URLSessionDataTask
        let request: AVAssetResourceLoadingRequest
        let origin: VideoOrigin
        let retried: Bool
        var skip: Int64
        /// Byte ancora da consegnare per questa richiesta; nil se AVPlayer vuole tutto fino alla fine.
        var remaining: Int64?
    }

    // Usato solo sulla coda `queue`.
    private var pending: [Int: Pending] = [:]

    init(fileExtension: String, origin: @escaping @Sendable () async throws -> VideoOrigin) {
        self.fileExtension = fileExtension.filter { $0.isLetter || $0.isNumber }
        self.origin = origin
        super.init()
        let operations = OperationQueue()
        operations.underlyingQueue = queue
        operations.maxConcurrentOperationCount = 1
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: operations)
    }

    /// Video di Google Foto: token nell'intestazione, rinnovato una volta se Google lo rifiuta;
    /// Google può spostare il video su un altro suo indirizzo https, mai altrove.
    static func google(mediaID: String, stream: GooglePhotosStream) -> StreamingVideoLoader {
        func origin(failed: GooglePhotosStream.Access?) async throws -> VideoOrigin {
            let access = try await stream.access(mediaID: mediaID, failed: failed)
            // "=dv" chiede il file del video; senza, Google manderebbe un fotogramma.
            guard let url = URL(string: access.baseURL + "=dv") else { throw URLError(.badURL) }
            return VideoOrigin(
                url: url,
                headers: ["Authorization": "Bearer \(access.accessToken)"],
                allowRedirect: { $0.scheme?.lowercased() == "https" },
                renew: { try await origin(failed: access) }
            )
        }
        return StreamingVideoLoader(fileExtension: "mp4") { try await origin(failed: nil) }
    }

    /// Video di un server della rete di casa: nessuna credenziale, reindirizzamenti solo nella rete di casa.
    static func lan(url: URL) -> StreamingVideoLoader {
        StreamingVideoLoader(fileExtension: url.pathExtension) {
            VideoOrigin(url: url, allowRedirect: { LANAddress.isLocal($0) }, renew: nil)
        }
    }

    /// Asset per AVPlayer. L'asset tiene il caricatore solo debolmente: va tenuto finché il video serve.
    func makeAsset() -> AVURLAsset {
        var components = URLComponents()
        components.scheme = Self.scheme
        components.host = "media"
        components.path = "/video" + (fileExtension.isEmpty ? "" : "." + fileExtension)
        let asset = AVURLAsset(url: components.url ?? URL(fileURLWithPath: "/"))
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    /// Chiude le connessioni; la sessione tiene il caricatore finché non viene invalidata.
    func invalidate() {
        session.invalidateAndCancel()
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        load(loadingRequest, renewing: nil)
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        for (id, item) in pending where item.request === loadingRequest {
            item.task.cancel()
            pending[id] = nil
        }
    }

    /// Chiede l'origine del video (indirizzo e credenziali valide); con `failed` la rinnova.
    private func load(_ request: AVAssetResourceLoadingRequest, renewing failed: VideoOrigin?) {
        let resolve = failed?.renew ?? origin
        Task {
            do {
                let origin = try await resolve()
                queue.async { self.start(request, origin: origin, retried: failed != nil) }
            } catch {
                queue.async { Self.finish(request, error: error) }
            }
        }
    }

    private func start(_ request: AVAssetResourceLoadingRequest, origin: VideoOrigin, retried: Bool) {
        guard !request.isCancelled, !request.isFinished else { return }
        var urlRequest = URLRequest(url: origin.url)
        for (name, value) in origin.headers {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        var offset: Int64 = 0
        var remaining: Int64?
        if let data = request.dataRequest {
            offset = data.requestedOffset
            let end = data.requestsAllDataToEndOfResource ? "" : String(offset + Int64(data.requestedLength) - 1)
            urlRequest.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
            if !data.requestsAllDataToEndOfResource { remaining = Int64(data.requestedLength) }
        }
        let task = session.dataTask(with: urlRequest)
        pending[task.taskIdentifier] = Pending(task: task, request: request, origin: origin, retried: retried, skip: offset, remaining: remaining)
        task.resume()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Solo dove l'origine ammette: altrove il 3xx arriva come risposta e il video fallisce.
        guard let item = pending[task.taskIdentifier], let url = request.url, item.origin.allowRedirect(url) else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard var item = pending[dataTask.taskIdentifier], let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            return
        }
        if (http.statusCode == 401 || http.statusCode == 403), !item.retried, item.origin.renew != nil {
            // Token o indirizzo scaduti durante il video: si rinnovano una volta.
            pending[dataTask.taskIdentifier] = nil
            completionHandler(.cancel)
            load(item.request, renewing: item.origin)
            return
        }
        guard (200..<300).contains(http.statusCode) else {
            pending[dataTask.taskIdentifier] = nil
            completionHandler(.cancel)
            Self.finish(item.request, error: URLError(.badServerResponse))
            return
        }
        // 206: i byte partono già dal punto chiesto.
        if http.statusCode == 206 { item.skip = 0 }
        pending[dataTask.taskIdentifier] = item
        if let info = item.request.contentInformationRequest {
            info.contentType = contentType(http)
            info.contentLength = Self.totalLength(http) ?? http.expectedContentLength
            info.isByteRangeAccessSupported = http.statusCode == 206
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard var item = pending[dataTask.taskIdentifier] else { return }
        var chunk = data
        if item.skip > 0 {
            let dropped = min(Int64(chunk.count), item.skip)
            chunk = chunk.dropFirst(Int(dropped))
            item.skip -= dropped
            pending[dataTask.taskIdentifier] = item
        }
        // Un server che ignora Range manda il file intero: si consegna solo il tratto chiesto e si
        // chiude la connessione, invece di scaricare tutto il video a ogni richiesta parziale.
        if let remaining = item.remaining {
            if Int64(chunk.count) > remaining { chunk = chunk.prefix(Int(remaining)) }
            item.remaining = remaining - Int64(chunk.count)
            pending[dataTask.taskIdentifier] = item
        }
        if !chunk.isEmpty { item.request.dataRequest?.respond(with: chunk) }
        if item.remaining == 0 {
            pending[dataTask.taskIdentifier] = nil
            dataTask.cancel()
            Self.finish(item.request, error: nil)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let item = pending.removeValue(forKey: task.taskIdentifier) else { return }
        Self.finish(item.request, error: error)
    }

    private static func finish(_ request: AVAssetResourceLoadingRequest, error: Error?) {
        guard !request.isCancelled, !request.isFinished else { return }
        if let error { request.finishLoading(with: error) } else { request.finishLoading() }
    }

    /// Tipo del file dal Content-Type del server, altrimenti dall'estensione; MP4 in mancanza d'altro.
    private func contentType(_ response: HTTPURLResponse) -> String {
        if let mime = response.mimeType, mime.lowercased().hasPrefix("video/"), let type = UTType(mimeType: mime) {
            return type.identifier
        }
        if !fileExtension.isEmpty, let type = UTType(filenameExtension: fileExtension), type.conforms(to: .movie) {
            return type.identifier
        }
        return UTType.mpeg4Movie.identifier
    }

    /// Lunghezza del file da "Content-Range: bytes 0-1/123456".
    private static func totalLength(_ response: HTTPURLResponse) -> Int64? {
        guard let range = response.value(forHTTPHeaderField: "Content-Range"),
              let total = range.split(separator: "/").last else { return nil }
        return Int64(total.trimmingCharacters(in: .whitespaces))
    }
}

/// Un video dello slideshow: lettore, fine del video e video che il dispositivo non sa riprodurre.
@MainActor
@Observable
final class VideoPlayback {
    let player = AVPlayer()
    private(set) var failed = false
    @ObservationIgnored var onEnded: () -> Void = {}
    @ObservationIgnored var loop = false
    @ObservationIgnored private var loader: StreamingVideoLoader?
    @ObservationIgnored private var loading: Task<Void, Never>?
    @ObservationIgnored private var playRequested = false
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?

    init(photo: Photo) {
        player.actionAtItemEnd = .pause
        switch photo.source {
        case let .google(mediaID):
            let loader = StreamingVideoLoader.google(mediaID: mediaID, stream: GoogleServices.shared.stream)
            self.loader = loader
            attach(AVPlayerItem(asset: loader.makeAsset()))
        case let .library(localIdentifier):
            // PhotoKit prepara il video (da iCloud, se serve) e poi lo dà al lettore.
            loading = Task { [weak self] in
                let item = await PhotoLibrary.playerItem(localIdentifier: localIdentifier)
                guard let self, !Task.isCancelled else { return }
                if let item { self.attach(item) } else { self.failed = true }
            }
        case let .network(address):
            // Video del server di casa, letto da qui con le richieste Range: AVPlayer da solo
            // seguirebbe un reindirizzamento anche fuori dalla rete di casa.
            guard let url = URL(string: address), LANAddress.isLocal(url) else {
                failed = true
                return
            }
            let loader = StreamingVideoLoader.lan(url: url)
            self.loader = loader
            attach(AVPlayerItem(asset: loader.makeAsset()))
        case .bundled, .guest:
            failed = true
        }
    }

    private func attach(_ item: AVPlayerItem) {
        player.replaceCurrentItem(with: item)
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        }
        statusObservation = item.observe(\.status) { [weak self] item, _ in
            guard item.status == .failed else { return }
            DispatchQueue.main.async { self?.failed = true }
        }
        if playRequested { player.play() }
    }

    /// Avvia o mette in pausa; un video ancora in preparazione parte appena è pronto.
    func setPlaying(_ playing: Bool) {
        playRequested = playing
        if playing { player.play() } else { player.pause() }
    }

    private func ended() {
        if loop {
            player.seek(to: .zero)
            player.play()
        } else {
            onEnded()
        }
    }

    /// Ferma il video e chiude le connessioni: va chiamato quando il video esce di scena.
    func stop() {
        loading?.cancel()
        loading = nil
        playRequested = false
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        statusObservation = nil
        loader?.invalidate()
        loader = nil
    }
}

/// Vista del video senza controlli: li dà lo slideshow.
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    let fill: Bool

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .black
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        view.playerLayer.player = player
        view.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }
}

/// Video dello slideshow, dall'inizio alla fine; con `loop` (unico elemento) ricomincia da capo.
/// Un video che il dispositivo non sa riprodurre mostra un avviso e poi si salta.
struct VideoSlideView: View {
    let photo: Photo
    let settings: SlideshowSettings
    let playing: Bool
    let loop: Bool
    let onEnded: () -> Void

    @State private var playback: VideoPlayback?

    var body: some View {
        ZStack {
            Color.black
            if let playback {
                PlayerLayerView(player: playback.player, fill: settings.fitMode == .crop)
                if playback.failed {
                    Text(L("video.unsupported"))
                        .font(.headline)
                        .padding(.horizontal, 32)
                        .padding(.vertical, 18)
                        .background(.regularMaterial, in: Capsule())
                        .task(id: playing) {
                            // In pausa non si passa oltre: il timer riparte quando la riproduzione riprende.
                            guard playing else { return }
                            try? await Task.sleep(nanoseconds: 2_500_000_000)
                            guard !Task.isCancelled else { return }
                            onEnded()
                        }
                }
            }
        }
        .onAppear {
            let playback = VideoPlayback(photo: photo)
            playback.onEnded = onEnded
            playback.loop = loop
            playback.player.isMuted = !settings.videoSound
            playback.setPlaying(playing)
            self.playback = playback
        }
        .onDisappear {
            playback?.stop()
            playback = nil
        }
        .onChange(of: playing) { _, nowPlaying in
            playback?.setPlaying(nowPlaying)
        }
        .onChange(of: settings.videoSound) { _, sound in playback?.player.isMuted = !sound }
        .onChange(of: loop) { _, value in playback?.loop = value }
    }
}
