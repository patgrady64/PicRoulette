package com.patgrady64.picroulette

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumDatabaseTest {
    private lateinit var db: PicRouletteDatabase
    private lateinit var dao: AlbumDao
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PicRouletteDatabase::class.java).allowMainThreadQueries().build()
        dao = db.albumDao()
    }
    @After fun close() = db.close()

    private fun photo(id: String, key: String, uri: String, name: String = "$id.jpg") = CatalogPhotoEntity(id, key, uri, name, 100L)

    @Test fun membershipSurvivesCurrentUriChange() {
        dao.putAlbum(AlbumEntity("a", "Twins", 1))
        dao.putPhoto(photo("p", "doc:provider:Pictures/p.jpg", "content://old"))
        dao.addToAlbum(AlbumPhotoCrossRef("a", "p"))
        dao.putPhoto(photo("p", "doc:provider:Pictures/p.jpg", "content://new"))
        val result = dao.albumsWithPhotos().single()
        assertEquals(1, result.photos.size); assertEquals("content://new", result.photos.single().currentUri)
    }

    @Test fun samePhotoCanBelongToMultipleAlbums() {
        dao.putAlbum(AlbumEntity("a", "Twins", 1)); dao.putAlbum(AlbumEntity("b", "Vacation", 2))
        dao.putPhoto(photo("p", "key", "content://photo"))
        dao.addToAlbum(AlbumPhotoCrossRef("a", "p")); dao.addToAlbum(AlbumPhotoCrossRef("b", "p"))
        assertEquals(1, dao.membershipCount("a")); assertEquals(1, dao.membershipCount("b"))
    }

    @Test fun removingFromOneAlbumDoesNotAffectAnother() {
        dao.putAlbum(AlbumEntity("a", "A", 1)); dao.putAlbum(AlbumEntity("b", "B", 2)); dao.putPhoto(photo("p", "key", "content://photo"))
        dao.addToAlbum(AlbumPhotoCrossRef("a", "p")); dao.addToAlbum(AlbumPhotoCrossRef("b", "p")); dao.removeFromAlbum("a", "p")
        assertEquals(0, dao.membershipCount("a")); assertEquals(1, dao.membershipCount("b")); assertEquals(1, dao.photoCount())
    }

    @Test fun deletingAlbumNeverDeletesCatalogPhoto() {
        dao.putAlbum(AlbumEntity("a", "A", 1)); dao.putPhoto(photo("p", "key", "content://photo")); dao.addToAlbum(AlbumPhotoCrossRef("a", "p"))
        dao.deleteAlbum("a")
        assertEquals(1, dao.photoCount())
    }

    @Test fun duplicateCrossRefIsIgnored() {
        dao.putAlbum(AlbumEntity("a", "A", 1)); dao.putPhoto(photo("p", "key", "content://photo"))
        dao.addToAlbum(AlbumPhotoCrossRef("a", "p")); dao.addToAlbum(AlbumPhotoCrossRef("a", "p"))
        assertEquals(1, dao.membershipCount("a"))
    }
}
