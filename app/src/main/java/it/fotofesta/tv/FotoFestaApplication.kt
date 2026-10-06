package it.fotofesta.tv

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.SenderDisconnectedEventInfo
import com.google.android.gms.cast.tv.SenderInfo
import it.fotofesta.tv.cast.CastBridge

class FotoFestaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            CastReceiverContext.initInstance(this)
            CastReceiverContext.getInstance().apply {
                registerEventCallback(object : CastReceiverContext.EventCallback() {
                    override fun onSenderConnected(senderInfo: SenderInfo) {
                        CastBridge.senderConnected(true)
                    }

                    override fun onSenderDisconnected(eventInfo: SenderDisconnectedEventInfo) {
                        CastBridge.senderConnected(false)
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
                }
            })
        }
    }
}
