package it.fotofesta.tv

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import it.fotofesta.tv.cast.RemoteUrlPolicy
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.SenderDisconnectedEventInfo
import com.google.android.gms.cast.tv.SenderInfo
import it.fotofesta.tv.cast.CastBridge

class FotoFestaApplication : Application(), ImageLoaderFactory {
    // Coil e usato per le foto remote del protocollo Cast: un URL pubblico puo
    // risolvere a un IP privato tramite DNS rebinding o un redirect.
    override fun newImageLoader(): ImageLoader {
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (RemoteUrlPolicy.isLocalOrAmbiguousHost(hostname)) {
                    throw UnknownHostException("Host remoto non consentito")
                }
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any {
                        RemoteUrlPolicy.isLocalOrAmbiguousHost(it.hostAddress)
                    }) {
                    throw UnknownHostException("Indirizzo risolto non consentito")
                }
                return addresses
            }
        }
        val client = OkHttpClient.Builder()
            .dns(dns)
            // I redirect possono puntare a host privati: fallire invece di seguirli.
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addNetworkInterceptor response
                val mime = body.contentType()?.type
                if (body.contentLength() > MAX_REMOTE_IMAGE_BYTES || (mime != null && mime != "image")) {
                    response.close()
                    throw IOException("Risorsa foto non ammessa")
                }
                // Limita anche le risposte chunked prive di Content-Length.
                val boundedSource = object : ForwardingSource(body.source()) {
                    var seen = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, byteCount)
                        if (count > 0) {
                            seen += count
                            if (seen > MAX_REMOTE_IMAGE_BYTES) {
                                throw IOException("Foto remota troppo grande")
                            }
                        }
                        return count
                    }
                }.buffer()
                val boundedBody = object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = boundedSource
                }
                response.newBuilder().body(boundedBody).build()
            }
            .build()
        return ImageLoader.Builder(this).okHttpClient(client).build()
    }

    private companion object {
        const val MAX_REMOTE_IMAGE_BYTES = 24L * 1024 * 1024
    }

    // Più telefoni possono essere collegati: l'indicatore si spegne solo quando escono tutti.
    private val castSenders = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        // Cattura i crash anche se Compose non riesce a mostrare la prima schermata.
        StartupDiagnostics.install(this)
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
        }.onFailure { failure ->
            // Cast e opzionale: una TV senza Cast Connect deve comunque aprire FotoFesta.
            Log.w("FotoFestaApplication", "Cast Connect non disponibile", failure)
        }
    }
}
