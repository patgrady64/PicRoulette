package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.UUID
import java.io.File
import java.io.FileNotFoundException

data class PhotoAlbum(
    val id: String,
    val name: String,
    val photoUris: Set<String>,
    val createdAt: Long
)

private data class PhotoIdentity(
    val stableKey: String,
    val name: String?,
    val size: Long?
)

private fun identity(
    context: Context,
    uri: Uri
): PhotoIdentity {
    val authority = uri.authority.orEmpty()

    val documentId = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            null
        }
    }.getOrNull()

    var name: String? = null
    var size: Long? = null

    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex =
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                val sizeIndex =
                    cursor.getColumnIndex(OpenableColumns.SIZE)

                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }

                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }

    val stableKey = albumStableKey(
        authority = authority,
        documentId = documentId,
        scheme = uri.scheme,
        path = uri.path,
        normalizedUri = uri.normalizeScheme().toString()
    )

    return PhotoIdentity(
        stableKey = stableKey,
        name = name,
        size = size
    )
}

private fun ensurePhoto(
    context: Context,
    uri: Uri
): CatalogPhotoEntity {
    val dao = PicRouletteDatabase.get(context).albumDao()
    val photoIdentity = identity(context, uri)

    val existing =
        dao.photoByStableKey(photoIdentity.stableKey)

    if (existing != null) {
        val updated = existing.copy(
            currentUri = uri.toString(),
            displayName =
                photoIdentity.name ?: existing.displayName,
            sizeBytes =
                photoIdentity.size ?: existing.sizeBytes
        )

        if (updated != existing) {
            dao.putPhoto(updated)
        }

        return updated
    }

    /*
     * If Android changed the URI format, try to reconnect the
     * photo using its filename and file size. Only accept a
     * unique match so two similarly named photos are not merged.
     */
    if (
        photoIdentity.name != null &&
        photoIdentity.size != null
    ) {
        val candidates = dao.photosByNameAndSize(
            name = photoIdentity.name,
            size = photoIdentity.size
        )

        if (candidates.size == 1) {
            val updated = candidates.single().copy(
                stableKey = photoIdentity.stableKey,
                currentUri = uri.toString()
            )

            dao.putPhoto(updated)
            return updated
        }
    }

    val newPhoto = CatalogPhotoEntity(
        id = UUID.randomUUID().toString(),
        stableKey = photoIdentity.stableKey,
        currentUri = uri.toString(),
        displayName = photoIdentity.name,
        sizeBytes = photoIdentity.size
    )

    dao.putPhoto(newPhoto)
    return newPhoto
}

/**
 * Adds newly discovered photos to the album catalog.
 *
 * Photos whose exact URI is already present are skipped so a scan
 * does not repeat metadata and database work for the entire library.
 */
fun syncPhotoCatalog(
    context: Context,
    photos: List<Uri>
) {
    val dao = PicRouletteDatabase.get(context).albumDao()

    val knownUris: MutableSet<String> =
        dao.allPhotoUris().toHashSet()

    photos.asSequence()
        .distinctBy { uri ->
            uri.toString()
        }
        .filter { uri ->
            knownUris.add(uri.toString())
        }
        .forEach { uri ->
            ensurePhoto(context, uri)
        }
}

/**
 * Resolves an album's saved photo memberships against the current
 * library scan.
 */
fun resolveAlbumPhotos(
    context: Context,
    albumId: String,
    scannedPhotos: List<Uri>
): List<Uri> {
    val dao = PicRouletteDatabase.get(context).albumDao()

    val row =
        dao.albumWithPhotos(albumId)
            ?: return emptyList()

    val scanned = scannedPhotos.distinctBy { uri ->
        uri.toString()
    }

    val scannedByUri = scanned.associateBy { uri ->
        uri.toString()
    }

    /*
     * Build stable keys without querying photo metadata. The previous fallback
     * called ContentResolver.query for every scanned photo when even one album
     * member had a changed URI. On a large library that could take tens of
     * seconds before Roulette opened.
     */
    fun stableKeyFor(uri: Uri): String {
        val documentId = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                null
            }
        }.getOrNull()

        return albumStableKey(
            authority = uri.authority.orEmpty(),
            documentId = documentId,
            scheme = uri.scheme,
            path = uri.path,
            normalizedUri = uri.normalizeScheme().toString()
        )
    }

    val byStableKey = scanned.groupBy(::stableKeyFor)

    fun canOpen(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver
                .openFileDescriptor(uri, "r")
                ?.use { descriptor ->
                    descriptor.fileDescriptor.valid()
                } == true
        }.getOrDefault(false)
    }

    // Only prune an album membership when Android can tell us the backing file is
    // genuinely gone. A generic permission/read failure is not enough: albums must
    // survive temporary provider or permission problems.
    fun definitelyMissing(uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.let { !File(it).exists() } == true
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.close()
            false
        } catch (_: FileNotFoundException) {
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    val favoriteMappings = getFavoriteMappings(context)
        .filter { mapping ->
            !mapping.isDeleted &&
                    mapping.favoriteUri.isNotBlank()
        }

    val favoriteCopiesByOriginalKey = favoriteMappings
        .mapNotNull { mapping ->
            runCatching {
                stableKeyFor(Uri.parse(mapping.originalUri)) to
                        mapping.favoriteUri
            }.getOrNull()
        }
        .toMap()

    /*
     * An album stores the original photo identity, but displays the saved
     * favorite copy while that mapping exists. That preserves the user's
     * crop/zoom without making the album depend permanently on PR_FAVS.
     */
    if (row.photos.all { stored -> scannedByUri.containsKey(stored.currentUri) }) {
        return row.photos.mapNotNull { stored ->
            val original = scannedByUri.getValue(stored.currentUri)
            preferredAlbumPhotoUri(
                originalKey = stored.stableKey,
                originalUri = original.toString(),
                favoriteUrisByOriginalKey = favoriteCopiesByOriginalKey,
                canOpen = { candidate -> canOpen(Uri.parse(candidate)) }
            )?.let(Uri::parse)
        }.distinctBy { it.toString() }
    }

    val resolved = mutableListOf<Uri>()

    row.photos.forEach { stored ->
        val exact =
            scannedByUri[stored.currentUri]

        val stable = byStableKey[stored.stableKey]
            ?.singleOrNull()

        val current = exact ?: stable

        val preferredFavorite = favoriteCopiesByOriginalKey[stored.stableKey]
            ?.let(Uri::parse)
            ?.takeIf(::canOpen)

        when {
            preferredFavorite != null -> {
                resolved += preferredFavorite
            }

            exact != null -> {
                resolved += exact
            }

            current != null && canOpen(current) -> {
                val currentEntity =
                    ensurePhoto(context, current)

                if (currentEntity.id != stored.id) {
                    dao.addToAlbum(
                        AlbumPhotoCrossRef(
                            albumId = albumId,
                            photoId = currentEntity.id
                        )
                    )

                    dao.removeFromAlbum(
                        albumId = albumId,
                        photoId = stored.id
                    )
                }

                resolved += current
            }

            else -> {
                val oldUri = Uri.parse(stored.currentUri)

                if (canOpen(oldUri)) {
                    resolved += oldUri
                } else {
                    /*
                     * Album entries created from Favorites keep
                     * the original photo identity. If that original
                     * cannot be opened, try its saved favorite copy.
                     */
                    val favoriteCopy = favoriteCopiesByOriginalKey[
                        stableKeyFor(oldUri)
                    ]?.let(Uri::parse)
                        ?.takeIf { favoriteUri ->
                            canOpen(favoriteUri)
                        }

                    if (favoriteCopy != null) {
                        resolved += favoriteCopy
                    } else if (definitelyMissing(oldUri)) {
                        // The photo was deleted outside PicRoulette (for example by
                        // SpaceTrace or Android Files). Remove only the stale album
                        // membership; never delete another copy of the photo.
                        dao.removeFromAlbum(albumId = albumId, photoId = stored.id)
                    }
                }
            }
        }
    }

    return resolved.distinctBy { uri ->
        uri.toString()
    }
}

fun albumMembershipCount(
    context: Context,
    albumId: String
): Int {
    return PicRouletteDatabase
        .get(context)
        .albumDao()
        .membershipCount(albumId)
}

fun loadAlbums(
    context: Context
): List<PhotoAlbum> {
    return PicRouletteDatabase
        .get(context)
        .albumDao()
        .albumsWithPhotos()
        .map { row ->
            PhotoAlbum(
                id = row.album.id,
                name = row.album.name,
                photoUris = row.photos.mapTo(
                    linkedSetOf()
                ) { photo ->
                    photo.currentUri
                },
                createdAt = row.album.createdAt
            )
        }
}

fun createAlbum(
    context: Context,
    albums: List<PhotoAlbum>,
    name: String,
    initialPhoto: Uri? = null
): List<PhotoAlbum> {
    val cleanName = name.trim()

    if (cleanName.isBlank()) {
        return albums
    }

    val dao = PicRouletteDatabase.get(context).albumDao()
    val albumId = UUID.randomUUID().toString()

    dao.putAlbum(
        AlbumEntity(
            id = albumId,
            name = cleanName,
            createdAt = System.currentTimeMillis()
        )
    )

    if (initialPhoto != null) {
        val photo = ensurePhoto(context, initialPhoto)

        dao.addToAlbum(
            AlbumPhotoCrossRef(
                albumId = albumId,
                photoId = photo.id
            )
        )
    }

    return loadAlbums(context)
}

fun setPhotoInAlbum(
    context: Context,
    albums: List<PhotoAlbum>,
    albumId: String,
    photo: Uri,
    included: Boolean
): List<PhotoAlbum> {
    val dao = PicRouletteDatabase.get(context).albumDao()
    val catalogPhoto = ensurePhoto(context, photo)

    if (included) {
        dao.addToAlbum(
            AlbumPhotoCrossRef(
                albumId = albumId,
                photoId = catalogPhoto.id
            )
        )
    } else {
        dao.removeFromAlbum(
            albumId = albumId,
            photoId = catalogPhoto.id
        )
    }

    return loadAlbums(context)
}

fun renameAlbum(
    context: Context,
    albums: List<PhotoAlbum>,
    albumId: String,
    name: String
): List<PhotoAlbum> {
    val cleanName = name.trim()

    if (cleanName.isBlank()) {
        return albums
    }

    PicRouletteDatabase
        .get(context)
        .albumDao()
        .renameAlbum(
            id = albumId,
            name = cleanName
        )

    return loadAlbums(context)
}

fun deleteAlbum(
    context: Context,
    albums: List<PhotoAlbum>,
    albumId: String
): List<PhotoAlbum> {
    /*
     * Room's cascade removes album membership rows only.
     * No photo file is deleted from the device.
     */
    PicRouletteDatabase
        .get(context)
        .albumDao()
        .deleteAlbum(albumId)

    return loadAlbums(context)
}
