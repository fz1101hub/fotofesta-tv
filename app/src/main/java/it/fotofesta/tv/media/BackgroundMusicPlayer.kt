package it.fotofesta.tv.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * Musica di sottofondo in loop: un brano o i brani di una cartella, uno dopo l'altro. Gestisce lo
 * stato asincrono di [MediaPlayer] (una pausa durante la preparazione non viene persa), il focus audio
 * di sistema e i file non più disponibili senza arrestare l'app: un brano che non si riproduce viene
 * saltato, e se non se ne riproduce nessuno notifica [onError].
 */
class BackgroundMusicPlayer(
    private val context: Context,
    private val onError: () -> Unit = {},
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private var player: MediaPlayer? = null
    private var playlist: List<String> = emptyList()
    private var index = 0
    /** Brani di fila che non si sono riprodotti: quando sono tutti, la musica si ferma. */
    private var failures = 0
    private var prepared = false
    private var wantPlaying = false
    private var volume = 1f

    /** Brano in corso per la silent disco: indirizzo, posizione e se sta suonando. */
    data class NowPlaying(val uri: String, val positionMs: Long, val playing: Boolean)

    // Letti dai thread del server della festa: si aggiornano solo qui, sul thread principale.
    @Volatile private var currentUri: String? = null
    @Volatile private var anchorElapsed = 0L
    @Volatile private var pausedAt = -1L

    /** Brano e posizione attuali, per i telefoni della silent disco; null se non suona nulla. */
    fun nowPlaying(): NowPlaying? {
        val uri = currentUri ?: return null
        val paused = pausedAt
        return if (paused >= 0) NowPlaying(uri, paused, false)
        else NowPlaying(uri, SystemClock.elapsedRealtime() - anchorElapsed, true)
    }

    private fun markStarted(current: MediaPlayer) {
        currentUri = playlist.getOrNull(index)
        anchorElapsed = SystemClock.elapsedRealtime() - runCatching { current.currentPosition.toLong() }.getOrDefault(0L)
        pausedAt = -1
    }

    private fun markPaused(current: MediaPlayer?) {
        pausedAt = current?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: 0L
    }

    fun play(tracks: List<String>, volume: Float) {
        this.volume = volume
        if (tracks.isEmpty()) return
        wantPlaying = true
        if (tracks != playlist || player == null) {
            release()
            playlist = tracks
            index = 0
            failures = 0
            wantPlaying = true
            if (!prepare(playlist[index])) {
                trackFailed()
                return
            }
        }
        startIfReady()
    }

    fun pause() {
        wantPlaying = false
        player?.takeIf { prepared && it.isPlaying }?.pause()
        if (currentUri != null) markPaused(player?.takeIf { prepared })
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    fun setVolume(volume: Float) {
        this.volume = volume
        player?.setVolume(volume, volume)
    }

    fun release() {
        wantPlaying = false
        releasePlayer()
        playlist = emptyList()
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun releasePlayer() {
        currentUri = null
        player?.runCatching { if (prepared) stop() }
        player?.release()
        player = null
        prepared = false
    }

    /**
     * Brano successivo della cartella, ricominciando dal primo dopo l'ultimo. I brani che non si
     * aprono (disco USB scollegato) si saltano in un ciclo, non in ricorsione: con una cartella
     * grande la pila finirebbe prima dei brani.
     */
    private fun advance() {
        while (playlist.isNotEmpty()) {
            releasePlayer()
            index = (index + 1) % playlist.size
            if (prepare(playlist[index])) {
                startIfReady()
                return
            }
            failures++
            if (failures >= playlist.size) {
                release()
                onError()
                return
            }
        }
    }

    /** Un brano non si riproduce: si passa al successivo, finché ce n'è almeno uno che va. */
    private fun trackFailed() {
        failures++
        if (failures >= playlist.size) {
            release()
            onError()
        } else {
            advance()
        }
    }

    private fun prepare(uriValue: String): Boolean = try {
        // Assegnato subito: se setDataSource fallisce, release() libera anche questo player.
        player = MediaPlayer()
        player?.apply {
            setAudioAttributes(attributes)
            setDataSource(context, Uri.parse(uriValue))
            // Un solo brano si ripete da sé; con una cartella si passa al successivo alla fine.
            isLooping = playlist.size == 1
            setVolume(volume, volume)
            setOnPreparedListener {
                prepared = true
                failures = 0
                startIfReady()
            }
            setOnCompletionListener { advance() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Errore MediaPlayer $what/$extra")
                trackFailed()
                true
            }
            prepareAsync()
        }
        true
    } catch (failure: Exception) {
        // File rimosso, permesso revocato o formato non supportato: chi chiama passa al successivo.
        Log.w(TAG, "Brano non riproducibile", failure)
        false
    }

    private fun startIfReady() {
        val current = player ?: return
        if (!prepared || !wantPlaying || current.isPlaying) return
        val granted = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            current.setVolume(volume, volume)
            current.start()
            markStarted(current)
        }
    }

    private fun onFocusChange(change: Int) {
        val current = player ?: return
        if (!prepared) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                current.setVolume(volume, volume)
                if (wantPlaying && !current.isPlaying) {
                    current.start()
                    markStarted(current)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> current.setVolume(volume * 0.3f, volume * 0.3f)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (current.isPlaying) {
                current.pause()
                markPaused(current)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Un'altra app ha preso l'audio: la musica riparte al prossimo "play" dello slideshow.
                wantPlaying = false
                if (current.isPlaying) current.pause()
                markPaused(current)
            }
        }
    }

    private companion object {
        const val TAG = "BackgroundMusic"
    }
}
