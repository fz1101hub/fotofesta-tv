package it.fotofesta.tv.guests

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Archivio ZIP senza compressione (le foto JPEG non si comprimono), scritto direttamente sulla
 * connessione: la lunghezza si conosce prima, quindi il telefono vede l'avanzamento. Ogni voce ha
 * CRC e dimensione già calcolati, così non servono descrittori dopo i dati, che alcuni programmi
 * di estrazione non accettano per le voci non compresse.
 */
object ZipStream {
    /** Voce dell'archivio: [open] dà i byte al momento della scrittura (null se non c'è più). */
    class Entry(val name: String, val size: Long, val crc: Long, val open: () -> InputStream?)

    fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** CRC di un file letto a pezzi, per i video. */
    fun crcOf(input: InputStream): Long {
        val crc = CRC32()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            crc.update(buffer, 0, read)
        }
        return crc.value
    }

    /** Byte totali dell'archivio con queste voci. */
    fun length(entries: List<Entry>): Long {
        var total = 0L
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8).size
            total += LOCAL_HEADER + name + entry.size + CENTRAL_HEADER + name
        }
        return total + END_RECORD
    }

    /**
     * Scrive l'archivio. Una voce che nel frattempo non si legge più (foto tolta da chi l'ha
     * mandata) viene scritta piena di zeri: la lunghezza annunciata deve restare quella.
     */
    fun write(entries: List<Entry>, output: OutputStream) {
        val offsets = LongArray(entries.size)
        var offset = 0L
        entries.forEachIndexed { index, entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            offsets[index] = offset
            output.write(localHeader(name, entry))
            output.write(name)
            copyExactly(entry, output)
            offset += LOCAL_HEADER + name.size + entry.size
        }
        val centralStart = offset
        var centralSize = 0L
        entries.forEachIndexed { index, entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            output.write(centralHeader(name, entry, offsets[index]))
            output.write(name)
            centralSize += CENTRAL_HEADER + name.size
        }
        output.write(endRecord(entries.size, centralSize, centralStart))
    }

    /** Esattamente [Entry.size] byte: se la voce è sparita o più corta si completa con zeri. */
    private fun copyExactly(entry: Entry, output: OutputStream) {
        var left = entry.size
        val buffer = ByteArray(64 * 1024)
        runCatching { entry.open() }.getOrNull()?.use { input ->
            while (left > 0) {
                val read = runCatching { input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()) }.getOrDefault(-1)
                if (read < 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
        java.util.Arrays.fill(buffer, 0)
        while (left > 0) {
            val chunk = minOf(buffer.size.toLong(), left).toInt()
            output.write(buffer, 0, chunk)
            left -= chunk
        }
    }

    private fun localHeader(name: ByteArray, entry: Entry) = Bytes(LOCAL_HEADER).apply {
        int(0x04034b50); short(20); short(UTF8_FLAG); short(0); short(0); short(DOS_DATE)
        int(entry.crc.toInt()); int(entry.size.toInt()); int(entry.size.toInt()); short(name.size); short(0)
    }.bytes

    private fun centralHeader(name: ByteArray, entry: Entry, offset: Long) = Bytes(CENTRAL_HEADER).apply {
        int(0x02014b50); short(20); short(20); short(UTF8_FLAG); short(0); short(0); short(DOS_DATE)
        int(entry.crc.toInt()); int(entry.size.toInt()); int(entry.size.toInt()); short(name.size); short(0); short(0)
        short(0); short(0); int(0); int(offset.toInt())
    }.bytes

    private fun endRecord(count: Int, centralSize: Long, centralStart: Long) = Bytes(END_RECORD).apply {
        int(0x06054b50); short(0); short(0); short(count); short(count); int(centralSize.toInt()); int(centralStart.toInt()); short(0)
    }.bytes

    private class Bytes(size: Int) {
        val bytes = ByteArray(size)
        private var at = 0
        fun short(value: Int) {
            bytes[at++] = value.toByte()
            bytes[at++] = (value shr 8).toByte()
        }
        fun int(value: Int) {
            short(value and 0xFFFF)
            short(value ushr 16)
        }
    }

    /** Nomi in UTF-8 (bit 11). */
    private const val UTF8_FLAG = 1 shl 11
    /** 1 gennaio 2000: la data vera non serve e terrebbe traccia dell'ora della festa. */
    private const val DOS_DATE = (20 shl 9) or (1 shl 5) or 1
    private const val LOCAL_HEADER = 30
    private const val CENTRAL_HEADER = 46
    private const val END_RECORD = 22

    /** Oltre questi limiti servirebbe ZIP64: la TV non arriva mai a tanto. */
    const val MAX_ENTRIES = 65_000
    const val MAX_BYTES = 0xFFFF_FFF0L
}
