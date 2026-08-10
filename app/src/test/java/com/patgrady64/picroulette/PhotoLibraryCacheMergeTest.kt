package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhotoLibraryCacheMergeTest {
    @Test
    fun failedFolder_keepsItsLastKnownPhotos() {
        val result = mergeFolderScanResults(
            configuredFolderKeys = listOf("A", "B"),
            freshFolderImages = mapOf("A" to listOf("a-new")),
            failedFolderKeys = setOf("B"),
            cachedFolderImages = mapOf(
                "A" to listOf("a-old"),
                "B" to listOf("b-old-1", "b-old-2")
            )
        )

        assertEquals(listOf("a-new"), result.folderImages["A"])
        assertEquals(
            listOf("b-old-1", "b-old-2"),
            result.folderImages["B"]
        )
    }

    @Test
    fun successfulEmptyScan_replacesOldFolderContents() {
        val result = mergeFolderScanResults(
            configuredFolderKeys = listOf("A"),
            freshFolderImages = mapOf("A" to emptyList()),
            failedFolderKeys = emptySet(),
            cachedFolderImages = mapOf("A" to listOf("old-photo"))
        )

        assertEquals(emptyList<String>(), result.folderImages["A"])
        assertEquals(emptyList<String>(), result.allImages)
    }

    @Test
    fun removedFolder_isDroppedFromCache() {
        val result = mergeFolderScanResults(
            configuredFolderKeys = listOf("A"),
            freshFolderImages = mapOf("A" to listOf("a")),
            failedFolderKeys = emptySet(),
            cachedFolderImages = mapOf(
                "A" to listOf("a-old"),
                "REMOVED" to listOf("should-not-survive")
            )
        )

        assertFalse(result.folderImages.containsKey("REMOVED"))
        assertFalse(result.allImages.contains("should-not-survive"))
    }
}
