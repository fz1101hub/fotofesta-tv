package it.fotofesta.tv.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionPhotoTest {
    @get:Rule val folder = TemporaryFolder()

    private val video = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray() + ByteArray(5_000) { (it % 251).toByte() }

    private fun jpeg(xmp: String, tail: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + xmp.toByteArray() + ByteArray(3_000) + byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + tail

    @Test
    fun `pixel micro video offset counts from the end`() {
        val bytes = jpeg("""<x GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${video.size}"/>""", video)
        val file = folder.newFile("pixel.jpg").apply { writeBytes(bytes) }
        val clip = MotionPhoto.find(file)!!
        assertEquals((bytes.size - video.size).toLong(), clip.offset)
        val target = folder.newFile("pixel.mp4")
        assertTrue(MotionPhoto.extract(file, target))
        assertArrayEquals(video, target.readBytes())
    }

    @Test
    fun `motion photo container item length is read`() {
        val xmp = """<Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${video.size}"/>"""
        val bytes = jpeg(xmp, video)
        val file = folder.newFile("mp.jpg").apply { writeBytes(bytes) }
        assertEquals(video.size.toLong(), MotionPhoto.find(file)!!.length)
    }

    @Test
    fun `samsung marker precedes the video`() {
        val bytes = jpeg("", "MotionPhoto_Data".toByteArray() + video)
        val file = folder.newFile("samsung.jpg").apply { writeBytes(bytes) }
        val clip = MotionPhoto.find(file)!!
        assertEquals(video.size.toLong(), clip.length)
    }

    @Test
    fun `ordinary photos have no clip`() {
        val file = folder.newFile("plain.jpg").apply { writeBytes(jpeg("", ByteArray(10))) }
        assertNull(MotionPhoto.find(file))
        val png = folder.newFile("x.png").apply { writeBytes(ByteArray(4_000)) }
        assertNull(MotionPhoto.find(png))
    }
}
