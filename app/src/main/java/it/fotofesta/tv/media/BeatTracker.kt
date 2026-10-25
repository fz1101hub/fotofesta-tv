package it.fotofesta.tv.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** Battito di un brano: battiti al minuto e istante del primo battito dall'inizio del brano. */
data class Beat(val bpm: Float, val offsetMs: Long) {
    val periodMs: Float get() = 60_000f / bpm
}

/**
 * Calcoli sul ritmo, senza Android: la stima del tempo dall'inviluppo degli attacchi e i tempi di
 * attesa fino al prossimo battito (o al prossimo inizio di battuta) mentre il brano suona.
 */
object BeatMath {
    const val MIN_BPM = 70f
    const val MAX_BPM = 180f

    /**
     * Forza degli attacchi da un'energia per finestra: crescita del logaritmo dell'energia, solo in
     * salita (una nota che parte, non una che si spegne).
     */
    fun onsets(energy: FloatArray): FloatArray {
        val out = FloatArray(energy.size)
        var previous = ln(1f + energy.firstOrNull().orZero())
        for (i in 1 until energy.size) {
            val current = ln(1f + energy[i])
            out[i] = max(0f, current - previous)
            previous = current
        }
        return out
    }

    /**
     * Tempo dall'autocorrelazione degli attacchi ([hopMs] millisecondi tra un valore e il
     * successivo). Tra due periodi quasi equivalenti si preferisce quello vicino a 120 BPM, il
     * ritmo da festa più comune. Poi la fase: lo scarto che fa cadere più attacchi sui battiti.
     */
    fun tempo(onsets: FloatArray, hopMs: Float): Beat? {
        if (onsets.size < 64 || hopMs <= 0f) return null
        val minLag = (60_000f / MAX_BPM / hopMs).roundToInt().coerceAtLeast(1)
        val maxLag = (60_000f / MIN_BPM / hopMs).roundToInt()
        if (maxLag >= onsets.size / 2) return null
        val mean = onsets.average().toFloat()
        val centered = FloatArray(onsets.size) { onsets[it] - mean }
        var bestLag = -1
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in 0 until centered.size - lag) sum += centered[i] * centered[i + lag]
            val bpm = 60_000f / (lag * hopMs)
            // Peso dolce attorno a 120 BPM: evita di scegliere la metà o il doppio del tempo.
            val weight = 1f / (1f + abs(ln(bpm / 120f)) * 1.5f)
            val score = sum / (centered.size - lag) * weight
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null
        var bestPhase = 0
        var phaseScore = -1f
        for (phase in 0 until bestLag) {
            var sum = 0f
            var i = phase
            while (i < onsets.size) {
                sum += onsets[i]
                i += bestLag
            }
            if (sum > phaseScore) {
                phaseScore = sum
                bestPhase = phase
            }
        }
        return Beat(60_000f / (bestLag * hopMs), (bestPhase * hopMs).toLong())
    }

    /**
     * Millisecondi da aspettare, a brano in posizione [positionMs], per arrivare al primo
     * battito multiplo di [every] (4 = inizio di battuta) che cade dopo almeno [minMs].
     */
    fun delayToBeat(positionMs: Long, beat: Beat, minMs: Long, every: Int = 1): Long {
        val period = beat.periodMs * every.coerceAtLeast(1)
        val target = positionMs + minMs
        val sinceFirst = target - beat.offsetMs
        val count = if (sinceFirst <= 0) 0.0 else kotlin.math.ceil(sinceFirst / period.toDouble())
        val beatAt = beat.offsetMs + count * period
        return (beatAt - positionMs).toLong().coerceAtLeast(minMs)
    }

    private fun Float?.orZero() = this ?: 0f
}

/**
 * Analisi dei brani della musica di sottofondo: decodifica i primi [ANALYSIS_SECONDS] secondi,
 * calcola l'energia ogni [HOP_SAMPLES] campioni e ne ricava il battito. Il risultato resta in
 * memoria per brano; un brano che non si decodifica non si riprova.
 */
class BeatAnalyzer(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Beat>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun cached(uri: String): Beat? = cache[uri]

    fun known(uri: String): Boolean = cache.containsKey(uri) || failed.contains(uri)

    /** Da chiamare fuori dal thread principale: per un brano lungo bastano pochi secondi. */
    fun analyze(uri: String): Beat? {
        cache[uri]?.let { return it }
        if (failed.contains(uri)) return null
        val beat = runCatching { decode(uri) }.onFailure { Log.w(TAG, "Analisi del ritmo non riuscita", it) }.getOrNull()
        if (beat != null) cache[uri] = beat else failed += uri
        return beat
    }

    private fun decode(uri: String): Beat? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val energy = ArrayList<Float>()
            var window = 0f
            var inWindow = 0
            var decodedFrames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > ANALYSIS_SECONDS * 1_000_000L) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)
                        if (buffer != null && info.size > 0) {
                            // PCM a 16 bit: si somma l'energia dei canali per finestra.
                            val samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            samples.position(info.offset / 2)
                            samples.limit((info.offset + info.size) / 2)
                            while (samples.remaining() >= channels) {
                                var frame = 0f
                                repeat(channels) { frame += samples.get() / 32768f }
                                frame /= channels
                                window += frame * frame
                                inWindow++
                                decodedFrames++
                                if (inWindow == HOP_SAMPLES) {
                                    energy += window
                                    window = 0f
                                    inWindow = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (decodedFrames > ANALYSIS_SECONDS.toLong() * sampleRate) outputDone = true
                    }
                }
            }
            val hopMs = HOP_SAMPLES * 1000f / sampleRate
            return BeatMath.tempo(BeatMath.onsets(energy.toFloatArray()), hopMs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "BeatAnalyzer"
        const val ANALYSIS_SECONDS = 45
        const val HOP_SAMPLES = 512
        const val TIMEOUT_US = 10_000L
    }
}
