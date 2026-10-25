package it.fotofesta.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import it.fotofesta.tv.media.MotionPhoto
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foto in movimento di Pixel e Samsung: prima della foto ferma si guarda una volta il suo breve
 * video, senza audio. Solo per le foto della chiavetta e della memoria della TV (file locali).
 */
@Composable
fun MotionClip(photo: PhotoItem, fitMode: FitMode, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val path = (photo.ref as? PhotoRef.LocalFile)?.path ?: return
    val clip by produceState<File?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { extractClip(File(context.cacheDir, CACHE_FOLDER), File(path)) }
    }
    var finished by remember(photo.id) { mutableStateOf(false) }
    val file = clip ?: return
    if (finished) return
    VideoSlide(
        PhotoItem(id = "motion:${photo.id}", title = photo.title, ref = PhotoRef.LocalFile(file.path), source = photo.source, video = true),
        fitMode,
        active = true,
        playing = playing,
        sound = false,
        loop = false,
        onEnded = { finished = true },
        onAudible = {},
        modifier = modifier,
    )
}

/** Il video della foto in una cartella temporanea (tenendo solo gli ultimi), o null se non c'è. */
private fun extractClip(cache: File, photo: File): File? = runCatching {
    if (!photo.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) return null
    cache.mkdirs()
    val target = File(cache, "${(photo.path + photo.lastModified()).hashCode().toUInt()}.mp4")
    if (target.exists()) return target
    if (MotionPhoto.find(photo) == null) return null
    // Pochi video tenuti da parte: lo spazio della TV è prezioso.
    cache.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_CLIPS - 1)?.forEach { it.delete() }
    val temp = File(cache, target.name + ".tmp")
    if (!MotionPhoto.extract(photo, temp) || !temp.renameTo(target)) {
        temp.delete()
        return null
    }
    target
}.getOrNull()

private const val CACHE_FOLDER = "motion_clips"
private const val KEEP_CLIPS = 6
