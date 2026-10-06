package it.fotofesta.tv.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class UsbPhotoRepository(private val context: Context) {
    suspend fun scan(treeUri: Uri, limit: Int = 10_000): List<PhotoItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val queue = ArrayDeque<DocumentFile>().apply { add(root) }
        val result = mutableListOf<DocumentFile>()

        while (queue.isNotEmpty() && result.size < limit) {
            val current = queue.removeFirst()
            current.listFiles().forEach { child ->
                when {
                    child.isDirectory -> queue.addLast(child)
                    child.isFile && child.type?.startsWith("image/") == true -> result += child
                }
            }
        }

        result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
            .mapIndexed { index, file ->
                PhotoItem(
                    id = "usb:${file.uri}",
                    title = file.name ?: "Foto ${index + 1}",
                    ref = PhotoRef.LocalUri(file.uri.toString()),
                    source = SourceType.USB,
                )
            }
    }
}
