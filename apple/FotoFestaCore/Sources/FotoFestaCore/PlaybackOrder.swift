import Foundation

/// Ordine di riproduzione dello slideshow, come nell'app Android.
///
/// In modalità casuale usa una permutazione completa: ogni foto compare una volta per giro,
/// "Precedente" torna alla foto vista prima e a fine giro la sequenza viene rimescolata
/// evitando di ripetere subito l'ultima foto mostrata.
public final class PlaybackOrder {
    public typealias Shuffler = ([Int]) -> [Int]

    private let shuffler: Shuffler
    private var order: [Int] = []
    private var shuffle = false

    public init(shuffler: @escaping Shuffler = { $0.shuffled() }) {
        self.shuffler = shuffler
    }

    public func reset(count: Int, shuffle: Bool, current: Int = 0) {
        self.shuffle = shuffle
        guard count > 0 else {
            order = []
            return
        }
        guard shuffle else {
            order = Array(0..<count)
            return
        }
        let first = min(max(current, 0), count - 1)
        order = [first] + shuffler((0..<count).filter { $0 != first })
    }

    public func next(after current: Int) -> Int {
        guard order.count > 1 else { return order.first ?? 0 }
        let position = position(of: current)
        if position == order.count - 1 && shuffle {
            reshuffle(avoiding: current)
            return order[0]
        }
        return order[(position + 1) % order.count]
    }

    public func previous(before current: Int) -> Int {
        guard order.count > 1 else { return order.first ?? 0 }
        let position = position(of: current)
        return order[(position - 1 + order.count) % order.count]
    }

    /// Foto successiva prevista, senza modificare l'ordine (per il precaricamento).
    public func peekNext(after current: Int) -> Int? {
        guard order.count > 1, let position = order.firstIndex(of: current) else { return nil }
        if position == order.count - 1 && shuffle { return nil }
        return order[(position + 1) % order.count]
    }

    private func position(of current: Int) -> Int {
        if let position = order.firstIndex(of: current) { return position }
        reset(count: order.count, shuffle: shuffle, current: current)
        return order.firstIndex(of: current) ?? 0
    }

    private func reshuffle(avoiding last: Int) {
        let shuffled = shuffler(order)
        if shuffled.first == last, shuffled.count > 1 {
            order = Array(shuffled.dropFirst()) + [last]
        } else {
            order = shuffled
        }
    }
}
