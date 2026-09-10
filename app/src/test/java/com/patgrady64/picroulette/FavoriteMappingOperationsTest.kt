package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteMappingOperationsTest {
    private val mapping = FavoriteMapping(
        originalUri = "content://original/1",
        favoriteUri = "content://favorite/old",
        originalFileName = "photo.jpg",
        originalRelativePath = "Pictures/Test",
        originalSha256 = "abc123",
        dateAdded = 1234L
    )

    @Test
    fun replacement_changesOnlyFavoriteUri() {
        val result = replaceFavoriteMappingUri(
            mappings = listOf(mapping),
            oldFavoriteUri = "content://favorite/old",
            newFavoriteUri = "content://favorite/new"
        )

        assertTrue(result.changed)
        assertEquals(
            mapping.copy(favoriteUri = "content://favorite/new"),
            result.mappings.single()
        )
    }

    @Test
    fun unrelatedMapping_isNotChanged() {
        val result = replaceFavoriteMappingUri(
            mappings = listOf(mapping),
            oldFavoriteUri = "content://favorite/missing",
            newFavoriteUri = "content://favorite/new"
        )

        assertFalse(result.changed)
        assertEquals(listOf(mapping), result.mappings)
    }

    @Test
    fun folderMigration_repairsEveryMovedFavoriteConnection() {
        val other = mapping.copy(
            originalUri = "content://original/2",
            favoriteUri = "content://favorite/old-2"
        )

        val result = remapFavoriteUris(
            mappings = listOf(mapping, other),
            uriChanges = mapOf(
                "content://favorite/old" to "content://new-folder/1",
                "content://favorite/old-2" to "content://new-folder/2"
            )
        )

        assertTrue(result.changed)
        assertEquals("content://new-folder/1", result.mappings[0].favoriteUri)
        assertEquals("content://new-folder/2", result.mappings[1].favoriteUri)
        assertEquals(mapping.originalUri, result.mappings[0].originalUri)
        assertEquals(other.originalUri, result.mappings[1].originalUri)
    }

    @Test
    fun folderMigration_keepsUnmatchedMappingsUnchanged() {
        val result = remapFavoriteUris(
            mappings = listOf(mapping),
            uriChanges = mapOf("content://different" to "content://new")
        )

        assertFalse(result.changed)
        assertEquals(listOf(mapping), result.mappings)
    }
    @Test
    fun staleCleanup_doesNotDeleteMappingAddedAfterRefreshStarted() {
        val oldMissing = mapping
        val newlyAdded = mapping.copy(
            originalUri = "content://original/2",
            favoriteUri = "content://favorite/newer"
        )

        val result = cleanStaleFavoriteMappings(
            mappingsAtRefreshStart = listOf(oldMissing),
            latestMappings = listOf(oldMissing, newlyAdded),
            diskFavoriteUris = setOf("content://favorite/newer")
        )

        assertTrue(result.changed)
        assertEquals(listOf(newlyAdded), result.mappings)
    }

    @Test
    fun removeFavoriteMapping_removesOnlyRequestedMapping() {
        val other = mapping.copy(
            originalUri = "content://original/2",
            favoriteUri = "content://favorite/2"
        )

        val result = removeFavoriteMapping(
            mappings = listOf(mapping, other),
            mappingToRemove = mapping
        )

        assertTrue(result.changed)
        assertEquals(listOf(other), result.mappings)
    }

    @Test
    fun markOriginalDeleted_preservesFavoriteAndMarksOnlyMatchingOriginal() {
        val other = mapping.copy(
            originalUri = "content://original/2",
            favoriteUri = "content://favorite/2"
        )

        val result = markOriginalDeleted(
            mappings = listOf(mapping, other),
            deletedOriginalUri = "content://original/1"
        )

        assertTrue(result.changed)
        assertTrue(result.mappings.first().isDeleted)
        assertEquals(mapping.favoriteUri, result.mappings.first().favoriteUri)
        assertFalse(result.mappings[1].isDeleted)
    }

    @Test
    fun markOriginalDeleted_isIdempotent() {
        val deleted = mapping.copy(isDeleted = true)

        val result = markOriginalDeleted(
            mappings = listOf(deleted),
            deletedOriginalUri = deleted.originalUri
        )

        assertFalse(result.changed)
        assertEquals(listOf(deleted), result.mappings)
    }

}
