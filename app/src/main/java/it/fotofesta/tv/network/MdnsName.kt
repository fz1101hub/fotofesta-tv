package it.fotofesta.tv.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Risponde in mDNS al nome [name] ("festa.local") con l'indirizzo della TV, così chi non può
 * inquadrare il QR scrive un indirizzo facile nel browser. Solo record A, solo nella rete locale;
 * le domande per altri nomi si ignorano. Il lavoro sui pacchetti è in [MdnsPackets], senza Android.
 */
class MdnsName(private val context: Context, private val name: String) {
    @Volatile private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start(address: Inet4Address) {
        stop()
        val group = InetAddress.getByName(MDNS_GROUP)
        val created = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                NetworkInterface.getByInetAddress(address)?.let { networkInterface = it }
                joinGroup(group)
                timeToLive = 255
            }
        } catch (failure: Exception) {
            Log.w(TAG, "mDNS non disponibile", failure)
            return
        }
        lock = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("fotofesta-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        socket = created
        thread = Thread({ loop(created, address, group) }, "mdns-name").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        thread = null
        runCatching { lock?.release() }
        lock = null
    }

    private fun loop(socket: MulticastSocket, address: Inet4Address, group: InetAddress) {
        val buffer = ByteArray(1500)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return
            } catch (failure: Exception) {
                Log.w(TAG, "mDNS: ricezione non riuscita", failure)
                return
            }
            val query = buffer.copyOf(packet.length)
            val unicast = MdnsPackets.asksFor(query, name) ?: continue
            val answer = MdnsPackets.answer(name, address.address)
            runCatching {
                // Con la domanda "QU" si risponde direttamente a chi l'ha fatta.
                val target = if (unicast) packet.socketAddress else InetSocketAddress(group, MDNS_PORT)
                socket.send(DatagramPacket(answer, answer.size, target))
            }
        }
    }

    private companion object {
        const val TAG = "MdnsName"
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
    }
}

/** Domande e risposte mDNS per un solo nome, in byte (RFC 6762). */
object MdnsPackets {
    private const val TYPE_A = 1
    private const val TYPE_ANY = 255
    private const val CLASS_IN = 1
    private const val TTL_SECONDS = 120

    /**
     * Se il pacchetto è una domanda per [name] (A o ANY, classe IN), dice se chiede una risposta
     * diretta (bit "QU"); null se non riguarda questo nome o non è una domanda.
     */
    fun asksFor(packet: ByteArray, name: String): Boolean? {
        if (packet.size < 12) return null
        val flags = u16(packet, 2)
        if (flags and 0x8000 != 0) return null // è una risposta
        val questions = u16(packet, 4)
        var offset = 12
        repeat(questions) {
            val (qname, next) = readName(packet, offset) ?: return null
            if (next + 4 > packet.size) return null
            val type = u16(packet, next)
            val klass = u16(packet, next + 2)
            offset = next + 4
            if (qname.equals(name, ignoreCase = true) && (type == TYPE_A || type == TYPE_ANY) && (klass and 0x7FFF) == CLASS_IN) {
                return klass and 0x8000 != 0
            }
        }
        return null
    }

    /** Risposta autorevole con un record A per [name]. */
    fun answer(name: String, ipv4: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(value: Int) { out.write(value shr 8 and 0xFF); out.write(value and 0xFF) }
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(0)
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(TYPE_A)
        u16(0x8000 or CLASS_IN) // "cache flush": questo nome è solo nostro
        u16(TTL_SECONDS shr 16); u16(TTL_SECONDS and 0xFFFF)
        u16(4)
        out.write(ipv4, 0, 4)
        return out.toByteArray()
    }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    /** Nome a partire da [start], con i puntatori di compressione; restituisce il nome e dove finisce. */
    private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var offset = start
        var end = -1
        var jumps = 0
        while (true) {
            if (offset >= packet.size) return null
            val length = packet[offset].toInt() and 0xFF
            when {
                length == 0 -> {
                    if (end < 0) end = offset + 1
                    return labels.joinToString(".") to end
                }
                length and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= packet.size || ++jumps > 10) return null
                    if (end < 0) end = offset + 2
                    offset = ((length and 0x3F) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                }
                else -> {
                    if (offset + 1 + length > packet.size) return null
                    labels += String(packet, offset + 1, length, Charsets.UTF_8)
                    offset += 1 + length
                }
            }
        }
    }
}
