package it.fotofesta.tv.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

class BackgroundMusicPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var currentUri: String? = null

    fun play(uriValue: String?, volume: Float) {
        if (uriValue == null) return
        if (currentUri == uriValue && player != null) {
            setVolume(volume)
            player?.start()
            return
        }
        release()
        currentUri = uriValue
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(context, Uri.parse(uriValue))
            isLooping = true
            setVolume(volume, volume)
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> release(); true }
            prepareAsync()
        }
    }

    fun pause() = player?.pause() ?: Unit

    fun setVolume(volume: Float) {
        player?.setVolume(volume, volume)
    }

    fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        currentUri = null
    }
}
