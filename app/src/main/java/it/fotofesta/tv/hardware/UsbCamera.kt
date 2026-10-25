package it.fotofesta.tv.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fotocamera collegata alla TV con il cavo USB (PTP/MTP, come quando la si collega a un computer):
 * ogni scatto nuovo entra nella festa. Le foto già sulla scheda restano dove sono: si prendono solo
 * quelle scattate dopo il collegamento. Solo JPEG; i file RAW si ignorano.
 */
class UsbCamera(
    private val context: Context,
    private val onPhoto: (ByteArray) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private var registered = false
    @Volatile private var current: MtpDevice? = null
    @Volatile private var poller: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.parcelableDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (isCamera(device)) ask(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (current?.deviceName == device.deviceName) close()
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device)
            }
        }
    }

    @Synchronized
    fun start() {
        val manager = usb ?: return
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        manager.deviceList.values.firstOrNull(::isCamera)?.let(::ask)
    }

    @Synchronized
    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        close()
    }

    private fun ask(device: UsbDevice) {
        val manager = usb ?: return
        if (manager.hasPermission(device)) {
            open(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
        runCatching { manager.requestPermission(device, intent) }
    }

    @Synchronized
    private fun open(device: UsbDevice) {
        if (current != null) return
        val connection = usb?.openDevice(device) ?: return
        val mtp = MtpDevice(device)
        if (!mtp.open(connection)) {
            connection.close()
            return
        }
        current = mtp
        val name = listOfNotNull(mtp.deviceInfo?.manufacturer, mtp.deviceInfo?.model).joinToString(" ").ifBlank { device.productName ?: "USB" }
        onStatus(name)
        poller = Thread({ poll(mtp) }, "usb-camera").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun close() {
        poller?.interrupt()
        poller = null
        current?.let { runCatching { it.close() } }
        current = null
        onStatus(null)
    }

    private fun poll(mtp: MtpDevice) {
        val seen = HashSet<Int>()
        var first = true
        while (current === mtp && !Thread.currentThread().isInterrupted) {
            val handles = runCatching {
                mtp.storageIds?.flatMap { storage -> mtp.getObjectHandles(storage, MtpConstants.FORMAT_EXIF_JPEG, 0)?.toList().orEmpty() }.orEmpty()
            }.getOrElse {
                Log.w(TAG, "Fotocamera non leggibile", it)
                emptyList()
            }
            for (handle in handles) {
                if (!seen.add(handle) || first) continue
                val info = runCatching { mtp.getObjectInfo(handle) }.getOrNull() ?: continue
                val size = info.compressedSizeLong
                if (size <= 0 || size > MAX_PHOTO_BYTES) continue
                runCatching { mtp.getObject(handle, size.toInt()) }.getOrNull()?.let(onPhoto)
            }
            first = false
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private const val TAG = "UsbCamera"
        private const val ACTION_PERMISSION = "it.fotofesta.tv.USB_CAMERA_PERMISSION"
        private const val POLL_MS = 3_000L
        private const val MAX_PHOTO_BYTES = 25L * 1024 * 1024

        /** Una fotocamera: classe "immagini fisse" (PTP) sul dispositivo o su una delle interfacce. */
        fun isCamera(device: UsbDevice): Boolean =
            device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

        @Suppress("DEPRECATION")
        private fun Intent.parcelableDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
