package it.fotofesta.tv.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import it.fotofesta.tv.R
import it.fotofesta.tv.model.BrowserRoot
import java.io.File

/**
 * Dischi e memorie leggibili dalla TV: memoria interna condivisa e dischi USB o schede montati
 * dal sistema. Da Android 11 li elenca StorageManager; prima si guardano le cartelle sotto
 * `/storage`, dove il sistema monta i dischi esterni, senza creare nulla sul disco.
 */
object StorageRoots {
    private val NOT_VOLUMES = setOf("emulated", "self", "enc_emulated")

    fun list(context: Context): List<BrowserRoot> {
        val manager = context.getSystemService(StorageManager::class.java)
        val roots = LinkedHashMap<String, BrowserRoot>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager != null) {
            manager.storageVolumes.forEach { volume ->
                val state = volume.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val directory = volume.directory ?: return@forEach
                roots[directory.absolutePath] = BrowserRoot(
                    path = directory.absolutePath,
                    label = volume.getDescription(context) ?: directory.name,
                    removable = volume.isRemovable,
                )
            }
        } else {
            @Suppress("DEPRECATION") // L'unico modo prima di Android 11, senza API nascoste.
            val primary = Environment.getExternalStorageDirectory()
            if (primary.canRead()) {
                roots[primary.absolutePath] = BrowserRoot(
                    path = primary.absolutePath,
                    label = manager?.primaryStorageVolume?.getDescription(context)
                        ?: context.getString(R.string.browser_internal_storage),
                    removable = false,
                )
            }
            File("/storage").listFiles().orEmpty()
                .filter { it.isDirectory && it.name !in NOT_VOLUMES && it.canRead() }
                .forEach { directory ->
                    val volume = runCatching { manager?.getStorageVolume(directory) }.getOrNull()
                    roots[directory.absolutePath] = BrowserRoot(
                        path = directory.absolutePath,
                        label = volume?.getDescription(context) ?: directory.name,
                        removable = volume?.isRemovable ?: true,
                    )
                }
        }
        // Prima i dischi esterni, che sono il motivo per cui si apre il browser.
        return roots.values.sortedBy { !it.removable }
    }
}
