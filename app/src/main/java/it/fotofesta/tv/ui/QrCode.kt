package it.fotofesta.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun QrCode(value: String, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(value) { createQrBitmap(value) }
    Box(modifier.background(Color.White).padding(12.dp)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            // Ingrandimento senza interpolazione: i moduli restano netti e facili da leggere.
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(220.dp),
        )
    }
}

/** Genera il QR a un pixel per modulo (il bordo bianco è dato dal padding) con un'unica scrittura di pixel. */
private fun createQrBitmap(value: String): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height) { index ->
        if (matrix[index % width, index / width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
