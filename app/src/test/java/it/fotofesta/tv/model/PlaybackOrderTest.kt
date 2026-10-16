package it.fotofesta.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PlaybackOrderTest {
    @Test
    fun `sequential order wraps around in both directions`() {
        val order = PlaybackOrder().apply { reset(3, shuffle = false) }
        assertEquals(1, order.next(0))
        assertEquals(0, order.next(2))
        assertEquals(2, order.previous(0))
    }

    @Test
    fun `shuffle visits every photo once per cycle starting from current`() {
        val order = PlaybackOrder(Random(42)).apply { reset(10, shuffle = true, current = 7) }
        val seen = mutableListOf(7)
        var current = 7
        repeat(9) {
            current = order.next(current)
            seen += current
        }
        assertEquals((0 until 10).toSet(), seen.toSet())
        assertEquals(10, seen.size)
    }

    @Test
    fun `previous in shuffle returns the photo shown before`() {
        val order = PlaybackOrder(Random(1)).apply { reset(8, shuffle = true, current = 0) }
        val first = order.next(0)
        val second = order.next(first)
        assertEquals(first, order.previous(second))
        assertEquals(0, order.previous(first))
    }

    @Test
    fun `new shuffle cycle never repeats the last photo immediately`() {
        repeat(50) { seed ->
            val order = PlaybackOrder(Random(seed)).apply { reset(3, shuffle = true, current = 0) }
            var current = 0
            repeat(30) {
                val next = order.next(current)
                assertNotEquals(current, next)
                current = next
            }
        }
    }

    @Test
    fun `single photo and empty collection are stable`() {
        val single = PlaybackOrder().apply { reset(1, shuffle = true) }
        assertEquals(0, single.next(0))
        assertEquals(0, single.previous(0))
        assertNull(single.peekNext(0))
        val empty = PlaybackOrder().apply { reset(0, shuffle = false) }
        assertEquals(0, empty.next(0))
    }

    @Test
    fun `peek matches next within a cycle`() {
        val order = PlaybackOrder(Random(3)).apply { reset(5, shuffle = true, current = 2) }
        val peeked = order.peekNext(2)
        assertEquals(peeked, order.next(2))
    }

    @Test
    fun `previous after a reshuffle returns the photo actually shown before`() {
        repeat(30) { seed ->
            val order = PlaybackOrder(Random(seed)).apply { reset(4, shuffle = true, current = 0) }
            var current = 0
            repeat(3) { current = order.next(current) }
            val lastOfCycle = current
            val firstOfNewCycle = order.next(lastOfCycle)
            assertEquals(lastOfCycle, order.previous(firstOfNewCycle))
        }
    }

    @Test
    fun `peek has no side effects`() {
        val order = PlaybackOrder(Random(5)).apply { reset(6, shuffle = true, current = 1) }
        assertNull(order.peekNext(42))
        val expected = order.peekNext(1)
        assertEquals(expected, order.next(1))
    }
}
