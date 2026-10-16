package it.fotofesta.tv.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import it.fotofesta.tv.model.BrowserKind

/**
 * Permessi per leggere foto, video o musica dai dischi: da Android 13 sono separati per tipo, e una
 * cartella letta dal suo percorso mostra solo i file dei tipi permessi.
 */
object StoragePermissions {
    /** Il primo è quello indispensabile; per le foto il secondo aggiunge i video. */
    fun forKind(kind: BrowserKind): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when (kind) {
            BrowserKind.PHOTOS -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            BrowserKind.MUSIC -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
        }
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun missing(context: Context, kind: BrowserKind): List<String> =
        forKind(kind).filter { !isGranted(context, it) }

    /**
     * Basta il permesso indispensabile: senza quello dei video (ad esempio concesso a una versione
     * precedente solo per le foto) la cartella mostra le foto e non i video.
     */
    fun granted(context: Context, kind: BrowserKind): Boolean = isGranted(context, forKind(kind).first())

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
