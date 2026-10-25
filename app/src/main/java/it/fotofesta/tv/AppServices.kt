package it.fotofesta.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import it.fotofesta.tv.files.FileBrowser
import it.fotofesta.tv.google.GoogleAuthRepository
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.google.GoogleSelectionStore
import it.fotofesta.tv.google.SecureTokenStore
import it.fotofesta.tv.guests.GuestParty
import it.fotofesta.tv.guests.HostRemote
import it.fotofesta.tv.guests.PartyHub
import it.fotofesta.tv.guests.PartyPlay
import it.fotofesta.tv.guests.PhoneGame
import it.fotofesta.tv.guests.PartySetup
import it.fotofesta.tv.guests.PhoneWall
import it.fotofesta.tv.icloud.ICloudAlbumClient
import it.fotofesta.tv.icloud.ICloudAlbumStream
import it.fotofesta.tv.guests.GuestPhotoStore
import it.fotofesta.tv.media.VideoDataSources
import it.fotofesta.tv.network.LanHttp
import it.fotofesta.tv.network.NetworkMedia
import it.fotofesta.tv.network.ScreenSize
import it.fotofesta.tv.smb.SmbShares
import it.fotofesta.tv.tv.DlnaRenderer
import it.fotofesta.tv.tv.IpCamera
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

    /**
     * Client per i video in streaming: come [http] (stesse connessioni e stessi thread), ma senza il
     * limite complessivo di 90 secondi per chiamata, che spezzerebbe un video più lungo. Restano i
     * limiti di connessione e di attesa fra un pezzo e l'altro.
     */
    val streamingHttp: OkHttpClient = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()

    val googleAuth = GoogleAuthRepository(
        client = http,
        tokens = SecureTokenStore(context),
        clientId = BuildConfig.PHOTOS_CLIENT_ID,
        clientSecret = BuildConfig.PHOTOS_CLIENT_SECRET,
        redirectUri = BuildConfig.PHOTOS_REDIRECT_URI,
    )

    val googlePicker = GooglePhotosPickerRepository(http)

    // noBackupFilesDir: la sessione Picker vale solo per questo collegamento, non va nei backup.
    val googleSelection = GoogleSelectionStore(File(context.noBackupFilesDir, "google_selection.json"))

    val googleStream = GooglePhotosStream(googlePicker, googleAuth, googleSelection)

    /** HTTP in chiaro, solo verso i server multimediali della rete di casa. */
    val lanHttp = LanHttp()

    /** Cartelle dei server multimediali (DLNA) della rete di casa. */
    val networkMedia = NetworkMedia(lanHttp, NetworkMedia.imageMimeTypes(Build.VERSION.SDK_INT), screenSize(context))

    /** Album condiviso di iCloud in uso, con gli indirizzi dei file che Apple fa scadere. */
    val icloudClient = ICloudAlbumClient(http)
    val icloudStream = ICloudAlbumStream(icloudClient, screenSize(context))

    /** Cartelle condivise (SMB) di computer e NAS: una connessione per computer, riaperta se cade. */
    val smbShares = SmbShares(FileBrowser.imageExtensions(Build.VERSION.SDK_INT))

    /** Da dove il lettore legge i video: TV e disco USB, rete di casa, Google Foto, iCloud, cartelle condivise. */
    val videoDataSources = VideoDataSources(context.applicationContext, lanHttp, googleStream, icloudStream, streamingHttp, smbShares)

    /** Gioco con i telefoni: la TV fa le domande, gli invitati rispondono dalla pagina della festa. */
    val phoneGame = PhoneGame()

    /** Reazioni e dediche dei telefoni, servite dalla stessa pagina della festa. */
    val partyHub = PartyHub()

    /** Regia dal telefono con il PIN mostrato sulla TV. */
    val hostRemote = HostRemote()

    /** Giochi con i telefoni oltre ai quiz: disegni, missioni, talpa, caccia al QR, monete. */
    val partyPlay = PartyPlay(partyHub)

    /** Nome, logo, colori, scaletta, sponsor e invitati attesi: restano sulla TV tra una festa e l'altra. */
    val partySetup = PartySetup(java.io.File(context.noBackupFilesDir, "party_setup"))

    /** Muro di telefoni: un pezzo della stessa foto su ogni telefono, uno accanto all'altro. */
    val phoneWall = PhoneWall()

    /** Foto degli invitati: in RAM finché c'è posto, poi in una cartella temporanea con un limite. */
    val guests = GuestParty(context, guestStore(context), phoneGame, partyHub, hostRemote, partyPlay).apply {
        phoneWall = this@AppServices.phoneWall
        setup = this@AppServices.partySetup
        // Schermo DLNA: le foto mandate dai telefoni con "Riproduci su" entrano nella festa.
        val appContext = context.applicationContext
        dlnaFactory = {
            // Il titolo DLNA è di solito il nome del file: come autore si scrive "Telefono (DLNA)".
            DlnaRenderer(appContext, appContext.getString(R.string.dlna_name), lanHttp) { bytes, _ ->
                addPhoto(bytes, appContext.getString(R.string.dlna_sender))
            }
        }
    }

    /** Vecchio telefono come telecamera e microfono (app "IP Webcam"), nella rete di casa. */
    val ipCamera = IpCamera(lanHttp)

    private companion object {
        const val MB = 1024L * 1024
        /** Spazio massimo per le foto degli invitati sulla chiavetta. */
        const val USB_DISK_BYTES = 4L * 1024 * MB

        /** Le foto in rete e su iCloud si chiedono nella versione che basta a riempire lo schermo. */
        fun screenSize(context: Context): ScreenSize = context.resources.displayMetrics.let {
            ScreenSize(maxOf(it.widthPixels, it.heightPixels), minOf(it.widthPixels, it.heightPixels))
        }

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
            // Feste grandi: con l'impostazione, le foto vanno sulla chiavetta (cartella privata dell'app
            // sul supporto rimovibile, che Android cancella se si disinstalla l'app). Si decide
            // all'avvio: cambiare cartella a festa in corso perderebbe le foto già arrivate.
            val usb = context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE).getBoolean("usb_storage", false)
            val removable = if (usb) removableDirectory(context) else null
            return GuestPhotoStore(
                directory = removable?.let { File(it, "guest_photos") } ?: File(context.noBackupFilesDir, "guest_photos"),
                memoryBudgetBytes = memory,
                // Sulla chiavetta lo spazio è molto di più, ma sempre entro metà di quello libero.
                maxDiskBytes = removable?.let { minOf(USB_DISK_BYTES, it.usableSpace / 2) }?.coerceAtLeast(disk) ?: disk,
            )
        }

        /** Cartella dell'app su una chiavetta o un disco USB scrivibile, se c'è. */
        private fun removableDirectory(context: Context): File? =
            context.getExternalFilesDirs(null).drop(1).filterNotNull().firstOrNull { dir ->
                runCatching { android.os.Environment.isExternalStorageRemovable(dir) && dir.canWrite() }.getOrDefault(false)
            }
    }
}

/** Servizi condivisi dell'app, raggiungibili da qualunque Context. */
val Context.appServices: AppServices
    get() = (applicationContext as FotoFestaApplication).services
