package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RouletteDeckTest {
    @Test
    fun reshuffle_preservesEveryDeckEntryExactlyOnce() {
        val deck = (1..50).toMutableList()
        val before = deck.groupingBy { it }.eachCount()

        reshuffleDeckAvoidingBoundaryRepeat(deck)

        assertEquals(before, deck.groupingBy { it }.eachCount())
    }

    @Test
    fun reshuffle_doesNotRepeatPreviousLastAtNewDeckBoundary() {
        repeat(100) {
            val deck = (1..20).toMutableList()
            val previousLast = deck.last()

            reshuffleDeckAvoidingBoundaryRepeat(deck)

            assertNotEquals(previousLast, deck.first())
        }
    }

    @Test
    fun singlePhotoDeck_isSafe() {
        val deck = mutableListOf(7)
        reshuffleDeckAvoidingBoundaryRepeat(deck)
        assertEquals(listOf(7), deck)
    }
}
