package it.fotofesta.tv.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Annuncio della festa sulla rete di casa (DNS-SD, "_fotofesta._tcp"): le altre TV con FotoFesta la
 * trovano da sole e diventano schermi aggiuntivi dopo il PIN della regia. L'annuncio contiene solo
 * nome e porta, mai la chiave della festa.
 */
class PartyBeacon(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun advertise(name: String, port: Int) {
        stop()
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Annuncio della festa non riuscito: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        if (runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback) }.isSuccess) listener = callback
    }

    @Synchronized
    fun stop() {
        listener?.let { callback -> runCatching { nsd?.unregisterService(callback) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_fotofesta._tcp."
        private const val TAG = "PartyBeacon"
    }
}
