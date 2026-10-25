import Foundation

/// Muro di telefoni: gli invitati appoggiano i telefoni uno accanto all'altro sul tavolo e ognuno
/// mostra un pezzo della stessa foto, come un maxischermo. Prima i telefoni si mettono in fila
/// (ognuno riceve il suo numero), poi la TV manda la foto. Si usano solo foto approvate degli
/// invitati, che i telefoni scaricano dalla parete con la chiave della festa. Come `PhoneWall.kt`.
///
/// Thread-safe: il server lo usa dalla sua coda, l'interfaccia dal thread principale.
public final class PhoneWall: @unchecked Sendable {
    public enum Phase: String, Sendable {
        case joining, showing
    }

    public struct State: Equatable, Sendable {
        public let phase: Phase
        public let players: [String]
        public let columns: Int
        public let rows: Int
        public let photoID: String?
    }

    public static let maxPhones = 12

    private let lock = NSLock()
    private var phase: Phase?
    private var players: [String] = []
    private var photoID: String?
    /// Cresce a ogni foto: i telefoni ridisegnano anche se la foto è la stessa di prima.
    private var serial = 0
    /// Cresce a ogni gran finale: i telefoni del muro esplodono in coriandoli tutti insieme.
    private var finaleSerial = 0
    private var revision = 0

    public init() {}

    public func start() {
        lock.withLock {
            phase = .joining
            players = []
            photoID = nil
            serial += 1
            revision += 1
        }
    }

    /// Manda `id` a tutti i telefoni in fila; false se il muro è spento o non c'è nessuno.
    @discardableResult
    public func show(_ id: String) -> Bool {
        lock.withLock {
            guard phase != nil, !players.isEmpty else { return false }
            phase = .showing
            photoID = id
            serial += 1
            revision += 1
            return true
        }
    }

    /// Di nuovo in fila: per cambiare la disposizione o far entrare altri telefoni.
    public func rearrange() {
        lock.withLock {
            guard phase != nil else { return }
            phase = .joining
            photoID = nil
            serial += 1
            revision += 1
        }
    }

    /// Gran finale: coriandoli e lampi su tutti i telefoni del muro.
    public func finale() {
        lock.withLock {
            guard phase == .showing else { return }
            finaleSerial += 1
            revision += 1
        }
    }

    public func stop() {
        lock.withLock {
            phase = nil
            players = []
            photoID = nil
            revision += 1
        }
    }

    /// Cambia a ogni modifica: l'interfaccia lo confronta per aggiornarsi.
    public func currentRevision() -> Int { lock.withLock { revision } }

    public func state() -> State? {
        lock.withLock {
            guard let phase else { return nil }
            let (columns, rows) = Self.grid(players.count)
            return State(phase: phase, players: players, columns: columns, rows: rows, photoID: photoID)
        }
    }

    /// Stato per la pagina di un telefono; "null" se il muro è spento.
    public func phoneJSON(player: String?) -> String {
        lock.withLock {
            guard let phase else { return "null" }
            let (columns, rows) = Self.grid(players.count)
            let slot = player.flatMap { players.firstIndex(of: $0) }.map(String.init) ?? "null"
            let photo = photoID.map(PartyHub.json) ?? "null"
            return #"{"phase":"\#(phase.rawValue)","slot":\#(slot),"count":\#(players.count),"columns":\#(columns),"rows":\#(rows),"photo":\#(photo),"serial":\#(serial),"finale":\#(finaleSerial)}"#
        }
    }

    /// Richiesta "type=wall&command=join|leave" di un telefono: stato HTTP e risposta JSON.
    public func handle(_ form: [String: String]) -> (status: Int, json: String) {
        guard let player = form["player"], PhoneGame.isValidPlayer(player) else { return (400, #"{"error":"invalid"}"#) }
        return lock.withLock {
            guard phase != nil else { return (403, #"{"error":"disabled"}"#) }
            switch form["command"] {
            case "leave":
                players.removeAll { $0 == player }
                revision += 1
                return (200, #"{"status":"ok"}"#)
            case "join":
                if players.contains(player) { return (200, #"{"status":"ok"}"#) }
                // A foto già mandata la disposizione è fissata: si entra al prossimo giro.
                if phase == .showing { return (409, #"{"error":"showing"}"#) }
                guard players.count < Self.maxPhones else { return (507, #"{"error":"full"}"#) }
                players.append(player)
                revision += 1
                return (200, #"{"status":"ok"}"#)
            default:
                return (400, #"{"error":"invalid"}"#)
            }
        }
    }

    /// Colonne e righe per `count` telefoni in verticale: fino a quattro in fila, poi due o tre file.
    /// Le foto sono quasi sempre orizzontali, quindi si preferisce allargare.
    public static func grid(_ count: Int) -> (columns: Int, rows: Int) {
        let rows = count <= 4 ? 1 : (count <= 8 ? 2 : 3)
        return ((max(count, 1) + rows - 1) / rows, rows)
    }
}
