package it.fotofesta.tv

import android.app.ActivityManager
import android.content.Context
import it.fotofesta.tv.google.GoogleDeviceAuthRepository
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.google.GoogleSelectionStore
import it.fotofesta.tv.google.SecureTokenStore
import it.fotofesta.tv.guests.GuestParty
import it.fotofesta.tv.guests.GuestPhotoStore
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Oggetti condivisi da app, salvaschermo e caricamento delle immagini: una sola istanza per
 * processo, creata da [FotoFestaApplication].
 */
class AppServices(context: Context) {
    /**
     * Unico client HTTP, mai chiuso: la revoca di "Disconnetti e cancella" deve poter terminare
     * anche dopo la chiusura dell'Activity, e app e salvaschermo riusano connessioni e thread.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    val googleAuth = GoogleDeviceAuthRepository(
        client = http,
        tokens = SecureTokenStore(context),
        clientId = BuildConfig.PHOTOS_CLIENT_ID,
        clientSecret = BuildConfig.PHOTOS_CLIENT_SECRET,
    )

    val googlePicker = GooglePhotosPickerRepository(http)

    // noBackupFilesDir: la sessione Picker vale solo per questo collegamento, non va nei backup.
    val googleSelection = GoogleSelectionStore(File(context.noBackupFilesDir, "google_selection.json"))

    val googleStream = GooglePhotosStream(googlePicker, googleAuth, googleSelection)

    /** Foto degli invitati: in RAM finché c'è posto, poi in una cartella temporanea con un limite. */
    val guests = GuestParty(context, guestStore(context))

    private companion object {
        const val MB = 1024L * 1024

        fun guestStore(context: Context): GuestPhotoStore {
            val activities = context.getSystemService(ActivityManager::class.java)
            // Un quarto della memoria concessa all'app, tra 16 e 64 MB: il resto serve a Compose e a Coil.
            val memory = when {
                activities == null || activities.isLowRamDevice -> 16 * MB
                else -> (activities.memoryClass * MB / 4).coerceIn(16 * MB, 64 * MB)
            }
            // Sulle TV che disegnano l'app in 4K le foto pesano circa quattro volte di più.
            val metrics = context.resources.displayMetrics
            val disk = if (maxOf(metrics.widthPixels, metrics.heightPixels) > 1920) 300 * MB else 100 * MB
            return GuestPhotoStore(
                directory = File(context.noBackupFilesDir, "guest_photos"),
                memoryBudgetBytes = memory,
                maxDiskBytes = disk,
            )
        }
    }
}

/** Servizi condivisi dell'app, raggiungibili da qualunque Context. */
val Context.appServices: AppServices
    get() = (applicationContext as FotoFestaApplication).services
