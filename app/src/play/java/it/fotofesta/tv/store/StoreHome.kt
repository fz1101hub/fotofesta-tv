package it.fotofesta.tv.store

import android.content.Context

/** Su Google Play la schermata Home usa il canale di Android TV (HomeChannel): qui non c'è nulla da fare. */
@Suppress("UNUSED_PARAMETER")
class StoreHome(context: Context) {
    fun publish() = Unit

    fun remove() = Unit

    companion object {
        const val SUPPORTED = false
    }
}
