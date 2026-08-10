package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSessionOperationsTest {
    @Test
    fun reconciliation_removesMissingPhotosAndKeepsCurrentPhotoSelected() {
        val result = reconcilePhotoSession(
            sessionUris = listOf("A", "B", "C"),
            availableUris = setOf("B", "C"),
            oldCurrentIndex = 1
        )

        assertEquals(listOf("B", "C"), result.sessionUris)
        assertEquals(0, result.currentIndex)
        assertEquals(1, result.removedCount)
    }

    @Test
    fun reconciliation_movesToNextAvailablePhotoWhenCurrentWasDeleted() {
        val result = reconcilePhotoSession(
            sessionUris = listOf("A", "B", "C"),
            availableUris = setOf("A", "C"),
            oldCurrentIndex = 1
        )

        assertEquals(listOf("A", "C"), result.sessionUris)
        assertEquals(1, result.currentIndex)
        assertEquals(1, result.removedCount)
    }

    @Test
    fun reconciliation_handlesEveryPhotoBeingUnavailable() {
        val result = reconcilePhotoSession(
            sessionUris = listOf("A", "B"),
            availableUris = emptySet(),
            oldCurrentIndex = 1
        )

        assertEquals(emptyList<String>(), result.sessionUris)
        assertEquals(0, result.currentIndex)
        assertEquals(2, result.removedCount)
    }
}
