package it.fotofesta.tv.store

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Richiesta di recensione di Google Play, dopo una festa riuscita. Google decide se e quando
 * mostrarla davvero (e la mostra poche volte): qui si chiede soltanto.
 */
class StoreReview(private val activity: Activity) {
    fun request() {
        val manager = runCatching { ReviewManagerFactory.create(activity) }.getOrNull() ?: return
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful && !activity.isFinishing) manager.launchReviewFlow(activity, task.result)
        }
    }
}
