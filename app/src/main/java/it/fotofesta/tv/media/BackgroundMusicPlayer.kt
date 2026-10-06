package it.fotofesta.tv.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * Musica di sottofondo in loop. Gestisce lo stato asincrono di [MediaPlayer] (una pausa durante la
 * preparazione non viene persa), il focus audio di sistema e i file non più disponibili senza
 * arrestare l'app: in caso di errore notifica [onError].
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
    private var currentUri: String? = null
    private var prepared = false
    private var wantPlaying = false
    private var volume = 1f

    fun play(uriValue: String?, volume: Float) {
        this.volume = volume
        if (uriValue == null) return
        wantPlaying = true
        if (currentUri != uriValue || player == null) {
            release()
            wantPlaying = true
            if (!prepare(uriValue)) return
        }
        startIfReady()
    }

    fun pause() {
        wantPlaying = false
        player?.takeIf { prepared && it.isPlaying }?.pause()
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    fun setVolume(volume: Float) {
        this.volume = volume
        player?.setVolume(volume, volume)
    }

    fun release() {
        wantPlaying = false
        player?.runCatching { if (prepared) stop() }
        player?.release()
        player = null
        currentUri = null
        prepared = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun prepare(uriValue: String): Boolean = try {
        // Assegnato subito: se setDataSource fallisce, release() libera anche questo player.
        player = MediaPlayer()
        player?.apply {
            setAudioAttributes(attributes)
            setDataSource(context, Uri.parse(uriValue))
            isLooping = true
            setVolume(volume, volume)
            setOnPreparedListener {
                prepared = true
                startIfReady()
            }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Errore MediaPlayer $what/$extra")
                this@BackgroundMusicPlayer.release()
                onError()
                true
            }
            prepareAsync()
        }
        currentUri = uriValue
        true
    } catch (failure: Exception) {
        // File rimosso, permesso revocato o formato non supportato.
        Log.w(TAG, "Brano non riproducibile", failure)
        release()
        onError()
        false
    }

    private fun startIfReady() {
        val current = player ?: return
        if (!prepared || !wantPlaying || current.isPlaying) return
        val granted = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            current.setVolume(volume, volume)
            current.start()
        }
    }

    private fun onFocusChange(change: Int) {
        val current = player ?: return
        if (!prepared) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                current.setVolume(volume, volume)
                if (wantPlaying && !current.isPlaying) current.start()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> current.setVolume(volume * 0.3f, volume * 0.3f)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (current.isPlaying) current.pause()
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Un'altra app ha preso l'audio: la musica riparte al prossimo "play" dello slideshow.
                wantPlaying = false
                if (current.isPlaying) current.pause()
            }
        }
    }

    private companion object {
        const val TAG = "BackgroundMusic"
    }
}
