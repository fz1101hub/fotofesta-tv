package it.fotofesta.tv.ui

import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import it.fotofesta.tv.R
import it.fotofesta.tv.appServices
import it.fotofesta.tv.media.videoUri
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay

private const val TAG = "VideoSlide"

/** Un video che la TV non sa riprodurre resta sullo schermo con un avviso per questo tempo. */
private const val FAILED_VIDEO_MS = 2_500L

/**
 * Video dello slideshow, dall'inizio alla fine. [active] è falso mentre il video esce con la
 * transizione: si ferma e tace. Alla fine (o se la TV non sa riprodurlo) chiama [onEnded]; con
 * [loop] ricomincia invece da capo, quando è l'unico elemento della raccolta. [onAudible] dice se
 * in questo momento il video suona, così la musica di sottofondo si ferma.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSlide(
    item: PhotoItem,
    fitMode: FitMode,
    active: Boolean,
    playing: Boolean,
    sound: Boolean,
    loop: Boolean,
    onEnded: () -> Unit,
    onAudible: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnAudible by rememberUpdatedState(onAudible)
    var failed by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context.appServices.videoDataSources))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(item.ref.videoUri()))
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentOnEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Formato o codec che questa TV non sa riprodurre, file rovinato o server spento.
                Log.w(TAG, "Video non riproducibile: ${item.title} (${error.errorCodeName})", error)
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, active, playing) { player.playWhenReady = active && playing }
    LaunchedEffect(player, active, sound) { player.volume = if (active && sound) 1f else 0f }
    LaunchedEffect(player, loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(failed, active) {
        if (failed && active) {
            delay(FAILED_VIDEO_MS)
            currentOnEnded()
        }
    }
    val audible = active && playing && sound && !failed
    DisposableEffect(audible) {
        currentOnAudible(audible)
        onDispose { if (audible) currentOnAudible(false) }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.video_player, null, false) as PlayerView).also { it.player = player }
            },
            update = { view ->
                view.player = player
                view.resizeMode = if (fitMode == FitMode.CROP) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Text(
                stringResource(R.string.video_unsupported),
                modifier = Modifier.align(Alignment.Center)
                    .background(Background.copy(alpha = .8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        }
    }
}
