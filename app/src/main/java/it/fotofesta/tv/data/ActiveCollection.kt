package it.fotofesta.tv.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import it.fotofesta.tv.appServices
import it.fotofesta.tv.files.FileBrowser
import it.fotofesta.tv.files.toUsbPhotoItem
import it.fotofesta.tv.guests.toPhotoItem
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.combineWithGuests
import it.fotofesta.tv.network.NetworkFolder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Raccolta attiva dell'ultima sorgente scelta (Google Foto, cartella USB o di rete, foto demo), con
 * le foto approvate degli invitati se la festa è in corso. La usa il salvaschermo, che gira senza
 * l'interfaccia dell'app.
 */
class ActiveCollection(private val context: Context) {
    suspend fun load(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context)
        val photos = when (settings.loadLastSource()) {
            // Le foto Google si caricano da Google durante il salvaschermo: serve la connessione.
            // Una selezione scaduta presso Google viene tolta dall'archivio al primo caricamento fallito.
            "GOOGLE" -> context.appServices.googleSelection.current()?.toPhotos().orEmpty()
            "USB" -> settings.loadUsbFolder()?.let(::scanFolder)
                ?: settings.loadUsbTree()?.let { scanUsb(Uri.parse(it)) }.orEmpty()
            // Le foto in rete si caricano dal server di casa: serve che sia acceso.
            "NETWORK" -> settings.loadNetworkFolder()?.let(::scanNetwork).orEmpty()
            else -> emptyList()
        }
        // All'avvio a freddo del salvaschermo le foto di una festa interrotta si stanno ancora riprendendo.
        val party = context.appServices.guests
        withTimeoutOrNull(3_000) { party.ready.first { it } }
        val guests = party.store.photos.value
            .filter { it.approved }
            .map { it.toPhotoItem(context) }
        combineWithGuests(
            base = photos.ifEmpty { DemoPhotos.load(context) },
            baseIsDemo = photos.isEmpty(),
            guests = guests,
            partyActive = false,
        )
    }

    /** Cartella scelta con il browser interno; vuota se il disco è scollegato o il permesso revocato. */
    private fun scanFolder(path: String): List<PhotoItem> = runCatching {
        FileBrowser(FileBrowser.imageExtensions(Build.VERSION.SDK_INT)).scanPhotos(File(path)).map { it.toUsbPhotoItem() }
    }.getOrDefault(emptyList())

    /** Cartella di un server della rete di casa; vuota se il server è spento o irraggiungibile. */
    private fun scanNetwork(folder: NetworkFolder): List<PhotoItem> = runCatching {
        val media = context.appServices.networkMedia
        val server = media.find(folder.udn, folder.location) ?: return@runCatching emptyList()
        media.scanPhotos(server, folder.containerId)
    }.onFailure { Log.w("ActiveCollection", "Cartella di rete non disponibile", it) }.getOrDefault(emptyList())

    private suspend fun scanUsb(treeUri: Uri): List<PhotoItem> = try {
        UsbPhotoRepository(context).scan(treeUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Disco scollegato o permesso revocato: il salvaschermo ripiega sulle foto demo.
        Log.w("ActiveCollection", "Cartella USB non disponibile", failure)
        emptyList()
    }
}
