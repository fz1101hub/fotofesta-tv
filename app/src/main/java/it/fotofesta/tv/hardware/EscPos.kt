package it.fotofesta.tv.hardware

import it.fotofesta.tv.network.LanHttp
import it.fotofesta.tv.network.NotLanAddressException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Scontrini della festa su una stampante termica di rete (ESC/POS, porta 9100): intestazione, la
 * foto in bianco e nero a puntini, chi l'ha mandata e l'ora. Qui solo i byte, senza Android: la
 * foto arriva già ridotta in toni di grigio.
 */
object EscPos {
    const val PORT = 9100

    /** Larghezza in punti: 384 per i rotoli da 58 mm, 576 per quelli da 80 mm. */
    enum class Paper(val dots: Int) { MM58(384), MM80(576) }

    /** Foto in toni di grigio, un byte per punto (0 nero, 255 bianco), riga per riga. */
    class Gray(val width: Int, val height: Int, val pixels: ByteArray) {
        init { require(pixels.size == width * height) }
    }

    /** Lo scontrino completo, da mandare così com'è alla stampante. */
    fun receipt(title: String, photo: Gray?, lines: List<String>, footer: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)
        out.write(byteArrayOf(ESC, 0x61, 1)) // centrato
        out.write(byteArrayOf(GS, 0x21, 0x11)) // doppia altezza e larghezza
        out.write(text(title))
        out.write(LF.toInt())
        out.write(byteArrayOf(GS, 0x21, 0x00))
        out.write(LF.toInt())
        photo?.let {
            out.write(rasterOf(it))
            out.write(LF.toInt())
        }
        lines.forEach { line ->
            out.write(text(line))
            out.write(LF.toInt())
        }
        out.write(LF.toInt())
        out.write(text(footer))
        out.write(byteArrayOf(LF, LF, LF, LF))
        out.write(byteArrayOf(GS, 0x56, 0x42, 0x00)) // taglio parziale, dove c'è la taglierina
        return out.toByteArray()
    }

    /** Floyd–Steinberg: true = punto nero. I bianchi restano puliti, i grigi diventano puntini. */
    fun dither(photo: Gray): BooleanArray {
        val w = photo.width
        val h = photo.height
        val values = FloatArray(w * h) { (photo.pixels[it].toInt() and 0xFF).toFloat() }
        val black = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val index = y * w + x
            val old = values[index]
            val new = if (old < 128f) 0f else 255f
            black[index] = new == 0f
            val error = old - new
            if (x + 1 < w) values[index + 1] += error * 7 / 16
            if (y + 1 < h) {
                if (x > 0) values[index + w - 1] += error * 3 / 16
                values[index + w] += error * 5 / 16
                if (x + 1 < w) values[index + w + 1] += error * 1 / 16
            }
        }
        return black
    }

    /** Immagine raster (GS v 0): righe di byte, un bit per punto, il più alto a sinistra. */
    fun raster(black: BooleanArray, width: Int, height: Int): ByteArray {
        val bytesPerRow = (width + 7) / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(GS, 0x76, 0x30, 0x00))
        out.write(bytesPerRow and 0xFF); out.write(bytesPerRow shr 8 and 0xFF)
        out.write(height and 0xFF); out.write(height shr 8 and 0xFF)
        for (y in 0 until height) for (column in 0 until bytesPerRow) {
            var byte = 0
            for (bit in 0 until 8) {
                val x = column * 8 + bit
                if (x < width && black[y * width + x]) byte = byte or (0x80 shr bit)
            }
            out.write(byte)
        }
        return out.toByteArray()
    }

    /** Raster di una foto a puntini con le sue dimensioni. */
    fun rasterOf(photo: Gray): ByteArray = raster(dither(photo), photo.width, photo.height)

    /**
     * Testo per la tabella di caratteri predefinita delle stampanti (PC437): le lettere accentate
     * che non ci sono perdono l'accento invece di diventare simboli strani.
     */
    fun text(value: String): ByteArray {
        val plain = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.map { char -> if (char.code in 0x20..0x7E) char.code.toByte() else '?'.code.toByte() }.toByteArray()
    }

    private const val ESC: Byte = 0x1B
    private const val GS: Byte = 0x1D
    private const val LF: Byte = 0x0A
    private val INIT = byteArrayOf(ESC, 0x40)
}

/** Manda lo scontrino alla stampante termica, solo nella rete di casa. */
object EscPosPrinter {
    fun print(host: String, bytes: ByteArray, port: Int = EscPos.PORT) {
        val address = InetAddress.getAllByName(host).firstOrNull(LanHttp::isLanAddress) ?: throw NotLanAddressException(host)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), 4_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }
    }
}
