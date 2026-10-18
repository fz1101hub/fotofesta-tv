package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCaptionTest {
    @Test
    fun cameraAndPhoneNamesAreRecognised() {
        listOf(
            "IMG_20240512_153422",
            "PXL_20240512_123456789",
            "DSC01234",
            "DSCF0042",
            "IMG-20240101-WA0001",
            "WhatsApp Image 2024-01-01 at 10.10.10",
            "Screenshot_20240101-101010",
            "20240512_153422",
            "GOPR0123",
            "",
        ).forEach { assertTrue(it, PhotoCaption.looksLikeCameraName(it)) }
    }

    @Test
    fun meaningfulNamesAreKept() {
        listOf("Festa di compleanno", "Estate 2024", "Foto 2024", "Torta della nonna", "Coast at sunset", "Image of the bay").forEach {
            assertFalse(it, PhotoCaption.looksLikeCameraName(it))
        }
    }

    @Test
    fun titleFallsBackToTheFolderName() {
        assertEquals("Festa di compleanno", PhotoCaption.title("Festa di compleanno", "DCIM"))
        assertEquals("Estate 2024", PhotoCaption.title("IMG_20240512_153422", "Estate 2024"))
        assertNull(PhotoCaption.title("IMG_20240512_153422", "100ANDRO"))
        assertNull(PhotoCaption.title("DSC01234", null))
        assertNull(PhotoCaption.title("PXL_20240512_123456789", "Camera"))
        assertNull(PhotoCaption.title("DSC01234", "101NIKON"))
    }

    @Test
    fun exifDatesAreParsed() {
        assertEquals(Triple(2024, 5, 12), PhotoCaption.parseExifDate("2024:05:12 15:34:22"))
        assertEquals(Triple(2023, 12, 31), PhotoCaption.parseExifDate("2023-12-31T23:59:00"))
        assertNull(PhotoCaption.parseExifDate("0000:00:00 00:00:00"))
        assertNull(PhotoCaption.parseExifDate("    :  :     :  :  "))
        assertNull(PhotoCaption.parseExifDate(null))
    }
}
