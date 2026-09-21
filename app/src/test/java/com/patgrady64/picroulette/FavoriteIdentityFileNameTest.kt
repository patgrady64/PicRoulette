package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteIdentityFileNameTest {
    private val firstId = "11111111-2222-3333-4444-555555555555"
    private val secondId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun legacyFavorite_getsDurablePrefix() {
        val result = favoriteFileNameWithId(
            favoriteId = firstId,
            existingFileName = "IMG_1234_20260919120000000.jpg"
        )

        assertEquals(
            "PRF_${firstId}_IMG_1234_20260919120000000.jpg",
            result
        )
        assertEquals(firstId, favoriteIdFromFileName(result))
    }

    @Test
    fun existingMatchingId_isLeftAlone() {
        val original = "PRF_${firstId}_IMG_1234.jpg"

        assertEquals(
            original,
            favoriteFileNameWithId(firstId, original)
        )
    }

    @Test
    fun wrongExistingId_isReplacedInsteadOfStacked() {
        val result = favoriteFileNameWithId(
            favoriteId = secondId,
            existingFileName = "PRF_${firstId}_IMG_1234.jpg"
        )

        assertEquals(
            "PRF_${secondId}_IMG_1234.jpg",
            result
        )
        assertFalse(result.contains(firstId))
        assertEquals(secondId, favoriteIdFromFileName(result))
    }

    @Test
    fun durableIdParser_ignoresLegacyNames() {
        assertTrue(favoriteIdFromFileName("PR_FAV_IMG_1234.jpg").isBlank())
    }
}
