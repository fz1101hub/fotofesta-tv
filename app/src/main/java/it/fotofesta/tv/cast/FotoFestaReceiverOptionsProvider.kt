package it.fotofesta.tv.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import it.fotofesta.tv.BuildConfig

class FotoFestaReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context)
            .setVersionCode(BuildConfig.CAST_PROTOCOL_VERSION)
            .setStatusText("FotoFesta TV pronta")
            .setCustomNamespaces(listOf(BuildConfig.CAST_NAMESPACE))
            .build()
}
