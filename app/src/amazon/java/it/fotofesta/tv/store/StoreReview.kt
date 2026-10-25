package it.fotofesta.tv.store

import android.app.Activity

/** L'Amazon Appstore non ha una richiesta di recensione dentro le app: non si chiede nulla. */
@Suppress("UNUSED_PARAMETER")
class StoreReview(activity: Activity) {
    fun request() = Unit
}
