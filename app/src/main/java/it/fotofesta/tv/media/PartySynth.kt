package it.fotofesta.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import it.fotofesta.tv.model.PartyGames
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Piccolo sintetizzatore per "La festa suona": una nota per foto, con un suono di carillon
 * (fondamentale, ottava e un po' di quinta, attacco rapido e coda lunga).
 */
object PartySynth {
    private const val RATE = 22_050
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "party-synth").apply { isDaemon = true } }
    @Volatile private var current: Future<*>? = null
    @Volatile private var track: AudioTrack? = null

    fun play(notes: List<PartyGames.Note>) {
        stop()
        if (notes.isEmpty()) return
        current = worker.submit {
            runCatching {
                val samples = render(notes)
                val created = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                track = created
                created.write(samples, 0, samples.size)
                created.play()
            }.onFailure { Log.w("PartySynth", "Note non riprodotte", it) }
        }
    }

    fun stop() {
        current?.cancel(true)
        current = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    internal fun render(notes: List<PartyGames.Note>): ShortArray {
        val total = notes.sumOf { it.millis } * RATE / 1000 + RATE / 2
        val mix = FloatArray(total)
        var start = 0
        notes.forEach { note ->
            val length = note.millis * RATE / 1000
            // La coda di ogni nota si sovrappone un po' alla successiva, come un carillon.
            val ring = min(length * 2, total - start)
            for (i in 0 until ring) {
                val t = i.toDouble() / RATE
                val envelope = min(1.0, i / (0.005 * RATE)) * kotlin.math.exp(-3.2 * i / ring)
                val tone = sin(2 * PI * note.frequency * t) + 0.4 * sin(4 * PI * note.frequency * t) + 0.15 * sin(3 * PI * note.frequency * t)
                mix[start + i] += (tone * envelope * 0.28).toFloat()
            }
            start += length
        }
        return ShortArray(total) { (mix[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }
}
