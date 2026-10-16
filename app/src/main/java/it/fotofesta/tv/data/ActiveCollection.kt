package it.fotofesta.tv.data

import android.content.Context
import android.net.Uri
import android.util.Log
import it.fotofesta.tv.appServices
import it.fotofesta.tv.guests.toPhotoItem
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.combineWithGuests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Raccolta attiva dell'ultima sorgente scelta (Google Foto, cartella USB o foto demo), con le foto
 * approvate degli invitati se la festa è in corso. La usa il salvaschermo, che gira senza
 * l'interfaccia dell'app.
 */
class ActiveCollection(private val context: Context) {
    suspend fun load(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context)
        val photos = when (settings.loadLastSource()) {
            // Le foto Google si caricano da Google durante il salvaschermo: serve la connessione.
            // Una selezione scaduta presso Google viene tolta dall'archivio al primo caricamento fallito.
            "GOOGLE" -> context.appServices.googleSelection.current()?.toPhotos().orEmpty()
            "USB" -> settings.loadUsbTree()?.let { scanUsb(Uri.parse(it)) }.orEmpty()
            else -> emptyList()
        }
        val guests = context.appServices.guests.store.photos.value
            .filter { it.approved }
            .map { it.toPhotoItem(context) }
        combineWithGuests(
            base = photos.ifEmpty { DemoPhotos.load(context) },
            baseIsDemo = photos.isEmpty(),
            guests = guests,
            partyActive = false,
        )
    }

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
