package it.fotofesta.tv.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import it.fotofesta.tv.R

/**
 * Raccomandazioni nella schermata Home di Fire TV: notifiche con la categoria "recommendation" e
 * gli extra di Amazon. Come il canale di Android TV, portano a slideshow, festa, giochi e spettacoli
 * con le immagini demo: le foto degli invitati non escono mai dall'app.
 */
class StoreHome(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun publish() {
        val notifications = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.fire_recommendations_channel), NotificationManager.IMPORTANCE_LOW),
            )
            ITEMS.forEachIndexed { index, item ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("fotofesta://open/${item.route}")).setPackage(context.packageName)
                val open = PendingIntent.getActivity(context, index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val picture = picture(item.image)
                val extras = Bundle().apply {
                    putString("com.amazon.extra.DISPLAY_NAME", context.getString(R.string.app_name))
                    putString("com.amazon.extra.LONG_DESCRIPTION", context.getString(item.body))
                    putInt("com.amazon.extra.RANK", index)
                }
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setContentTitle(context.getString(item.title))
                    .setContentText(context.getString(item.body))
                    .setContentInfo(context.getString(R.string.app_name))
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setLocalOnly(true)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(open)
                    .addExtras(extras)
                    .apply { picture?.let { setLargeIcon(it) } }
                    .build()
                notifications.notify(TAG, BASE_ID + index, notification)
            }
        }.onFailure { Log.w(TAG, "Raccomandazioni di Fire TV non pubblicate", it) }
    }

    fun remove() {
        ITEMS.indices.forEach { runCatching { manager?.cancel(TAG, BASE_ID + it) } }
    }

    /** Immagine demo ridotta a 16:9: Fire TV la mostra come copertina della raccomandazione. */
    private fun picture(id: Int): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeResource(context.resources, id, options)
    }.getOrNull()

    private data class Item(val route: String, val title: Int, val body: Int, val image: Int)

    companion object {
        /** Nella versione per l'Amazon Appstore la home di Fire TV mostra le raccomandazioni. */
        const val SUPPORTED = true

        private const val TAG = "StoreHome"
        private const val CHANNEL = "fire_recommendations"
        private const val BASE_ID = 4100
        private val ITEMS = listOf(
            Item("party", R.string.channel_party, R.string.fire_rec_party, R.drawable.demo_birthday),
            Item("slideshow", R.string.channel_slideshow, R.string.fire_rec_slideshow, R.drawable.demo_coast),
            Item("games", R.string.channel_games, R.string.fire_rec_games, R.drawable.demo_picnic),
            Item("shows", R.string.channel_shows, R.string.fire_rec_shows, R.drawable.demo_mountains),
        )
    }
}
