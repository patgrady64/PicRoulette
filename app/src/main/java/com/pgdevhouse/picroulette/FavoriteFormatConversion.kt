package com.pgdevhouse.picroulette

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Favorite copies are JPEGs, even if the original source had another extension. */
fun formattedFavoriteName(originalName: String): String {
    val stem = splitFileName(originalName).stem
        .replace(Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]"), "_")
        .trim().ifBlank { "photo" }
    return "FAV_${stem}.jpg"
}

/** Never overwrite a different favorite with the same original basename. */
fun uniqueFavoriteName(requested: String, takenLowercase: Set<String>): String {
    if (requested.lowercase(Locale.ROOT) !in takenLowercase) return requested
    val stem = splitFileName(requested).stem
    var n = 2
    while ("${stem}_$n.jpg".lowercase(Locale.ROOT) in takenLowercase) n++
    return "${stem}_$n.jpg"
}

data class FavoriteFormatProgress(val completed: Int, val total: Int, val startedAt: Long) {
    val fraction: Float get() = if (total == 0) 1f else completed.toFloat() / total
    fun etaSeconds(now: Long = System.currentTimeMillis()): Long? =
        if (completed <= 0 || completed >= total) null else
            ((now - startedAt).coerceAtLeast(0) * (total - completed) / completed) / 1000
}

data class FavoriteFormatResult(
    val updated: MutableList<FavoriteMapping>,
    val renamed: Int,
    val skipped: Int,
    val failed: Int
)

/** Only rename mapped, readable favorites. Never delete or replace photo data. */
suspend fun convertFavoritesToNewFormat(
    context: Context,
    mappings: List<FavoriteMapping>,
    onProgress: suspend (FavoriteFormatProgress) -> Unit
): FavoriteFormatResult = withContext(Dispatchers.IO) {
    val current = mappings.toMutableList()
    val onDisk = readFavoritesList(context).getOrElse { emptyList() }
    val taken = onDisk.map { it.fileNameOnDisk.lowercase(Locale.ROOT) }.toMutableSet()
    val started = System.currentTimeMillis()
    var renamed = 0
    var skipped = 0
    var failed = 0
    val indices = current.indices.filter { !current[it].isDeleted && current[it].favoriteUri.isNotBlank() }
    onProgress(FavoriteFormatProgress(0, indices.size, started))
    for ((step, index) in indices.withIndex()) {
        val mapping = current[index]
        val oldUri = Uri.parse(mapping.favoriteUri)
        val existing = displayNameForUri(context, oldUri)
        val sourceName = mapping.originalFileName.ifBlank {
            mapping.originalUri.takeIf { it.isNotBlank() }
                ?.let { displayNameForUri(context, Uri.parse(it)) }.orEmpty()
        }
        if (existing.isBlank() || sourceName.isBlank()) {
            skipped++
        } else {
            val desired = formattedFavoriteName(sourceName)
            if (existing.equals(desired, ignoreCase = true) ||
                (existing.startsWith("FAV_", ignoreCase = true) &&
                    existing.matches(Regex("FAV_.*_\\d+\\.jpg", RegexOption.IGNORE_CASE)) &&
                    existing.startsWith(splitFileName(desired).stem + "_", ignoreCase = true))) {
                current[index] = mapping.copy(favoriteFileName = existing)
                skipped++
            } else {
                taken.remove(existing.lowercase(Locale.ROOT))
                val safeName = uniqueFavoriteName(desired, taken)
                val renamedFile = renamePhotoFile(context, oldUri, safeName)
                if (renamedFile.isSuccess) {
                    val newUri = renamedFile.renamedUri ?: oldUri
                    updateCatalogUriForRename(context, oldUri, newUri, safeName)
                    current[index] = mapping.copy(
                        favoriteUri = newUri.toString(),
                        favoriteFileName = safeName
                    )
                    renamed++
                    taken.add(safeName.lowercase(Locale.ROOT))
                } else {
                    failed++
                    taken.add(existing.lowercase(Locale.ROOT))
                }
            }
        }
        // Persist each successful step, so an interrupted migration can resume safely.
        saveFavoriteMappings(context, current)
        onProgress(FavoriteFormatProgress(step + 1, indices.size, started))
    }
    FavoriteFormatResult(current, renamed, skipped, failed)
}

fun updateCatalogUriForRename(context: Context, old: Uri, new: Uri, name: String) {
    val dao = PicRouletteDatabase.get(context).albumDao()
    dao.photoByCurrentUri(old.toString())?.let { photo ->
        dao.putPhoto(photo.copy(currentUri = new.toString(), displayName = name))
    }
}
