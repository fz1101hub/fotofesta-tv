import AVFoundation
import FotoFestaCore

/// Carillon per "La festa suona": le note si generano al volo (seno con un armonico e una coda che
/// si spegne), nessun file audio.
@MainActor
final class PartySynth {
    static let shared = PartySynth()
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var connected = false
    private static let sampleRate = 44_100.0

    func play(_ notes: [PartyGames.Note]) {
        stop()
        guard let format = AVAudioFormat(standardFormatWithSampleRate: Self.sampleRate, channels: 1) else { return }
        let lengths = notes.map { Int(Double($0.milliseconds) / 1000 * Self.sampleRate) }
        let total = lengths.reduce(0, +)
        guard total > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(total)),
              let samples = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(total)
        var offset = 0
        for (note, count) in zip(notes, lengths) {
            let step = 2 * Double.pi * note.frequency / Self.sampleRate
            for i in 0..<count {
                let time = Double(i) / Self.sampleRate
                let envelope = exp(-time * 6) * min(time * 400, 1)
                let value = sin(step * Double(i)) * 0.7 + sin(2 * step * Double(i)) * 0.2
                samples[offset + i] = Float(value * envelope * 0.35)
            }
            offset += count
        }
        if !connected {
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            connected = true
        }
        do { try engine.start() } catch { return }
        player.scheduleBuffer(buffer, completionHandler: nil)
        player.play()
    }

    func stop() {
        player.stop()
        engine.stop()
    }
}
