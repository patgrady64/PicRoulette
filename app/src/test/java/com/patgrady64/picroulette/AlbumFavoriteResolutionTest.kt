package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumFavoriteResolutionTest {
    @Test
    fun activeFavoriteCopyIsPreferredOverOriginal() {
        assertEquals(
            "content://favorites/crop",
            preferredAlbumPhotoUri(
                originalKey = "photo-1",
                originalUri = "content://library/original",
                favoriteUrisByOriginalKey = mapOf(
                    "photo-1" to "content://favorites/crop"
                ),
                canOpen = { true }
            )
        )
    }

    @Test
    fun removingFavoriteMappingRevertsAlbumToOriginal() {
        assertEquals(
            "content://library/original",
            preferredAlbumPhotoUri(
                originalKey = "photo-1",
                originalUri = "content://library/original",
                favoriteUrisByOriginalKey = emptyMap(),
                canOpen = { true }
            )
        )
    }

    @Test
    fun unavailableFavoriteFallsBackToOriginal() {
        assertEquals(
            "content://library/original",
            preferredAlbumPhotoUri(
                originalKey = "photo-1",
                originalUri = "content://library/original",
                favoriteUrisByOriginalKey = mapOf(
                    "photo-1" to "content://favorites/missing"
                ),
                canOpen = { it == "content://library/original" }
            )
        )
    }

    @Test
    fun returnsNullWhenNeitherCopyCanBeOpened() {
        assertNull(
            preferredAlbumPhotoUri(
                originalKey = "photo-1",
                originalUri = "content://library/missing",
                favoriteUrisByOriginalKey = emptyMap(),
                canOpen = { false }
            )
        )
    }
}
