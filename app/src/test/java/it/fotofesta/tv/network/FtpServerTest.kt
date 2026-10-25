package it.fotofesta.tv.network

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FtpServerTest {
    private val received = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    private val arrived = CountDownLatch(1)
    private val server = FtpServer("fotofesta", { "123456" }, { name, bytes ->
        received += name to bytes
        arrived.countDown()
    }, port = 0, maxFileBytes = 1_000, allowLoopback = true).apply { start() }

    @After
    fun stop() = server.stop()

    private class Client(port: Int) {
        val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        fun read(): String {
            var line = reader.readLine()
            // Risposte su più righe: "211-..." fino a "211 ...".
            while (line.length > 3 && line[3] == '-') {
                val code = line.take(3)
                do line = reader.readLine() while (!line.startsWith("$code "))
            }
            return line
        }

        fun send(command: String): String {
            socket.getOutputStream().write("$command\r\n".toByteArray())
            return read()
        }
    }

    @Test
    fun `a camera logs in and uploads a photo in passive mode`() {
        val client = Client(server.localPort)
        assertTrue(client.read().startsWith("220"))
        assertTrue(client.send("STOR prima.jpg").startsWith("530"))
        assertTrue(client.send("USER fotofesta").startsWith("331"))
        assertTrue(client.send("PASS 123456").startsWith("230"))
        assertTrue(client.send("FEAT").startsWith("211"))
        assertTrue(client.send("TYPE I").startsWith("200"))
        assertTrue(client.send("CWD DCIM").startsWith("250"))
        assertEquals("257 \"/DCIM\"", client.send("PWD"))
        val pasv = client.send("PASV")
        assertTrue(pasv, pasv.startsWith("227"))
        val numbers = pasv.substringAfter('(').substringBefore(')').split(',').map(String::toInt)
        val data = Socket(InetAddress.getLoopbackAddress(), numbers[4] * 256 + numbers[5])
        assertTrue(client.send("STOR IMG_0001.JPG").startsWith("150"))
        val photo = ByteArray(500) { it.toByte() }
        data.getOutputStream().write(photo)
        data.close()
        assertTrue(client.read().startsWith("226"))
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertEquals("IMG_0001.JPG", received.single().first)
        assertArrayEquals(photo, received.single().second)
        assertTrue(client.send("QUIT").startsWith("221"))
    }

    @Test
    fun `files that are too large are refused`() {
        val client = Client(server.localPort)
        client.read()
        client.send("USER fotofesta")
        client.send("PASS 123456")
        val epsv = client.send("EPSV")
        assertTrue(epsv, epsv.startsWith("229"))
        val port = epsv.substringAfter("|||").substringBefore('|').toInt()
        val data = Socket(InetAddress.getLoopbackAddress(), port)
        assertTrue(client.send("STOR grande.jpg").startsWith("150"))
        data.getOutputStream().write(ByteArray(2_000))
        data.close()
        assertTrue(client.read().startsWith("552"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a wrong password three times closes the connection`() {
        val client = Client(server.localPort)
        client.read()
        client.send("USER fotofesta")
        repeat(3) { assertTrue(client.send("PASS 000000").startsWith("530")) }
        assertEquals(-1, client.socket.getInputStream().read())
    }

    @Test
    fun `active mode only connects back to the camera`() {
        val client = Client(server.localPort)
        client.read()
        client.send("USER fotofesta")
        client.send("PASS 123456")
        assertTrue(client.send("PORT 10,0,0,9,20,20").startsWith("504"))
        assertTrue(client.send("PORT 127,0,0,1,20,20").startsWith("200"))
    }
}
