package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumIdentityKeyTest {
    @Test fun documentIdentityIgnoresTreeUriShape() {
        val a = albumStableKey("com.android.externalstorage.documents", "primary:Pictures/Twins/a.jpg", "content", null, "content://old/tree")
        val b = albumStableKey("com.android.externalstorage.documents", "primary:Pictures/Twins/a.jpg", "content", null, "content://new/tree")
        assertEquals(a, b)
    }

    @Test fun differentDocumentsHaveDifferentKeys() {
        assertNotEquals(
            albumStableKey("provider", "primary:A/a.jpg", "content", null, "content://same"),
            albumStableKey("provider", "primary:A/b.jpg", "content", null, "content://same")
        )
    }

    @Test fun providerIsPartOfDocumentIdentity() {
        assertNotEquals(
            albumStableKey("provider.one", "primary:A/a.jpg", "content", null, "content://x"),
            albumStableKey("provider.two", "primary:A/a.jpg", "content", null, "content://x")
        )
    }

    @Test fun fileUriUsesPathInsteadOfWholeUri() {
        assertEquals("file:/storage/emulated/0/Pictures/a.jpg", albumStableKey("", null, "file", "/storage/emulated/0/Pictures/a.jpg", "file:///ignored"))
    }

    @Test fun fileSchemeComparisonIsCaseInsensitive() {
        assertEquals("file:/a.jpg", albumStableKey("", null, "FILE", "/a.jpg", "FILE:///a.jpg"))
    }

    @Test fun nonDocumentContentUriFallsBackToNormalizedUri() {
        assertEquals("uri:content://media/external/images/42", albumStableKey("media", null, "content", null, "content://media/external/images/42"))
    }

    @Test fun documentIdWinsOverFileScheme() {
        assertEquals("doc:provider:primary:A/a.jpg", albumStableKey("provider", "primary:A/a.jpg", "file", "/wrong.jpg", "file:///wrong.jpg"))
    }

    @Test fun blankDocumentIdDoesNotMasqueradeAsStableDocument() {
        assertEquals("uri:content://media/42", albumStableKey("media", "", "content", null, "content://media/42"))
    }

    @Test fun sameDocumentProducesSameKeyAcrossRepeatedScans() {
        val first = albumStableKey("provider", "primary:DCIM/100.jpg", "content", null, "content://scan/1")
        val second = albumStableKey("provider", "primary:DCIM/100.jpg", "content", null, "content://scan/999")
        assertEquals(first, second)
    }

    @Test fun sameFilenameInDifferentFoldersDoesNotCollide() {
        val a = albumStableKey("provider", "primary:Twins/IMG_1.jpg", "content", null, "content://x")
        val b = albumStableKey("provider", "primary:Vacation/IMG_1.jpg", "content", null, "content://y")
        assertNotEquals(a, b)
    }
}
