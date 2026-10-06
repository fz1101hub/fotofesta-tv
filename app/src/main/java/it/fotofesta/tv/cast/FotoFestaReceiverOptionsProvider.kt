package it.fotofesta.tv.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import it.fotofesta.tv.BuildConfig
import it.fotofesta.tv.R

class FotoFestaReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context)
            .setVersionCode(BuildConfig.CAST_PROTOCOL_VERSION)
            .setStatusText(context.getString(R.string.cast_status_ready))
            .setCustomNamespaces(listOf(BuildConfig.CAST_NAMESPACE))
            .build()
}
