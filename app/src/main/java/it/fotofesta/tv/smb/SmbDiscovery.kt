package it.fotofesta.tv.smb

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/** Computer con le cartelle condivise trovato nella rete di casa. */
data class SmbHost(val name: String, val address: String, val port: Int = SmbLocation.DEFAULT_PORT)

/**
 * Ricerca dei computer e dei NAS che annunciano le cartelle condivise con Bonjour (mDNS,
 * "_smb._tcp"): Mac, NAS Synology, QNAP, Linux con Samba. È una domanda "una tantum" da una porta
 * qualsiasi, a cui i dispositivi rispondono direttamente alla TV: non serve ascoltare il multicast.
 * I PC Windows non si annunciano così: si aggiungono scrivendo l'indirizzo.
 */
object SmbDiscovery {
    const val SERVICE = "_smb._tcp.local"
    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_SRV = 33
    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val MAX_PACKET = 9_000

    /** Cerca per [timeoutMs]; la domanda parte due volte, perché un pacchetto UDP può perdersi. */
    fun search(timeoutMs: Int = 2_500, target: InetSocketAddress = InetSocketAddress(MDNS_GROUP, MDNS_PORT)): List<SmbHost> {
        val query = query(SERVICE)
        val replies = mutableListOf<Pair<ByteArray, String>>()
        // Porta qualsiasi: chi risponde manda la risposta direttamente qui, senza multicast.
        MulticastSocket(0).use { socket ->
            socket.timeToLive = 255
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            var sent = 0
            val buffer = ByteArray(MAX_PACKET)
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                if (sent < 2 && now >= nextSend) {
                    socket.send(DatagramPacket(query, query.size, target))
                    sent++
                    nextSend = now + 700
                }
                socket.soTimeout = (minOf(deadline, if (sent < 2) nextSend else deadline) - now).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                replies += packet.data.copyOf(packet.length) to (packet.address.hostAddress ?: continue)
            }
        }
        return hosts(replies)
    }

    /** Computer dalle risposte ricevute (pacchetto e indirizzo di chi l'ha mandato), per nome. */
    fun hosts(replies: List<Pair<ByteArray, String>>): List<SmbHost> {
        val found = LinkedHashMap<String, SmbHost>()
        for ((packet, sender) in replies) {
            val records = runCatching { parse(packet) }.getOrNull() ?: continue
            val addresses = records.filter { it.type == TYPE_A && it.data.size == 4 }
                .associate { it.name.lowercase() to InetAddress.getByAddress(it.data).hostAddress }
            for (pointer in records.filter { it.type == TYPE_PTR && it.name.equals(SERVICE, ignoreCase = true) }) {
                val instance = pointer.target ?: continue
                val srv = records.firstOrNull { it.type == TYPE_SRV && it.name.equals(instance.name, ignoreCase = true) }
                // L'indirizzo è nel record A del nome nel SRV; senza, è chi ha risposto.
                val address = srv?.target?.let { addresses[it.name.lowercase()] } ?: sender
                val host = SmbHost(instance.labels.firstOrNull().orEmpty().ifBlank { address }, address, srv?.port ?: SmbLocation.DEFAULT_PORT)
                found.putIfAbsent("${host.address}:${host.port}", host)
            }
        }
        return found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Domanda PTR per [service], con la risposta chiesta direttamente alla TV (bit "QU"). */
    fun query(service: String): ByteArray = ByteArrayOutputStream().apply {
        write(ByteArray(4)) // identificativo 0, flag 0 (domanda standard)
        write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)) // una domanda
        for (label in service.split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            write(bytes.size)
            write(bytes)
        }
        write(0)
        write(byteArrayOf(0, TYPE_PTR.toByte(), 0x80.toByte(), 1)) // PTR, classe IN con bit QU
    }.toByteArray()

    /** Nome DNS come etichette: il nome di un servizio può contenere punti nella prima. */
    class Name(val labels: List<String>) {
        val name: String get() = labels.joinToString(".")
    }

    class Record(val name: String, val type: Int, val data: ByteArray, val target: Name?, val port: Int?)

    /** Record di risposta, autorità e aggiuntivi di un pacchetto DNS. */
    fun parse(packet: ByteArray): List<Record> {
        val reader = Reader(packet)
        reader.position = 4
        val questions = reader.u16()
        val count = reader.u16() + reader.u16() + reader.u16()
        repeat(questions) {
            reader.name()
            reader.position += 4
        }
        return List(count) {
            val name = reader.name()
            val type = reader.u16()
            reader.u16() // classe
            reader.position += 4 // TTL
            val length = reader.u16()
            val start = reader.position
            require(start + length <= packet.size) { "Record troncato" }
            val record = when (type) {
                TYPE_PTR -> Record(name.name, type, ByteArray(0), reader.name(), null)
                TYPE_SRV -> {
                    reader.position += 4 // priorità e peso
                    val port = reader.u16()
                    Record(name.name, type, ByteArray(0), reader.name(), port)
                }
                else -> Record(name.name, type, packet.copyOfRange(start, start + length), null, null)
            }
            reader.position = start + length
            record
        }
    }

    private class Reader(val bytes: ByteArray) {
        var position = 0

        fun u8(): Int {
            require(position < bytes.size) { "Pacchetto troncato" }
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        /** Nome con la compressione DNS; i rimandi possono solo tornare indietro, così non si gira in tondo. */
        fun name(): Name {
            val labels = mutableListOf<String>()
            var cursor = position
            var resume = -1
            var limit = bytes.size
            while (true) {
                require(cursor < bytes.size) { "Nome troncato" }
                val length = bytes[cursor].toInt() and 0xFF
                when {
                    length == 0 -> {
                        cursor++
                        break
                    }
                    length and 0xC0 == 0xC0 -> {
                        require(cursor + 1 < bytes.size) { "Rimando troncato" }
                        val target = ((length and 0x3F) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
                        require(target < limit) { "Rimando non valido" }
                        if (resume < 0) resume = cursor + 2
                        limit = target
                        cursor = target
                    }
                    length and 0xC0 != 0 -> throw IllegalArgumentException("Etichetta non valida")
                    else -> {
                        require(cursor + 1 + length <= bytes.size) { "Etichetta troncata" }
                        labels += String(bytes, cursor + 1, length, Charsets.UTF_8)
                        require(labels.size <= 128) { "Nome troppo lungo" }
                        cursor += 1 + length
                    }
                }
            }
            position = if (resume >= 0) resume else cursor
            return Name(labels)
        }
    }
}
