import Foundation

/// Come la Apple TV ottiene il codice di autorizzazione di Google: in produzione dal telefono, con
/// la pagina servita sulla rete di casa (`LANPhoneConsent`); nei test da un oggetto finto.
public protocol PhoneConsent: AnyObject, Sendable {
    /// Mostra al telefono la strada verso Google e aspetta il codice. `authorizationURL` costruisce
    /// l'indirizzo di Google dato quello di ritorno sulla TV; `onStep` riceve l'indirizzo da mettere nel QR.
    func obtainCode(
        authorizationURL: @escaping @Sendable (String) -> String,
        onStep: @escaping @Sendable (ImportStep) -> Void
    ) async throws -> String

    /// Dopo la sessione Picker: la pagina sul telefono offre il pulsante per scegliere le foto.
    func publishPickerURL(_ url: String)

    func close()
}

/// Consenso dal telefono attraverso la pagina che la Apple TV serve sulla rete di casa.
public final class LANPhoneConsent: PhoneConsent, @unchecked Sendable {
    /// Quanto resta aperta la pagina: il consenso su Google richiede un minuto o due.
    public static let expiresIn = 10 * 60

    private let page: @Sendable () -> String
    private let address: @Sendable () -> String?
    private let lock = NSLock()
    private var pages: GoogleConsentPages?

    /// - Parameters:
    ///   - page: `shared/assets/guest/google.html`, dal bundle dell'app.
    ///   - address: indirizzo della Apple TV sulla rete di casa; nil se non è in rete.
    public init(page: @escaping @Sendable () -> String, address: @escaping @Sendable () -> String? = { LocalNetwork.currentIPv4() }) {
        self.page = page
        self.address = address
    }

    public func obtainCode(
        authorizationURL: @escaping @Sendable (String) -> String,
        onStep: @escaping @Sendable (ImportStep) -> Void
    ) async throws -> String {
        guard let host = address() else { throw FotoFestaError.noNetwork }
        let box = CodeBox()
        let pages = GoogleConsentPages(
            key: Self.randomKey(),
            page: page,
            authorizationURL: authorizationURL,
            onCode: { box.deliver(.success($0)) },
            onError: { box.deliver(.failure($0 == "access_denied" ? FotoFestaError.accessDenied : FotoFestaError.consentFailed)) }
        )
        let url = try await pages.start(host: host)
        lock.lock()
        self.pages = pages
        lock.unlock()
        onStep(.waitingForAuthorization(pageURL: url, expiresIn: Self.expiresIn))
        return try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask { try await box.value() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(Self.expiresIn) * 1_000_000_000)
                throw FotoFestaError.codeExpired
            }
            guard let first = try await group.next() else { throw FotoFestaError.codeExpired }
            group.cancelAll()
            return first
        }
    }

    public func publishPickerURL(_ url: String) {
        lock.lock(); defer { lock.unlock() }
        pages?.pickerURL = url
    }

    public func close() {
        lock.lock()
        let pages = self.pages
        self.pages = nil
        lock.unlock()
        pages?.stop()
    }

    private static func randomKey() -> String {
        (0..<16).map { _ in String(format: "%02x", UInt8.random(in: .min ... .max)) }.joined()
    }
}

/// Un valore consegnato una volta sola da un altro thread a chi lo aspetta.
private final class CodeBox: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<String, Error>?
    private var continuation: CheckedContinuation<String, Error>?

    func deliver(_ value: Result<String, Error>) {
        lock.lock()
        guard result == nil else { lock.unlock(); return }
        result = value
        let waiting = continuation
        continuation = nil
        lock.unlock()
        waiting?.resume(with: value)
    }

    func value() async throws -> String {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if let result {
                    lock.unlock()
                    continuation.resume(with: result)
                } else {
                    self.continuation = continuation
                    lock.unlock()
                }
            }
        } onCancel: {
            deliver(.failure(CancellationError()))
        }
    }
}

/// Pagina che la Apple TV serve al telefono per autorizzare Google Foto (`shared/assets/guest/google.html`),
/// con gli stessi percorsi dell'app Android:
/// - `GET /?k=KEY`: la pagina, che chiede a `/start` l'indirizzo di Google e mostra il pulsante;
/// - `GET /start?k=KEY`: `{"url": …}`, l'indirizzo di Google con il ritorno a questa TV nello `state`;
/// - `GET /google?k=KEY&code=…` (o `&error=…`): ritorno dal sito; il codice va a `onCode` e il
///   browser viene rimandato alla pagina (`#done` o `#cancelled`), così il codice sparisce dall'indirizzo;
/// - `GET /status?k=KEY`: `{"picker": …}` appena la TV ha la sessione Picker.
final class GoogleConsentPages: @unchecked Sendable {
    static let expiredPage = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1"><title>FotoFesta</title></head>
    <body style="font:18px/1.5 system-ui,sans-serif;margin:32px 20px;background:#0b1222;color:#eef2fa">
    <h1 style="font-size:1.4rem">FotoFesta</h1>
    <p>Questo link non è più valido: inquadra di nuovo il QR sulla TV.</p>
    <p>This link is no longer valid: scan the QR code on the TV again.</p>
    </body></html>
    """

    private let key: String
    private let page: @Sendable () -> String
    private let authorizationURL: @Sendable (String) -> String
    private let onCode: @Sendable (String) -> Void
    private let onError: @Sendable (String) -> Void
    private let lock = NSLock()
    private var delivered = false
    private var googleURL = ""
    private var picker: String?
    private var server: PhonePageServer!

    private(set) var pageURL = ""

    var pickerURL: String? {
        get { lock.lock(); defer { lock.unlock() }; return picker }
        set { lock.lock(); picker = newValue; lock.unlock() }
    }

    init(
        key: String,
        page: @escaping @Sendable () -> String,
        authorizationURL: @escaping @Sendable (String) -> String,
        onCode: @escaping @Sendable (String) -> Void,
        onError: @escaping @Sendable (String) -> Void
    ) {
        self.key = key
        self.page = page
        self.authorizationURL = authorizationURL
        self.onCode = onCode
        self.onError = onError
        server = PhonePageServer(maxBodyBytes: 1_024, expiredPage: Self.expiredPage) { [weak self] request, _ in
            self?.respond(request) ?? .html(403, GoogleConsentPages.expiredPage)
        }
    }

    /// Avvia la pagina su `host` e restituisce il suo indirizzo, da mettere nel QR.
    func start(host: String) async throws -> String {
        let port = try await server.start(host: host)
        let hostHeader = GuestUploadServer.hostHeader(host: host, port: port)
        lock.lock()
        pageURL = "http://\(hostHeader)/?k=\(key)"
        googleURL = authorizationURL("http://\(hostHeader)/google?k=\(key)")
        let url = pageURL
        lock.unlock()
        return url
    }

    func stop() {
        server.stop()
    }

    private func respond(_ request: GuestHTTPRequest) -> GuestHTTPResponse {
        guard request.method == "GET" else { return .html(405, Self.expiredPage) }
        guard let candidate = request.query["k"], Self.constantTimeEquals(candidate, key) else { return .html(403, Self.expiredPage) }
        switch request.path {
        case "/":
            return .html(200, page())
        case "/start":
            lock.lock(); let url = googleURL; lock.unlock()
            return .json(200, Self.json(["url": url]))
        case "/status":
            return .json(200, pickerURL.map { Self.json(["picker": $0]) } ?? "{}")
        case "/google":
            let code = request.query["code"].flatMap { $0.removingPercentEncoding } ?? ""
            let error = request.query["error"].flatMap { $0.removingPercentEncoding } ?? ""
            if !code.isEmpty {
                // Un ritorno ripetuto (ricarica della pagina) non consegna due volte lo stesso codice.
                if claimDelivery() { onCode(code) }
                return .redirect("/?k=\(key)#done")
            }
            if !error.isEmpty {
                if claimDelivery() { onError(error) }
                return .redirect("/?k=\(key)#cancelled")
            }
            return .html(400, Self.expiredPage)
        default:
            return .html(404, Self.expiredPage)
        }
    }

    private func claimDelivery() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if delivered { return false }
        delivered = true
        return true
    }

    private static func json(_ object: [String: String]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes, .sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    private static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let left = Array(a.utf8), right = Array(b.utf8)
        guard left.count == right.count else { return false }
        var difference: UInt8 = 0
        for (x, y) in zip(left, right) { difference |= x ^ y }
        return difference == 0
    }
}

extension GuestHTTPResponse {
    /// Rimanda il browser a `location` (303: anche dopo un POST si passa a un GET).
    static func redirect(_ location: String) -> GuestHTTPResponse {
        GuestHTTPResponse(status: 303, contentType: "text/plain; charset=utf-8", body: Data(), extraHeaders: [("Location", location)])
    }
}
