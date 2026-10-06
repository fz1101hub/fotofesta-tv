package it.fotofesta.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `numbers are compared by value`() {
        val names = listOf("IMG_10.jpg", "img_2.jpg", "IMG_1.jpg", "IMG_002.jpg", "Vacanze 2024", "Vacanze 2023")
        assertEquals(
            listOf("IMG_1.jpg", "IMG_002.jpg", "img_2.jpg", "IMG_10.jpg", "Vacanze 2023", "Vacanze 2024"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `comparison is consistent for equal and prefix names`() {
        assertEquals(0, NaturalOrder.compare("a1", "a1"))
        assertEquals(true, NaturalOrder.compare("a", "a1") < 0)
        assertEquals(true, NaturalOrder.compare("foto99999999999999999999", "foto100000000000000000000") < 0)
    }
}
