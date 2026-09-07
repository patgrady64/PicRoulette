package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.UUID

data class PhotoAlbum(val id: String, val name: String, val photoUris: Set<String>, val createdAt: Long)

private data class PhotoIdentity(val stableKey: String, val name: String?, val size: Long?)

private fun identity(context: Context, uri: Uri): PhotoIdentity {
    val authority = uri.authority.orEmpty()
    val docId = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
    }.getOrNull()
    var name: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    }
    // A document ID identifies the file independently of the SAF tree URI used to reach it.
    val key = albumStableKey(
        authority = authority,
        documentId = docId,
        scheme = uri.scheme,
        path = uri.path,
        normalizedUri = uri.normalizeScheme().toString()
    )
    return PhotoIdentity(key, name, size)
}

private fun ensurePhoto(context: Context, uri: Uri): CatalogPhotoEntity {
    val dao = PicRouletteDatabase.get(context).albumDao()
    val i = identity(context, uri)
    val existing = dao.photoByStableKey(i.stableKey)
    if (existing != null) {
        val updated = existing.copy(currentUri = uri.toString(), displayName = i.name ?: existing.displayName, sizeBytes = i.size ?: existing.sizeBytes)
        if (updated != existing) dao.putPhoto(updated)
        return updated
    }
    // Conservative secondary reconciliation for providers that changed URI shape: only a unique name+size match is accepted.
    if (i.name != null && i.size != null) {
        val candidates = dao.photosByNameAndSize(i.name!!, i.size!!)
        if (candidates.size == 1) {
            val updated = candidates.single().copy(stableKey = i.stableKey, currentUri = uri.toString())
            dao.putPhoto(updated)
            return updated
        }
    }
    return CatalogPhotoEntity(UUID.randomUUID().toString(), i.stableKey, uri.toString(), i.name, i.size).also(dao::putPhoto)
}

fun syncPhotoCatalog(context: Context, photos: List<Uri>) {
    photos.distinctBy { it.toString() }.forEach { ensurePhoto(context, it) }
}

fun loadAlbums(context: Context): List<PhotoAlbum> =
    PicRouletteDatabase.get(context).albumDao().albumsWithPhotos().map { row ->
        PhotoAlbum(row.album.id, row.album.name, row.photos.mapTo(linkedSetOf()) { it.currentUri }, row.album.createdAt)
    }

fun createAlbum(context: Context, albums: List<PhotoAlbum>, name: String, initialPhoto: Uri? = null): List<PhotoAlbum> {
    val clean = name.trim(); if (clean.isBlank()) return albums
    val dao = PicRouletteDatabase.get(context).albumDao()
    val id = UUID.randomUUID().toString()
    dao.putAlbum(AlbumEntity(id, clean, System.currentTimeMillis()))
    initialPhoto?.let { dao.addToAlbum(AlbumPhotoCrossRef(id, ensurePhoto(context, it).id)) }
    return loadAlbums(context)
}

fun setPhotoInAlbum(context: Context, albums: List<PhotoAlbum>, albumId: String, photo: Uri, included: Boolean): List<PhotoAlbum> {
    val dao = PicRouletteDatabase.get(context).albumDao()
    val p = ensurePhoto(context, photo)
    if (included) dao.addToAlbum(AlbumPhotoCrossRef(albumId, p.id)) else dao.removeFromAlbum(albumId, p.id)
    return loadAlbums(context)
}

fun renameAlbum(context: Context, albums: List<PhotoAlbum>, albumId: String, name: String): List<PhotoAlbum> {
    val clean = name.trim(); if (clean.isBlank()) return albums
    PicRouletteDatabase.get(context).albumDao().renameAlbum(albumId, clean)
    return loadAlbums(context)
}

fun deleteAlbum(context: Context, albums: List<PhotoAlbum>, albumId: String): List<PhotoAlbum> {
    // Cascade removes only album_photos rows. No ContentResolver delete or file operation exists here.
    PicRouletteDatabase.get(context).albumDao().deleteAlbum(albumId)
    return loadAlbums(context)
}
