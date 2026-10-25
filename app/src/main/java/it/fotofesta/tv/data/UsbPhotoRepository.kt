package it.fotofesta.tv.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class UsbPhotoRepository(private val context: Context) {
    /**
     * Indicizza foto e video della cartella scelta e delle sottocartelle con una sola query per cartella.
     * `DocumentFile.listFiles()` farebbe invece tre query per ogni file (tipo, nome, cartella),
     * troppo lento su dischi USB con migliaia di foto.
     */
    suspend fun scan(treeUri: Uri, limit: Int = 10_000): List<PhotoItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val queue = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        val visited = HashSet<String>()
        // Identificativo, nome e se è un video.
        val found = mutableListOf<Triple<String, String, Boolean>>()

        while (queue.isNotEmpty() && found.size < limit) {
            ensureActive()
            val parentId = queue.removeFirst()
            if (!visited.add(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && found.size < limit) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty().lowercase()
                    when {
                        // Esclude file nascosti, come i "._IMG.jpg" creati da macOS sui dischi esterni.
                        id == null || name.startsWith(".") -> Unit
                        mime == Document.MIME_TYPE_DIR -> queue.addLast(id)
                        mime in SUPPORTED_IMAGE_TYPES || mime.startsWith("video/") -> found += Triple(id, name, mime.startsWith("video/"))
                    }
                }
            }
        }

        found.sortedWith(compareBy(NaturalOrder) { it.second })
            .map { (id, name, video) ->
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                PhotoItem(
                    id = "usb:$uri",
                    title = name.substringBeforeLast('.').ifBlank { name },
                    ref = PhotoRef.LocalUri(uri.toString()),
                    source = SourceType.USB,
                    video = video,
                )
            }
    }

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)

        /**
         * Formati decodificabili da questa versione di Android; RAW, TIFF e PSD apparirebbero come foto
         * vuote. HEIC/HEIF si decodificano da Android 9, AVIF da Android 12.
         */
        val SUPPORTED_IMAGE_TYPES = buildSet {
            addAll(listOf("image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) addAll(listOf("image/heic", "image/heif"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("image/avif")
        }
    }
}
