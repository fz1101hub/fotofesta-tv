package it.fotofesta.tv.guests

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pagine in più del server della festa, oltre a quella dei telefoni: il QR da mettere all'ingresso
 * ("sono arrivato") e la parete delle foto per un secondo schermo (Echo Show, tablet, PC).
 * Tutte con la stessa chiave del QR della festa.
 */
interface PartyExtras {
    /** Pagina da stampare o da lasciare su un tablet all'ingresso, con il QR degli arrivi. */
    fun doorPage(): String

    /** Parete delle foto: slideshow delle foto approvate degli invitati, dediche e QR. */
    fun wallPage(): String

    /** Stato per la parete: ultime foto approvate, dediche, annuncio, titolo della festa. */
    fun wallState(): String

    /** Foto approvata [id] per la parete; null se non c'è o non è approvata. */
    fun wallPhoto(id: String): ByteArray?
}

/** QR in SVG, per le pagine servite ai telefoni e agli altri schermi (nessuna immagine da generare). */
object QrSvg {
    fun encode(value: String, margin: Int = 2): String {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width + margin * 2
        val path = buildString {
            for (y in 0 until matrix.height) {
                var x = 0
                while (x < matrix.width) {
                    if (!matrix[x, y]) { x++; continue }
                    // Moduli neri consecutivi della riga in un solo rettangolo: l'SVG resta piccolo.
                    val start = x
                    while (x < matrix.width && matrix[x, y]) x++
                    append('M').append(start + margin).append(' ').append(y + margin)
                    append('h').append(x - start).append("v1h-").append(x - start).append('z')
                }
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
            """<rect width="$size" height="$size" fill="#fff"/><path fill="#000" d="$path"/></svg>"""
    }
}
