package it.fotofesta.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Fanfara di benvenuto, generata al momento (nessun file audio): do–mi–sol–do acuto, breve e
 * allegra. Non chiede il fuoco audio: si sovrappone alla musica per un secondo e mezzo.
 */
object Fanfare {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fanfare").apply { isDaemon = true } }
    private val samples: ShortArray by lazy(::compose)

    fun play() = worker.execute {
        runCatching {
            val bytes = samples.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(samples.size * 1000L / RATE + 100)
            track.release()
        }.onFailure { Log.w("Fanfare", "Fanfara non riprodotta", it) }
    }

    /** Note con un piccolo attacco e una coda morbida; l'ultima più lunga. */
    internal fun compose(): ShortArray {
        val notes = listOf(523.25 to 0.14, 659.25 to 0.14, 783.99 to 0.14, 1046.5 to 0.55)
        val out = ShortArray(notes.sumOf { (it.second * RATE).toInt() })
        var offset = 0
        notes.forEach { (frequency, seconds) ->
            val length = (seconds * RATE).toInt()
            for (i in 0 until length) {
                val t = i.toDouble() / RATE
                val envelope = min(1.0, i / (0.01 * RATE)) * (1 - i.toDouble() / length)
                // Fondamentale più un'armonica: suono di ottone giocattolo, non un bip.
                val wave = sin(2 * PI * frequency * t) * 0.7 + sin(4 * PI * frequency * t) * 0.3
                out[offset + i] = (wave * envelope * VOLUME * Short.MAX_VALUE).toInt().toShort()
            }
            offset += length
        }
        return out
    }

    private const val RATE = 22_050
    private const val VOLUME = 0.45
}
