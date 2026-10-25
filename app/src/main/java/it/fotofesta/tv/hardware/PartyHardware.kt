package it.fotofesta.tv.hardware

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * I dispositivi della festa nella rete di casa: luci colorate, stampante delle foto e stampante
 * degli scontrini. Il lavoro di rete è sempre su thread propri: lo schermo non aspetta mai.
 */
class PartyHardware {
    private val lightsClient = PartyLightsClient()
    private val ippClient = IppClient()
    private val lightsWorker = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "party-lights").apply { isDaemon = true } }
    private val printWorker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "party-print").apply { isDaemon = true } }

    @Volatile var lights: List<PartyLights.Device> = emptyList()
    @Volatile private var lastColor = WARM_WHITE
    @Volatile private var lastSentAt = 0L

    /** Colore della foto sullo schermo ([average] è il colore medio); al massimo uno al secondo. */
    fun showPhotoColor(average: Int) {
        val devices = lights
        if (devices.isEmpty()) return
        val color = PartyLights.partyColor(average)
        lastColor = color
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_GAP_MS) return
        lastSentAt = now
        lightsWorker.execute { devices.forEach { lightsClient.show(it, color, BRIGHTNESS) } }
    }

    /** Un lampo bianco (arrivi, coriandoli), poi di nuovo il colore della foto. */
    fun flash() {
        val devices = lights
        if (devices.isEmpty()) return
        lightsWorker.execute { devices.forEach { lightsClient.show(it, 0xFFFFFF, 1f) } }
        lightsWorker.schedule({ devices.forEach { lightsClient.show(it, lastColor, BRIGHTNESS) } }, FLASH_MS, TimeUnit.MILLISECONDS)
    }

    /** Prova di una luce appena collegata: tre colori della festa. */
    fun test(device: PartyLights.Device) {
        listOf(0xFF4F9A, 0x8B6CFF, 0xFFC145).forEachIndexed { index, color ->
            lightsWorker.schedule({ lightsClient.show(device, color, 1f) }, index * 700L, TimeUnit.MILLISECONDS)
        }
    }

    /** Abbinamento (dopo il pulsante): la chiave, o null. Da chiamare fuori dal thread principale. */
    fun pair(device: PartyLights.Device): String? = lightsClient.pair(device)

    /** Stampa la foto; [done] dice se la stampante l'ha accettata. */
    fun print(printer: Ipp.Printer, jpeg: ByteArray, jobName: String, done: (Boolean) -> Unit = {}) {
        printWorker.execute {
            val ok = runCatching { ippClient.print(printer, jpeg, jobName) }
                .onFailure { Log.w(TAG, "Stampa non riuscita", it) }
                .getOrDefault(false)
            done(ok)
        }
    }

    /** La stampante sa stampare le foto da qui (accetta JPEG). Fuori dal thread principale. */
    fun canPrint(printer: Ipp.Printer): Boolean = runCatching { ippClient.format(printer) != null }.getOrDefault(false)

    /** Scontrino della festa con la foto a puntini, chi l'ha mandata e l'ora. */
    fun receipt(host: String, wide: Boolean, photo: ByteArray?, title: String, lines: List<String>, footer: String, done: (Boolean) -> Unit = {}) {
        printWorker.execute {
            val ok = runCatching {
                val paper = if (wide) EscPos.Paper.MM80 else EscPos.Paper.MM58
                val gray = photo?.let { grayFor(it, paper.dots) }
                EscPosPrinter.print(host, EscPos.receipt(title, gray, lines, footer))
            }.onFailure { Log.w(TAG, "Scontrino non stampato", it) }.isSuccess
            done(ok)
        }
    }

    fun close() {
        lightsWorker.shutdownNow()
        printWorker.shutdownNow()
    }

    companion object {
        private const val TAG = "PartyHardware"
        private const val WARM_WHITE = 0xFFC98A
        private const val BRIGHTNESS = 0.85f
        private const val MIN_GAP_MS = 1_000L
        private const val FLASH_MS = 450L

        /** La foto larga quanto la carta, in toni di grigio un po' schiariti (la carta termica scurisce). */
        fun grayFor(jpeg: ByteArray, width: Int): EscPos.Gray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val height = (decoded.height * width.toFloat() / decoded.width).toInt().coerceIn(1, width * 2)
            val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
            if (scaled !== decoded) decoded.recycle()
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            scaled.recycle()
            val gray = ByteArray(pixels.size) { index ->
                val color = pixels[index]
                val luma = 0.299 * (color shr 16 and 0xFF) + 0.587 * (color shr 8 and 0xFF) + 0.114 * (color and 0xFF)
                (luma * 0.85 + 38).toInt().coerceIn(0, 255).toByte()
            }
            return EscPos.Gray(width, height, gray)
        }
    }
}
