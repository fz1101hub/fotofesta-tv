package it.fotofesta.tv.data

import android.content.Context
import android.net.Uri
import android.util.Log
import it.fotofesta.tv.appServices
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Raccolta attiva dell'ultima sorgente scelta (Google Foto, cartella USB o foto demo). La usa il
 * salvaschermo, che gira senza l'interfaccia dell'app.
 */
class ActiveCollection(private val context: Context) {
    suspend fun load(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context)
        val photos = when (settings.loadLastSource()) {
            // Le foto Google si caricano da Google durante il salvaschermo: serve la connessione.
            "GOOGLE" -> context.appServices.googleSelection.current()
                ?.takeUnless { it.isExpired(System.currentTimeMillis()) }
                ?.toPhotos()
                .orEmpty()
            "USB" -> settings.loadUsbTree()?.let { scanUsb(Uri.parse(it)) }.orEmpty()
            else -> emptyList()
        }
        photos.ifEmpty { DemoPhotos.load(context) }
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
