package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameUtilsTest {

    @Test
    fun splitFileName_keepsExtensionSeparate() {
        val parts = splitFileName("cloud.jpg")

        assertEquals("cloud", parts.stem)
        assertEquals(".jpg", parts.extension)
    }

    @Test
    fun validateRenamedFileName_preservesOriginalExtension() {
        val result = validateRenamedFileName(
            originalFileName = "cloud.jpg",
            requestedStem = "cloud2"
        )

        assertTrue(result.isValid)
        assertEquals("cloud2.jpg", result.completeFileName)
    }

    @Test
    fun validateRenamedFileName_handlesMultipleDots() {
        val result = validateRenamedFileName(
            originalFileName = "summer.trip.photo.jpeg",
            requestedStem = "beach day"
        )

        assertEquals("beach day.jpeg", result.completeFileName)
    }

    @Test
    fun validateRenamedFileName_rejectsPathCharacters() {
        val result = validateRenamedFileName(
            originalFileName = "cloud.jpg",
            requestedStem = "vacation/cloud"
        )

        assertFalse(result.isValid)
    }

    @Test
    fun validateRenamedFileName_rejectsBlankName() {
        val result = validateRenamedFileName(
            originalFileName = "cloud.jpg",
            requestedStem = "   "
        )

        assertFalse(result.isValid)
    }

    @Test
    fun validateRenamedFileName_rejectsUnchangedName() {
        val result = validateRenamedFileName(
            originalFileName = "cloud.jpg",
            requestedStem = "cloud"
        )

        assertFalse(result.isValid)
    }
}
