package it.fotofesta.tv.files

import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileBrowserTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val browser = FileBrowser(FileBrowser.imageExtensions(sdk = 34))

    private fun file(path: String): File = File(temp.root, path).apply {
        parentFile?.mkdirs()
        writeText("x")
    }

    private fun folder(path: String): File = File(temp.root, path).apply { mkdirs() }

    @Test
    fun listShowsFoldersAndOnlyTheFilesOfTheRequestedKindInNaturalOrder() {
        folder("Vacanze 10")
        folder("Vacanze 2")
        folder(".Trashes")
        file("IMG_10.JPG")
        file("IMG_2.jpg")
        file("canzone.mp3")
        file("note.txt")
        file("._IMG_3.jpg")

        val photos = browser.list(temp.root, BrowserKind.PHOTOS)
        assertEquals(listOf("Vacanze 2", "Vacanze 10"), photos.folders.map { it.name })
        assertEquals(listOf("IMG_2.jpg", "IMG_10.JPG"), photos.files.map { it.name })

        val music = browser.list(temp.root, BrowserKind.MUSIC)
        assertEquals(listOf("Vacanze 2", "Vacanze 10"), music.folders.map { it.name })
        assertEquals(listOf("canzone.mp3"), music.files.map { it.name })
    }

    @Test
    fun listOfAnUnreadablePathIsEmpty() {
        val listing = browser.list(File(temp.root, "scollegato"), BrowserKind.PHOTOS)
        assertTrue(listing.folders.isEmpty())
        assertTrue(listing.files.isEmpty())
    }

    @Test
    fun scanIncludesSubfoldersSkipsHiddenEntriesAndSortsByName() {
        file("b.jpg")
        file("Festa/a 10.png")
        file("Festa/a 2.webp")
        file("Festa/Altre/c.heic")
        file("Festa/.thumbnails/d.jpg")
        file("Festa/._e.jpg")
        file("Festa/f.cr2")
        file("musica.mp3")

        val names = browser.scanPhotos(temp.root).map { it.name }
        assertEquals(listOf("a 2.webp", "a 10.png", "b.jpg", "c.heic"), names)
    }

    @Test
    fun scanStopsAtTheLimit() {
        repeat(12) { file("foto_$it.jpg") }
        assertEquals(5, browser.scanPhotos(temp.root, limit = 5).size)
    }

    @Test
    fun scanCanBeCancelled() {
        file("Festa/a.jpg")
        var calls = 0
        val failure = runCatching {
            browser.scanPhotos(temp.root) {
                calls++
                if (calls > 1) throw IllegalStateException("annullato")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun scanIgnoresSymbolicLinkLoops() {
        file("Festa/a.jpg")
        val link = File(temp.root, "Festa/giro")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), temp.root.toPath()) }.isSuccess
        assumeTrue("Il file system non supporta i collegamenti simbolici", created)

        val names = browser.scanPhotos(temp.root).map { it.name }
        assertEquals(listOf("a.jpg"), names)
    }

    @Test
    fun countPhotosLooksOnlyAtTheFolderItself() {
        file("a.jpg")
        file("b.png")
        file("._c.jpg")
        file("Sotto/d.jpg")
        file("e.mp3")
        assertEquals(2, browser.countPhotos(temp.root))
    }

    @Test
    fun imageFormatsFollowTheAndroidVersion() {
        val oreo = FileBrowser.imageExtensions(26)
        assertFalse("heic" in oreo)
        assertFalse("avif" in oreo)
        assertTrue("heic" in FileBrowser.imageExtensions(28))
        assertFalse("avif" in FileBrowser.imageExtensions(30))
        assertTrue("avif" in FileBrowser.imageExtensions(31))
        assertTrue(listOf("jpg", "jpeg", "png", "webp", "gif", "bmp").all { it in oreo })
    }

    @Test
    fun photoItemReadsTheFileFromItsPath() {
        val photo = file("Festa/Torta di compleanno.jpg")
        val item = photo.toUsbPhotoItem()
        assertEquals("usb:file:${photo.path}", item.id)
        assertEquals("Torta di compleanno", item.title)
        assertEquals(PhotoRef.LocalFile(photo.path), item.ref)
        assertEquals(SourceType.USB, item.source)
    }
}
