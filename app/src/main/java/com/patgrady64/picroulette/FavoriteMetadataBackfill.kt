package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FavoriteMetadataBackfillResult(
    val updatedMappings: MutableList<FavoriteMapping>,
    val updatedCount: Int,
    val alreadyCompleteCount: Int,
    val hashFailureCount: Int
)

/**
 * Adds missing relative paths and SHA-256 hashes to old mappings.
 *
 * This reads the original photos only. It does not modify the
 * originals or the edited favorite copies.
 */
suspend fun backfillFavoriteMappingMetadata(
    context: Context,
    mappings: List<FavoriteMapping>,
    onProgress: suspend (
        completed: Int,
        total: Int
    ) -> Unit = { _, _ -> }
): FavoriteMetadataBackfillResult =
    withContext(Dispatchers.IO) {

        val updatedMappings = mappings.toMutableList()

        var updatedCount = 0
        var alreadyCompleteCount = 0
        var hashFailureCount = 0

        val total = updatedMappings.size

        updatedMappings.indices.forEach { index ->
            val existing = updatedMappings[index]

            if (existing.isDeleted) {
                alreadyCompleteCount++
                reportBackfillProgress(index + 1, total, onProgress)
                return@forEach
            }

            val originalUri = existing.originalUri
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }

            val favoriteUri = existing.favoriteUri
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }

            val currentFavoriteName = existing.favoriteFileName.ifBlank {
                favoriteUri?.let { displayNameForUri(context, it) }.orEmpty()
            }

            val durableFavoriteId = existing.favoriteId.ifBlank {
                favoriteIdFromFileName(currentFavoriteName).ifBlank {
                    newFavoriteId()
                }
            }

            var durableFavoriteUri = existing.favoriteUri
            var durableFavoriteName = currentFavoriteName

            if (favoriteUri != null) {
                val renamed = ensureFavoriteFileIdentity(
                    context = context,
                    favoriteUri = favoriteUri,
                    favoriteId = durableFavoriteId,
                    knownFileName = currentFavoriteName
                )
                durableFavoriteUri = renamed.first.toString()
                durableFavoriteName = renamed.second
            }

            val durablePhotoId = existing.photoId.ifBlank {
                originalUri?.let { resolveCatalogPhotoId(context, it) }.orEmpty()
            }

            if (originalUri == null) {
                hashFailureCount++
            }

            val relativePath = when {
                existing.originalRelativePath.isNotBlank() -> existing.originalRelativePath
                originalUri != null -> getOriginalRelativePath(originalUri)
                else -> ""
            }

            val sha256 = when {
                existing.originalSha256.isNotBlank() -> existing.originalSha256
                originalUri != null -> calculateOriginalSha256(context, originalUri)
                else -> ""
            }

            if (originalUri != null && sha256.isBlank()) {
                hashFailureCount++
            }

            val upgraded = existing.copy(
                favoriteUri = durableFavoriteUri,
                photoId = durablePhotoId,
                favoriteId = durableFavoriteId,
                favoriteFileName = durableFavoriteName,
                originalRelativePath = relativePath,
                originalSha256 = sha256
            )

            if (upgraded != existing) {
                updatedMappings[index] = upgraded
                updatedCount++
            } else {
                alreadyCompleteCount++
            }

            /*
             * Commit in small batches so a large one-time migration survives an
             * app interruption without restarting from zero.
             */
            if ((index + 1) % 25 == 0) {
                saveFavoriteMappings(context, updatedMappings)
            }

            reportBackfillProgress(index + 1, total, onProgress)
        }

        saveFavoriteMappings(context, updatedMappings)

        FavoriteMetadataBackfillResult(
            updatedMappings = updatedMappings,
            updatedCount = updatedCount,
            alreadyCompleteCount = alreadyCompleteCount,
            hashFailureCount = hashFailureCount
        )
    }

private suspend fun reportBackfillProgress(
    completed: Int,
    total: Int,
    onProgress: suspend (
        completed: Int,
        total: Int
    ) -> Unit
) {
    /*
     * Updating every fifth item avoids excessive UI work.
     */
    if (
        completed % 5 == 0 ||
        completed == total
    ) {
        onProgress(
            completed,
            total
        )
    }
}