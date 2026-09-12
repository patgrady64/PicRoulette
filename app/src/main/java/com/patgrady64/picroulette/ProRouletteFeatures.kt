package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

private const val PRO_ROULETTE_PREFS = "picroulette_pro_roulette"
private const val HISTORY_KEY = "history_json"
private const val HISTORY_LIMIT = 300
private const val DATE_FILTER_KEY = "date_filter"
private const val FAVORITE_FILTER_KEY = "favorite_filter"

data class RouletteHistoryEntry(val uri: Uri, val viewedAt: Long)

enum class ProDateFilter(val label: String) {
    ANY("Any date"), LAST_YEAR("Last 12 months"), OLDER_1("Older than 1 year"), OLDER_3("Older than 3 years"), OLDER_5("Older than 5 years")
}

enum class ProFavoriteFilter(val label: String) {
    ANY("All photos"), ONLY("Favorites only"), EXCLUDE("Exclude favorites")
}

data class ProRouletteFilters(
    val dateFilter: ProDateFilter = ProDateFilter.ANY,
    val favoriteFilter: ProFavoriteFilter = ProFavoriteFilter.ANY
)


fun loadProRouletteFilters(context: Context): ProRouletteFilters {
    val prefs = context.getSharedPreferences(PRO_ROULETTE_PREFS, Context.MODE_PRIVATE)
    val date = runCatching { ProDateFilter.valueOf(prefs.getString(DATE_FILTER_KEY, ProDateFilter.ANY.name)!!) }.getOrDefault(ProDateFilter.ANY)
    val favorite = runCatching { ProFavoriteFilter.valueOf(prefs.getString(FAVORITE_FILTER_KEY, ProFavoriteFilter.ANY.name)!!) }.getOrDefault(ProFavoriteFilter.ANY)
    return ProRouletteFilters(date, favorite)
}

fun saveProRouletteFilters(context: Context, filters: ProRouletteFilters) {
    context.getSharedPreferences(PRO_ROULETTE_PREFS, Context.MODE_PRIVATE).edit()
        .putString(DATE_FILTER_KEY, filters.dateFilter.name)
        .putString(FAVORITE_FILTER_KEY, filters.favoriteFilter.name).apply()
}

fun recordRouletteView(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences(PRO_ROULETTE_PREFS, Context.MODE_PRIVATE)
    val existing = loadRouletteHistory(context).filterNot { it.uri == uri }.toMutableList()
    existing.add(0, RouletteHistoryEntry(uri, System.currentTimeMillis()))
    val array = JSONArray()
    existing.take(HISTORY_LIMIT).forEach {
        array.put(JSONObject().put("uri", it.uri.toString()).put("viewedAt", it.viewedAt))
    }
    prefs.edit().putString(HISTORY_KEY, array.toString()).apply()
}

fun loadRouletteHistory(context: Context): List<RouletteHistoryEntry> {
    val raw = context.getSharedPreferences(PRO_ROULETTE_PREFS, Context.MODE_PRIVATE).getString(HISTORY_KEY, null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(RouletteHistoryEntry(Uri.parse(obj.getString("uri")), obj.getLong("viewedAt")))
            }
        }
    }.getOrDefault(emptyList())
}

fun clearRouletteHistory(context: Context) {
    context.getSharedPreferences(PRO_ROULETTE_PREFS, Context.MODE_PRIVATE).edit().remove(HISTORY_KEY).apply()
}


private fun photoTimestamp(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        fun value(name: String): Long? {
            val idx = cursor.getColumnIndex(name)
            if (idx < 0 || cursor.isNull(idx)) return null
            return cursor.getLong(idx).takeIf { it > 0 }
        }
        value(MediaStore.Images.ImageColumns.DATE_TAKEN)
            ?: value(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?: value(MediaStore.MediaColumns.DATE_ADDED)?.times(1000L)
            ?: value(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000L)
    }
}.getOrNull()

fun applyProRouletteFilters(
    context: Context,
    photos: List<Uri>,
    favoriteOriginalUris: Set<String>,
    filters: ProRouletteFilters
): List<Uri> {
    val now = System.currentTimeMillis()
    val year = 365.2425 * 24 * 60 * 60 * 1000

    // Most favorite mappings point at the exact original library URI.  Use a Set
    // lookup first instead of comparing every photo with every favorite (which made
    // Start Roulette appear to do nothing on large libraries).
    val favoriteExactUris = favoriteOriginalUris
    val favoriteDocumentKeys = favoriteOriginalUris.mapNotNull { raw ->
        runCatching {
            val parsed = Uri.parse(raw)
            val authority = parsed.authority ?: return@runCatching null
            val documentId = DocumentsContract.getDocumentId(parsed)
            "$authority|$documentId"
        }.getOrNull()
    }.toSet()

    fun isFavorite(uri: Uri): Boolean {
        if (uri.toString() in favoriteExactUris) return true
        val key = runCatching {
            val authority = uri.authority ?: return@runCatching null
            "$authority|${DocumentsContract.getDocumentId(uri)}"
        }.getOrNull()
        return key != null && key in favoriteDocumentKeys
    }

    return photos.filter { uri ->
        val isFavorite = isFavorite(uri)
        val favoriteMatch = when (filters.favoriteFilter) {
            ProFavoriteFilter.ANY -> true
            ProFavoriteFilter.ONLY -> isFavorite
            ProFavoriteFilter.EXCLUDE -> !isFavorite
        }
        if (!favoriteMatch) return@filter false
        if (filters.dateFilter == ProDateFilter.ANY) return@filter true
        val age = photoTimestamp(context, uri)?.let { now - it } ?: return@filter false
        when (filters.dateFilter) {
            ProDateFilter.ANY -> true
            ProDateFilter.LAST_YEAR -> age <= year
            ProDateFilter.OLDER_1 -> age > year
            ProDateFilter.OLDER_3 -> age > year * 3
            ProDateFilter.OLDER_5 -> age > year * 5
        }
    }
}
