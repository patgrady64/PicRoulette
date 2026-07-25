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

        val updatedMappings =
            mappings.toMutableList()

        var updatedCount = 0
        var alreadyCompleteCount = 0
        var hashFailureCount = 0

        val total = updatedMappings.size

        updatedMappings.indices.forEach { index ->

            val existing =
                updatedMappings[index]

            val alreadyComplete =
                existing.originalRelativePath.isNotBlank() &&
                        existing.originalSha256.isNotBlank()

            if (alreadyComplete) {
                alreadyCompleteCount++

                reportBackfillProgress(
                    completed = index + 1,
                    total = total,
                    onProgress = onProgress
                )

                return@forEach
            }

            val originalUri =
                runCatching {
                    Uri.parse(existing.originalUri)
                }.getOrNull()

            if (
                originalUri == null ||
                existing.originalUri.isBlank()
            ) {
                hashFailureCount++

                reportBackfillProgress(
                    completed = index + 1,
                    total = total,
                    onProgress = onProgress
                )

                return@forEach
            }

            val relativePath =
                if (
                    existing
                        .originalRelativePath
                        .isNotBlank()
                ) {
                    existing.originalRelativePath
                } else {
                    getOriginalRelativePath(
                        originalUri
                    )
                }

            val sha256 =
                if (
                    existing
                        .originalSha256
                        .isNotBlank()
                ) {
                    existing.originalSha256
                } else {
                    calculateOriginalSha256(
                        context = context,
                        uri = originalUri
                    )
                }

            if (sha256.isBlank()) {
                hashFailureCount++
            }

            val changed =
                relativePath !=
                        existing.originalRelativePath ||
                        sha256 !=
                        existing.originalSha256

            if (changed) {
                updatedMappings[index] =
                    existing.copy(
                        originalRelativePath =
                            relativePath,

                        originalSha256 =
                            sha256
                    )

                updatedCount++
            }

            /*
             * Save periodically so most progress survives if the
             * app is interrupted during a long upgrade.
             */
            if (
                (index + 1) % 25 == 0
            ) {
                saveFavoriteMappings(
                    context,
                    updatedMappings
                )
            }

            reportBackfillProgress(
                completed = index + 1,
                total = total,
                onProgress = onProgress
            )
        }

        saveFavoriteMappings(
            context,
            updatedMappings
        )

        FavoriteMetadataBackfillResult(
            updatedMappings =
                updatedMappings,

            updatedCount =
                updatedCount,

            alreadyCompleteCount =
                alreadyCompleteCount,

            hashFailureCount =
                hashFailureCount
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