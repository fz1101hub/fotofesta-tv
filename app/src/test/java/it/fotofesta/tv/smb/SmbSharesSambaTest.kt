package it.fotofesta.tv.smb

import it.fotofesta.tv.files.FileBrowser
import it.fotofesta.tv.model.PhotoRef
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Prova con un vero server Samba, se indicato in FOTOFESTA_SMB_TEST ("host:porta"; in CI non c'è e
 * i test si saltano). Il server deve avere:
 * - la condivisione "Foto" per l'utente festa/segreta123, con Mare/demo_coast.jpg,
 *   Mare/demo_mountains.jpg, Compleanno/demo_birthday.jpg e Compleanno/Torta/demo_picnic.jpg;
 * - la condivisione "Pubblica" aperta agli ospiti, con due foto;
 * - in FOTOFESTA_SMB_FILES la cartella locale di "Foto", per confrontare i byte.
 */
class SmbSharesSambaTest {
    private lateinit var server: SmbLocation
    private val shares = SmbShares(FileBrowser.imageExtensions(34), connectTimeoutMs = 2_000, responseTimeoutMs = 10_000)
    private val user = SmbLogin("festa", "segreta123")

    @Before
    fun server() {
        val address = System.getenv("FOTOFESTA_SMB_TEST")
        assumeTrue("Server Samba di prova non indicato", !address.isNullOrBlank())
        server = SmbAddress.parse(address!!)!!
    }

    @After
    fun close() = shares.close()

    @Test
    fun loginListsDiskSharesOnly() {
        assertEquals(listOf("Foto", "Pubblica"), shares.login(server, user))
    }

    @Test
    fun wrongPasswordIsALoginProblem() {
        val failure = runCatching { shares.login(server, SmbLogin("festa", "sbagliata")) }.exceptionOrNull()
        assertEquals(SmbException.Reason.LOGIN, (failure as SmbException).reason)
        // Il tentativo fallito non resta come accesso del computer.
        assertEquals(null, shares.loginFor(server))
    }

    @Test
    fun guestReadsThePublicShareButNotThePrivateOne() {
        shares.login(server, SmbLogin())
        assertEquals(SmbListing(emptyList(), photos = 2, videos = 0), shares.list(server.child("Pubblica")))
        val failure = runCatching { shares.list(server.child("Foto")) }.exceptionOrNull() as SmbException
        assertEquals(SmbException.Reason.DENIED, failure.reason)
    }

    @Test
    fun foldersListAndScanRecursively() {
        shares.login(server, user)
        val root = server.child("Foto")
        assertEquals(SmbListing(listOf("Compleanno", "Mare"), photos = 0, videos = 0), shares.list(root))
        assertEquals(SmbListing(listOf("Torta"), photos = 1, videos = 0), shares.list(root.child("Compleanno")))
        val photos = shares.scanPhotos(root)
        assertEquals(listOf("demo_birthday", "demo_coast", "demo_mountains", "demo_picnic"), photos.map { it.title })
        val picnic = photos.last().ref as PhotoRef.SmbPhoto
        assertEquals(root.child("Compleanno").child("Torta").child("demo_picnic.jpg"), SmbLocation.parse(picnic.path))
        assertFalse(photos.any { it.video })
        assertEquals(1, shares.scanPhotos(root, limit = 1).size)
    }

    @Test
    fun missingFoldersAndSharesAreNotFound() {
        shares.login(server, user)
        listOf(server.child("Foto").child("Nessuna"), server.child("Inesistente")).forEach { location ->
            val failure = runCatching { shares.list(location) }.exceptionOrNull() as SmbException
            assertEquals(location.toString(), SmbException.Reason.NOT_FOUND, failure.reason)
        }
    }

    @Test
    fun filesReadWholeAndInPieces() {
        val local = File(System.getenv("FOTOFESTA_SMB_FILES") ?: return, "Mare/demo_coast.jpg").readBytes()
        shares.login(server, user)
        val location = server.child("Foto").child("Mare").child("demo_coast.jpg")
        shares.open(location).use { file ->
            assertEquals(local.size.toLong(), file.size)
            val piece = ByteArray(1000)
            var read = 0
            while (read < piece.size) {
                val count = file.read(500_000L + read, piece, read, piece.size - read)
                assertTrue(count > 0)
                read += count
            }
            assertArrayEquals(local.copyOfRange(500_000, 501_000), piece)
            assertEquals(-1, file.read(file.size, piece, 0, 10))
        }
        assertArrayEquals(local, shares.open(location).inputStream().use { it.readBytes() })
    }

    @Test
    fun connectionsReopenAfterDisconnecting() {
        shares.login(server, user)
        val location = server.child("Foto").child("Mare")
        assertEquals(2, shares.list(location).photos)
        shares.disconnectAll()
        assertEquals(2, shares.list(location).photos)
    }

    @Test
    fun unreachableComputerFailsFast() {
        val started = System.nanoTime()
        try {
            shares.login(SmbLocation("10.255.255.1"), user)
            fail("Il computer non esiste")
        } catch (failure: SmbException) {
            assertEquals(SmbException.Reason.UNREACHABLE, failure.reason)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }
}
