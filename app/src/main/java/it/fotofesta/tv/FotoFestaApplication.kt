package it.fotofesta.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.SenderDisconnectedEventInfo
import com.google.android.gms.cast.tv.SenderInfo
import it.fotofesta.tv.cast.CastBridge
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.ui.GooglePhotoFetcher
import kotlin.concurrent.thread

class FotoFestaApplication : Application(), ImageLoaderFactory {
    val services: AppServices by lazy { AppServices(this) }

    // Più telefoni possono essere collegati: l'indicatore si spegne solo quando escono tutti.
    private val castSenders = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        // Le versioni fino alla 1.2.0 scaricavano le foto Google sulla TV: ora si caricano da Google.
        thread(name = "legacy-cleanup") {
            GooglePhotosPickerRepository.deleteLegacyDownloads(noBackupFilesDir, cacheDir)
        }
        runCatching {
            CastReceiverContext.initInstance(this)
            CastReceiverContext.getInstance().apply {
                registerEventCallback(object : CastReceiverContext.EventCallback() {
                    override fun onSenderConnected(senderInfo: SenderInfo) {
                        castSenders += senderInfo.senderId
                        CastBridge.senderConnected(castSenders.isNotEmpty())
                    }

                    override fun onSenderDisconnected(eventInfo: SenderDisconnectedEventInfo) {
                        castSenders -= eventInfo.senderInfo.senderId
                        CastBridge.senderConnected(castSenders.isNotEmpty())
                    }
                })
                setMessageReceivedListener(BuildConfig.CAST_NAMESPACE) { _, _, message ->
                    CastBridge.receive(message)
                }
            }
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    runCatching { CastReceiverContext.getInstance().start() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    runCatching { CastReceiverContext.getInstance().stop() }
                    // Fermando il ricevitore le sessioni cadono anche senza un evento per ogni telefono.
                    castSenders.clear()
                    CastBridge.senderConnected(false)
                }
            })
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(services.http)
        .components {
            add(GooglePhotoFetcher.Factory(services.googleStream, services.http))
            add(GooglePhotoFetcher.Key())
        }
        // Le foto Google non passano mai da qui; ci finiscono solo quelle inviate con Cast. Cache
        // piccola e nella cartella che il sistema può svuotare quando serve spazio.
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .build()

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 32L * 1024 * 1024
    }
}
