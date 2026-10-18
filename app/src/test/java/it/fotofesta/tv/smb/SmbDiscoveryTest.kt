package it.fotofesta.tv.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class SmbDiscoveryTest {
    /** Risposta come quella di Avahi o di un Mac: PTR, poi SRV, TXT e A tra i record aggiuntivi. */
    private fun answer(instance: String, target: String, port: Int, address: ByteArray?, echoQuestion: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        fun u16(value: Int) {
            out.write(value shr 8)
            out.write(value and 0xFF)
        }
        fun name(labels: List<String>) {
            for (index in labels.indices) {
                val suffix = labels.drop(index).joinToString(".")
                val known = offsets[suffix]
                if (known != null) {
                    u16(0xC000 or known)
                    return
                }
                offsets[suffix] = out.size()
                val bytes = labels[index].toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
        }
        fun record(owner: List<String>, type: Int, body: () -> Unit) {
            name(owner)
            u16(type)
            u16(1)
            u16(0)
            u16(120)
            val lengthAt = out.size()
            u16(0)
            val start = out.size()
            body()
            val bytes = out.toByteArray()
            val length = bytes.size - start
            bytes[lengthAt] = (length shr 8).toByte()
            bytes[lengthAt + 1] = length.toByte()
            out.reset()
            out.write(bytes)
        }
        val service = listOf("_smb", "_tcp", "local")
        val instanceName = listOf(instance) + service
        val targetName = target.split('.')
        u16(0)
        u16(0x8400)
        u16(if (echoQuestion) 1 else 0)
        u16(1)
        u16(0)
        u16(if (address != null) 3 else 2)
        if (echoQuestion) {
            name(service)
            u16(12)
            u16(1)
        }
        record(service, 12) { name(instanceName) }
        record(instanceName, 33) {
            u16(0)
            u16(0)
            u16(port)
            name(targetName)
        }
        record(instanceName, 16) { out.write(byteArrayOf(0)) }
        if (address != null) record(targetName, 1) { out.write(address) }
        return out.toByteArray()
    }

    @Test
    fun queryAsksForSmbServicesWithUnicastReply() {
        val query = SmbDiscovery.query(SmbDiscovery.SERVICE)
        val expected = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(4) + "_smb".toByteArray() + byteArrayOf(4) + "_tcp".toByteArray() + byteArrayOf(5) + "local".toByteArray() +
            byteArrayOf(0, 0, 12, 0x80.toByte(), 1)
        assertArrayEquals(expected, query)
    }

    @Test
    fun answersBecomeHostsWithNameAddressAndPort() {
        val nas = answer("NAS di casa (Synology)", "DiskStation.local", 445, byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        val mac = answer("MacBook di Anna", "MacBook-di-Anna.local", 4450, null)
        val hosts = SmbDiscovery.hosts(listOf(nas to "192.168.1.50", mac to "192.168.1.77", nas to "192.168.1.50"))
        assertEquals(
            listOf(
                SmbHost("MacBook di Anna", "192.168.1.77", 4450),
                SmbHost("NAS di casa (Synology)", "192.168.1.50", 445),
            ),
            hosts,
        )
    }

    @Test
    fun recordAddressWinsOverTheSender() {
        val reply = answer("NAS.lan", "nas.local", 445, byteArrayOf(10, 0, 0, 9), echoQuestion = false)
        assertEquals(listOf(SmbHost("NAS.lan", "10.0.0.9", 445)), SmbDiscovery.hosts(listOf(reply to "10.0.0.200")))
    }

    @Test
    fun brokenPacketsAreIgnored() {
        val good = answer("NAS", "nas.local", 445, byteArrayOf(10, 0, 0, 9))
        val truncated = good.copyOf(good.size - 3)
        val loop = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0, 0, 0, 0)
        val hosts = SmbDiscovery.hosts(listOf(truncated to "10.0.0.9", loop to "10.0.0.8", ByteArray(3) to "10.0.0.7", good to "10.0.0.9"))
        assertEquals(listOf(SmbHost("NAS", "10.0.0.9", 445)), hosts)
    }

    @Test
    fun searchSendsTheQueryAndCollectsReplies() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { responder ->
            val queries = mutableListOf<ByteArray>()
            val worker = thread {
                val buffer = ByteArray(1500)
                responder.soTimeout = 3_000
                runCatching {
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        responder.receive(packet)
                        synchronized(queries) { queries += packet.data.copyOf(packet.length) }
                        val reply = answer("NAS", "nas.local", 445, null)
                        responder.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    }
                }
            }
            val hosts = SmbDiscovery.search(timeoutMs = 1_200, target = InetSocketAddress(InetAddress.getLoopbackAddress(), responder.localPort))
            responder.close()
            worker.join()
            assertEquals(listOf(SmbHost("NAS", "127.0.0.1", 445)), hosts)
            // La domanda si ripete una volta, per i pacchetti persi.
            assertEquals(2, queries.size)
            assertTrue(queries.all { it.contentEquals(SmbDiscovery.query(SmbDiscovery.SERVICE)) })
        }
    }
}
