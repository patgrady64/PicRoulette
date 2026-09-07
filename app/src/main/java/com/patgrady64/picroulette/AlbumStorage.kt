package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PhotoAlbum(
    val id: String,
    val name: String,
    val photoUris: Set<String>,
    val createdAt: Long
)

private const val ALBUM_PREFS = "PicRouletteAlbums"
private const val ALBUMS_KEY = "albums_json"

fun loadAlbums(context: Context): List<PhotoAlbum> {
    val raw = context.getSharedPreferences(ALBUM_PREFS, Context.MODE_PRIVATE)
        .getString(ALBUMS_KEY, null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val photos = obj.optJSONArray("photos") ?: JSONArray()
                add(PhotoAlbum(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    photoUris = buildSet {
                        for (p in 0 until photos.length()) add(photos.getString(p))
                    },
                    createdAt = obj.optLong("createdAt", 0L)
                ))
            }
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())
}

fun saveAlbums(context: Context, albums: List<PhotoAlbum>) {
    val array = JSONArray()
    albums.forEach { album ->
        array.put(JSONObject().apply {
            put("id", album.id)
            put("name", album.name)
            put("createdAt", album.createdAt)
            put("photos", JSONArray().apply { album.photoUris.forEach(::put) })
        })
    }
    context.getSharedPreferences(ALBUM_PREFS, Context.MODE_PRIVATE)
        .edit().putString(ALBUMS_KEY, array.toString()).apply()
}

fun createAlbum(context: Context, albums: List<PhotoAlbum>, name: String, initialPhoto: Uri? = null): List<PhotoAlbum> {
    val clean = name.trim()
    if (clean.isBlank()) return albums
    val album = PhotoAlbum(
        id = UUID.randomUUID().toString(),
        name = clean,
        photoUris = initialPhoto?.let { setOf(it.toString()) } ?: emptySet(),
        createdAt = System.currentTimeMillis()
    )
    return (albums + album).also { saveAlbums(context, it) }
}

fun setPhotoInAlbum(context: Context, albums: List<PhotoAlbum>, albumId: String, photo: Uri, included: Boolean): List<PhotoAlbum> {
    val key = photo.toString()
    val updated = albums.map { album ->
        if (album.id != albumId) album else album.copy(
            photoUris = if (included) album.photoUris + key else album.photoUris - key
        )
    }
    saveAlbums(context, updated)
    return updated
}

fun renameAlbum(context: Context, albums: List<PhotoAlbum>, albumId: String, name: String): List<PhotoAlbum> {
    val clean = name.trim()
    if (clean.isBlank()) return albums
    val updated = albums.map { if (it.id == albumId) it.copy(name = clean) else it }
    saveAlbums(context, updated)
    return updated
}

fun deleteAlbum(context: Context, albums: List<PhotoAlbum>, albumId: String): List<PhotoAlbum> {
    val updated = albums.filterNot { it.id == albumId }
    saveAlbums(context, updated)
    return updated
}
