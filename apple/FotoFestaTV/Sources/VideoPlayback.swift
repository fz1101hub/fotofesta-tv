import AVFoundation
import FotoFestaCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Legge i video di Google Foto per AVPlayer. L'indirizzo del video vuole il token dell'utente in
/// un'intestazione, che AVPlayer non permette di aggiungere: i byte passano da qui, a pezzi, con le
/// richieste Range che AVPlayer fa per spostarsi nel file. Nulla viene salvato sul dispositivo.
final class GoogleVideoLoader: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate, @unchecked Sendable {
    private static let scheme = "fotofesta-google"

    private let mediaID: String
    private let stream: GooglePhotosStream
    private let queue = DispatchQueue(label: "it.fotofesta.video-loader")
    /// Tiene il caricatore finché `invalidate()` non la chiude.
    private var session: URLSession!

    /// Richiesta di AVPlayer in corso. `skip`: byte da scartare se il server risponde con il file
    /// intero invece che dal punto chiesto.
    private struct Pending {
        let task: URLSessionDataTask
        let request: AVAssetResourceLoadingRequest
        let access: GooglePhotosStream.Access
        let retried: Bool
        var skip: Int64
    }

    // Usato solo sulla coda `queue`.
    private var pending: [Int: Pending] = [:]

    init(mediaID: String, stream: GooglePhotosStream) {
        self.mediaID = mediaID
        self.stream = stream
        super.init()
        let operations = OperationQueue()
        operations.underlyingQueue = queue
        operations.maxConcurrentOperationCount = 1
        session = URLSession(configuration: .default, delegate: self, delegateQueue: operations)
    }

    /// Asset per AVPlayer. L'asset tiene il caricatore solo debolmente: va tenuto finché il video serve.
    func makeAsset() -> AVURLAsset {
        var components = URLComponents()
        components.scheme = Self.scheme
        components.host = "media"
        components.path = "/" + mediaID
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
        load(loadingRequest, failed: nil)
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        for (id, item) in pending where item.request === loadingRequest {
            item.task.cancel()
            pending[id] = nil
        }
    }

    /// Chiede a GooglePhotosStream indirizzo e token validi; con `failed` li rinnova (token scaduto).
    private func load(_ request: AVAssetResourceLoadingRequest, failed: GooglePhotosStream.Access?) {
        let stream = self.stream
        let mediaID = self.mediaID
        Task {
            do {
                let access = try await stream.access(mediaID: mediaID, failed: failed)
                queue.async { self.start(request, access: access, retried: failed != nil) }
            } catch {
                queue.async { Self.finish(request, error: error) }
            }
        }
    }

    private func start(_ request: AVAssetResourceLoadingRequest, access: GooglePhotosStream.Access, retried: Bool) {
        guard !request.isCancelled, !request.isFinished else { return }
        // "=dv" chiede il file del video; senza, Google manderebbe un fotogramma.
        guard let url = URL(string: access.baseURL + "=dv") else {
            Self.finish(request, error: URLError(.badURL))
            return
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.setValue("Bearer \(access.accessToken)", forHTTPHeaderField: "Authorization")
        var offset: Int64 = 0
        if let data = request.dataRequest {
            offset = data.requestedOffset
            let end = data.requestsAllDataToEndOfResource ? "" : String(offset + Int64(data.requestedLength) - 1)
            urlRequest.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
        }
        let task = session.dataTask(with: urlRequest)
        pending[task.taskIdentifier] = Pending(task: task, request: request, access: access, retried: retried, skip: offset)
        task.resume()
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
        if (http.statusCode == 401 || http.statusCode == 403), !item.retried {
            // Token o indirizzo scaduti durante il video: si rinnovano una volta.
            pending[dataTask.taskIdentifier] = nil
            completionHandler(.cancel)
            load(item.request, failed: item.access)
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
            info.contentType = UTType(mimeType: http.mimeType ?? "video/mp4")?.identifier ?? UTType.mpeg4Movie.identifier
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
        if !chunk.isEmpty { item.request.dataRequest?.respond(with: chunk) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let item = pending.removeValue(forKey: task.taskIdentifier) else { return }
        Self.finish(item.request, error: error)
    }

    private static func finish(_ request: AVAssetResourceLoadingRequest, error: Error?) {
        guard !request.isCancelled, !request.isFinished else { return }
        if let error { request.finishLoading(with: error) } else { request.finishLoading() }
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
    @ObservationIgnored private var loader: GoogleVideoLoader?
    @ObservationIgnored private var loading: Task<Void, Never>?
    @ObservationIgnored private var playRequested = false
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?

    init(photo: Photo) {
        player.actionAtItemEnd = .pause
        switch photo.source {
        case let .google(mediaID):
            let loader = GoogleVideoLoader(mediaID: mediaID, stream: GoogleServices.shared.stream)
            self.loader = loader
            attach(AVPlayerItem(asset: loader.makeAsset()))
        case let .library(localIdentifier):
            // PhotoKit prepara il video (da iCloud, se serve) e poi lo dà al lettore.
            loading = Task { [weak self] in
                let item = await PhotoLibrary.playerItem(localIdentifier: localIdentifier)
                guard let self, !Task.isCancelled else { return }
                if let item { self.attach(item) } else { self.failed = true }
            }
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
                        .task {
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
