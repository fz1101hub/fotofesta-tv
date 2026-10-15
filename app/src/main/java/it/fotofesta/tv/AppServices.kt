package it.fotofesta.tv

import android.content.Context
import it.fotofesta.tv.google.GoogleDeviceAuthRepository
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.google.GoogleSelectionStore
import it.fotofesta.tv.google.SecureTokenStore
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
}

/** Servizi condivisi dell'app, raggiungibili da qualunque Context. */
val Context.appServices: AppServices
    get() = (applicationContext as FotoFestaApplication).services
