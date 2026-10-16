package it.fotofesta.tv

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.ui.GooglePhotoFetcher
import it.fotofesta.tv.ui.GuestPhotoFetcher
import it.fotofesta.tv.ui.ICloudPhotoFetcher
import it.fotofesta.tv.ui.NetworkPhotoFetcher
import it.fotofesta.tv.ui.SmbPhotoFetcher
import kotlin.concurrent.thread

class FotoFestaApplication : Application(), ImageLoaderFactory {
    val services: AppServices by lazy { AppServices(this) }

    override fun onCreate() {
        super.onCreate()
        thread(name = "legacy-cleanup") {
            // Le versioni fino alla 1.2.0 scaricavano le foto Google sulla TV: ora si caricano da Google.
            GooglePhotosPickerRepository.deleteLegacyDownloads(noBackupFilesDir, cacheDir)
            // Le stesse versioni tenevano anche una cache su disco delle foto ricevute con Google Cast.
            cacheDir.resolve("image_cache").deleteRecursively()
        }
        services.guests.initialize()
        // Il server per le foto degli invitati resta acceso solo mentre l'app è in primo piano.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = services.guests.onForeground()
            override fun onStop(owner: LifecycleOwner) = services.guests.onBackground()
        })
    }

    @Suppress("DEPRECATION") // Le TV con Android fino al 13 ricevono ancora i livelli RUNNING_*.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) services.guests.onLowMemory()
    }

    // Nessuna cache su disco: le foto Google arrivano da un Fetcher personalizzato (mai salvate),
    // quelle degli invitati dall'archivio della festa, quelle in rete dal server di casa e le altre
    // dalla TV o dalla chiavetta.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(services.http)
        .diskCache(null)
        .components {
            add(GooglePhotoFetcher.Factory(services.googleStream, services.http))
            add(GooglePhotoFetcher.Key())
            add(GuestPhotoFetcher.Factory(services.guests.store))
            add(GuestPhotoFetcher.Key())
            add(NetworkPhotoFetcher.Factory(services.lanHttp))
            add(NetworkPhotoFetcher.Key())
            add(ICloudPhotoFetcher.Factory(services.icloudStream, services.http))
            add(ICloudPhotoFetcher.Key())
            add(SmbPhotoFetcher.Factory(services.smbShares))
            add(SmbPhotoFetcher.Key())
        }
        .build()
}
