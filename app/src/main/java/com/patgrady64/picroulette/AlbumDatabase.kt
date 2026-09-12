package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import androidx.room.*


@Entity(tableName = "photos", indices = [Index(value = ["stableKey"], unique = true)])
data class CatalogPhotoEntity(
    @PrimaryKey val id: String,
    val stableKey: String,
    val currentUri: String,
    val displayName: String?,
    val sizeBytes: Long?
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "album_photos",
    primaryKeys = ["albumId", "photoId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CatalogPhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("albumId"), Index("photoId")]
)
data class AlbumPhotoCrossRef(val albumId: String, val photoId: String)

data class AlbumWithPhotos(
    @Embedded val album: AlbumEntity,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(AlbumPhotoCrossRef::class, parentColumn = "albumId", entityColumn = "photoId"))
    val photos: List<CatalogPhotoEntity>
)

@Dao
interface AlbumDao {

    @Transaction
    @Query("SELECT * FROM albums ORDER BY name COLLATE NOCASE")
    fun albumsWithPhotos(): List<AlbumWithPhotos>

    @Transaction
    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    fun albumWithPhotos(albumId: String): AlbumWithPhotos?

    @Query("SELECT * FROM photos WHERE stableKey = :key LIMIT 1")
    fun photoByStableKey(key: String): CatalogPhotoEntity?

    @Query(
        "SELECT * FROM photos " +
                "WHERE displayName = :name AND sizeBytes = :size"
    )
    fun photosByNameAndSize(
        name: String,
        size: Long
    ): List<CatalogPhotoEntity>

    @Query("SELECT currentUri FROM photos")
    fun allPhotoUris(): List<String>

    @Upsert
    fun putPhoto(photo: CatalogPhotoEntity)

    @Upsert
    fun putAlbum(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addToAlbum(ref: AlbumPhotoCrossRef)

    @Query(
        "DELETE FROM album_photos " +
                "WHERE albumId = :albumId AND photoId = :photoId"
    )
    fun removeFromAlbum(
        albumId: String,
        photoId: String
    )

    @Query("UPDATE albums SET name = :name WHERE id = :id")
    fun renameAlbum(
        id: String,
        name: String
    )

    @Query("DELETE FROM albums WHERE id = :id")
    fun deleteAlbum(id: String)

    @Query(
        "SELECT COUNT(*) FROM album_photos " +
                "WHERE albumId = :albumId AND photoId = :photoId"
    )
    fun membershipCount(
        albumId: String,
        photoId: String
    ): Int

    @Query(
        "SELECT COUNT(*) FROM album_photos " +
                "WHERE albumId = :albumId"
    )
    fun membershipCount(albumId: String): Int

    @Query("SELECT COUNT(*) FROM photos")
    fun photoCount(): Int
}


@Database(entities = [CatalogPhotoEntity::class, AlbumEntity::class, AlbumPhotoCrossRef::class], version = 1, exportSchema = false)
abstract class PicRouletteDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    companion object {
        @Volatile private var INSTANCE: PicRouletteDatabase? = null
        fun get(context: Context): PicRouletteDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, PicRouletteDatabase::class.java, "picroulette_catalog.db")
                .allowMainThreadQueries()
                .build().also { INSTANCE = it }
        }
    }
}
