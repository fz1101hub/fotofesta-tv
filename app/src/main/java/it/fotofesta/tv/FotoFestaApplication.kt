package it.fotofesta.tv

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.SenderDisconnectedEventInfo
import com.google.android.gms.cast.tv.SenderInfo
import it.fotofesta.tv.cast.CastBridge

class FotoFestaApplication : Application() {
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
