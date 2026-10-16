package it.fotofesta.tv.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import it.fotofesta.tv.model.BrowserKind

/** Permessi per leggere foto o musica dai dischi: da Android 13 sono separati per tipo. */
object StoragePermissions {
    fun forKind(kind: BrowserKind): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            listOf(if (kind == BrowserKind.PHOTOS) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_AUDIO)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun missing(context: Context, kind: BrowserKind): List<String> =
        forKind(kind).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun granted(context: Context, kind: BrowserKind): Boolean = missing(context, kind).isEmpty()
}
