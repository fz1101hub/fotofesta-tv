package it.fotofesta.tv.tv

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import it.fotofesta.tv.R
import it.fotofesta.tv.appServices

/**
 * La festa come canale della TV: nell'elenco dei canali (Live Channels, guida TV) compare
 * "FotoFesta", che mostra le foto approvate degli invitati, o le foto demo se la festa non c'è.
 * Le foto si disegnano direttamente sullo schermo del canale, una ogni pochi secondi con una
 * dissolvenza: leggero anche per una TV modesta. Le foto non lasciano mai la TV.
 */
class PartyTvInputService : TvInputService() {
    override fun onCreateSession(inputId: String): Session = PartySession(this)
}

private class PartySession(private val context: Context) : TvInputService.Session(context) {
    @Volatile private var surface: Surface? = null
    @Volatile private var width = 1920
    @Volatile private var height = 1080
    private var thread: Thread? = null

    override fun onSetSurface(surface: Surface?): Boolean {
        stop()
        this.surface = surface
        if (surface != null) start()
        return true
    }

    override fun onSurfaceChanged(format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            this.width = width
            this.height = height
        }
    }

    override fun onTune(channelUri: Uri): Boolean {
        notifyVideoAvailable()
        return true
    }

    override fun onSetStreamVolume(volume: Float) = Unit

    override fun onSetCaptionEnabled(enabled: Boolean) = Unit

    override fun onRelease() = stop()

    private fun start() {
        thread = Thread({ loop() }, "party-channel").apply {
            isDaemon = true
            start()
        }
    }

    private fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var previous: Bitmap? = null
        var index = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val next = nextPhoto(index++)
                if (next == null) {
                    Thread.sleep(STEP_MS)
                    continue
                }
                for (frame in 1..FADE_FRAMES) {
                    draw(previous, next, frame / FADE_FRAMES.toFloat(), paint)
                    Thread.sleep(FADE_MS / FADE_FRAMES)
                }
                previous?.recycle()
                previous = next
                Thread.sleep(STEP_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            previous?.recycle()
        }
    }

    private fun draw(previous: Bitmap?, next: Bitmap, progress: Float, paint: Paint) {
        val target = surface ?: return
        val canvas: Canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            previous?.let {
                paint.alpha = 255
                canvas.drawBitmap(it, null, fit(it, canvas.width, canvas.height), paint)
            }
            paint.alpha = (progress * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(next, null, fit(next, canvas.width, canvas.height), paint)
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    /** La foto intera al centro, senza deformarla. */
    private fun fit(bitmap: Bitmap, width: Int, height: Int): Rect {
        val scale = minOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        val w = (bitmap.width * scale).toInt()
        val h = (bitmap.height * scale).toInt()
        return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    /** Foto approvate degli invitati, a turno; senza festa le foto demo. */
    private fun nextPhoto(index: Int): Bitmap? = runCatching {
        val services = context.applicationContext.appServices
        val store = services.guests.store
        val guests = store.photos.value.filter { it.approved }
        if (guests.isNotEmpty()) {
            val bytes = store.read(guests[index % guests.size].id) ?: return@runCatching null
            decode(width, height) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        } else {
            val demo = DEMO[index % DEMO.size]
            decode(width, height) { options -> BitmapFactory.decodeResource(context.resources, demo, options) }
        }
    }.onFailure { Log.w("PartyTvInput", "Foto del canale non caricata", it) }.getOrNull()

    private fun decode(width: Int, height: Int, block: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        block(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return block(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val STEP_MS = 7_000L
        private const val FADE_MS = 900L
        private const val FADE_FRAMES = 18
        private val DEMO = intArrayOf(R.drawable.demo_coast, R.drawable.demo_picnic, R.drawable.demo_mountains, R.drawable.demo_birthday)
    }
}

/**
 * Prima configurazione del canale, aperta dall'app dei canali della TV: aggiunge "FotoFesta"
 * all'elenco (una volta sola) e torna subito indietro.
 */
class PartyTvSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputId = intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)
            ?: TvContract.buildInputId(ComponentName(this, PartyTvInputService::class.java))
        Thread {
            val ok = runCatching { registerChannel(this, inputId) }
                .onFailure { Log.w("PartyTvInput", "Canale non aggiunto", it) }
                .isSuccess
            runOnUiThread {
                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                finish()
            }
        }.start()
    }

    private fun registerChannel(context: Context, inputId: String) {
        val resolver = context.contentResolver
        val existing = resolver.query(TvContract.buildChannelsUriForInput(inputId), arrayOf(TvContract.Channels._ID), null, null, null)
            ?.use { it.count } ?: 0
        if (existing > 0) return
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, context.getString(R.string.app_name))
            put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, "1")
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
            put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
            put(TvContract.Channels.COLUMN_DESCRIPTION, context.getString(R.string.tv_channel_description))
        }
        resolver.insert(TvContract.Channels.CONTENT_URI, values)
    }
}
