package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MosaicTest {
    private val photos = (0 until 5).map { PhotoItem("p$it", "Foto $it", PhotoRef.Resource(it), SourceType.DEMO) }

    private fun ids(list: List<PhotoItem>) = list.map { it.id }

    @Test
    fun `recently shown photos come first, most recent first`() {
        assertEquals(listOf("p1", "p0"), ids(mosaicSidePhotos(photos, 2, listOf("p1", "p0", "p4"))))
    }

    @Test
    fun `without history the following photos fill the gaps`() {
        assertEquals(listOf("p3", "p4"), ids(mosaicSidePhotos(photos, 2, emptyList())))
        assertEquals(listOf("p1", "p0"), ids(mosaicSidePhotos(photos, 4, listOf("p1"))))
    }

    @Test
    fun `the large photo and missing photos are skipped`() {
        assertEquals(listOf("p3", "p1"), ids(mosaicSidePhotos(photos, 0, listOf("p0", "gone", "p3", "p3", "p1"))))
    }

    @Test
    fun `videos never go in the small tiles`() {
        val mixed = photos.map { if (it.id == "p1" || it.id == "p3") it.copy(video = true) else it }
        assertEquals(listOf("p0", "p4"), ids(mosaicSidePhotos(mixed, 2, listOf("p1", "p0"))))
    }

    @Test
    fun `small collections show fewer side photos`() {
        assertEquals(listOf("p1"), ids(mosaicSidePhotos(photos.take(2), 0, emptyList())))
        assertEquals(emptyList<String>(), ids(mosaicSidePhotos(photos.take(1), 0, listOf("p0"))))
        assertEquals(emptyList<String>(), ids(mosaicSidePhotos(emptyList(), 0, emptyList())))
    }
}
