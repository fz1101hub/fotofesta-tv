package it.fotofesta.tv.store

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import it.fotofesta.tv.BuildConfig

/**
 * Aggiornamenti dal Play Store: si scaricano in background e si installano solo a TV libera
 * (home, festa non in corso), mai durante uno slideshow o un gioco. Va creato prima di onCreate.
 */
class StoreUpdates(activity: ComponentActivity) {
    private val updates by lazy { AppUpdateManagerFactory.create(activity) }
    private val flow = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    private var checked = false
    private var downloaded = false
    private var idle = false
    private val listener = InstallStateUpdatedListener { install ->
        if (install.installStatus() == InstallStatus.DOWNLOADED) {
            downloaded = true
            installIfIdle()
        }
    }

    fun start() {
        runCatching { updates.registerListener(listener) }
    }

    /** La TV è libera (home aperta, festa non in corso): si controlla una volta e si installa. */
    fun setIdle(free: Boolean) {
        idle = free
        if (!free) return
        check()
        installIfIdle()
    }

    fun stop() {
        runCatching { updates.unregisterListener(listener) }
    }

    /** Una volta per avvio, e solo nelle versioni installate dal Play Store. */
    private fun check() {
        if (checked || BuildConfig.DEBUG) return
        checked = true
        runCatching {
            updates.appUpdateInfo.addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> {
                        downloaded = true
                        installIfIdle()
                    }
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        runCatching { updates.startUpdateFlowForResult(info, flow, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()) }
                }
            }
        }
    }

    private fun installIfIdle() {
        if (downloaded && idle) runCatching { updates.completeUpdate() }
    }
}
