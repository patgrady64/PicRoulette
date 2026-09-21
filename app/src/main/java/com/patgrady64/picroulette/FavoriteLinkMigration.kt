package com.patgrady64.picroulette

import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

data class FavoriteSourceCandidate(
    val sourceUri: Uri,
    val relativePath: String,
    val fileName: String
)

data class FavoriteLinkReview(
    val favoriteFile: FavoriteFile,
    val candidates: List<FavoriteSourceCandidate>
)

data class FavoriteLinkMigrationResult(
    val updatedMappings: MutableList<FavoriteMapping>,
    val reviews: List<FavoriteLinkReview>,
    val automaticallyLinked: Int,
    val noMatchCount: Int
)

/**
 * Looks through favorite files that do not already have a FavoriteMapping.
 *
 * One source match:
 *   Linked automatically.
 *
 * Multiple source matches:
 *   Added to the review queue for the user to choose.
 *
 * No source matches:
 *   Left untouched and counted as unmatched.
 */
fun migrateExistingFavoriteLinks(
    favoriteFiles: List<FavoriteFile>,
    sourceImages: List<Uri>,
    existingMappings: List<FavoriteMapping>
): FavoriteLinkMigrationResult {

    val updatedMappings = existingMappings.toMutableList()

    val mappedFavoriteUris = updatedMappings
        .map { it.favoriteUri }
        .filter { it.isNotBlank() }
        .toMutableSet()

    val usedOriginalUris = updatedMappings
        .map { it.originalUri }
        .filter { it.isNotBlank() }
        .toMutableSet()

    /*
     * Build one source-image index up front.
     *
     * This avoids repeatedly searching all 5,000+ source images
     * for every favorite.
     */
    val sourcesByMatchKey = sourceImages
        .distinctBy { it.toString() }
        .mapNotNull(::createSourceCandidate)
        .groupBy { candidate ->
            favoriteMatchKey(candidate.fileName)
        }

    val reviews = mutableListOf<FavoriteLinkReview>()

    var automaticallyLinked = 0
    var noMatchCount = 0

    favoriteFiles.forEach { favoriteFile ->

        val durableFavoriteId = favoriteIdFromFileName(
            favoriteFile.fileNameOnDisk
        )

        /*
         * Do not touch favorites that are already mapped.
         */
        if (
            (durableFavoriteId.isNotBlank() &&
                updatedMappings.any { mapping ->
                    mapping.favoriteId.equals(
                        durableFavoriteId,
                        ignoreCase = true
                    )
                }) ||
            favoriteFile.mediaUri.toString() in mappedFavoriteUris ||
            updatedMappings.any { mapping ->
                mapping.favoriteUri.isNotBlank() &&
                    runCatching {
                        sameImage(Uri.parse(mapping.favoriteUri), favoriteFile.mediaUri)
                    }.getOrDefault(false)
            }
        ) {
            return@forEach
        }

        val matchKey = favoriteMatchKey(
            favoriteFile.fileNameOnDisk
        )

        if (matchKey.isBlank()) {
            noMatchCount++
            return@forEach
        }

        /*
         * Do not offer an original that is already linked
         * to another favorite.
         */
        val availableCandidates =
            sourcesByMatchKey[matchKey]
                .orEmpty()
                .filterNot { candidate ->
                    candidate.sourceUri.toString() in usedOriginalUris ||
                        updatedMappings.any { mapping ->
                            mapping.originalUri.isNotBlank() &&
                                runCatching {
                                    sameImage(Uri.parse(mapping.originalUri), candidate.sourceUri)
                                }.getOrDefault(false)
                        }
                }

        when (availableCandidates.size) {
            0 -> {
                noMatchCount++
            }

            1 -> {
                val candidate =
                    availableCandidates.first()

                updatedMappings.add(
                    FavoriteMapping(
                        originalUri =
                            candidate.sourceUri.toString(),

                        favoriteUri =
                            favoriteFile.mediaUri.toString(),

                        originalFileName =
                            candidate.fileName,

                        favoriteId = durableFavoriteId,

                        favoriteFileName =
                            favoriteFile.fileNameOnDisk,

                        dateAdded =
                            System.currentTimeMillis()
                    )
                )

                mappedFavoriteUris.add(
                    favoriteFile.mediaUri.toString()
                )

                usedOriginalUris.add(
                    candidate.sourceUri.toString()
                )

                automaticallyLinked++
            }

            else -> {
                reviews.add(
                    FavoriteLinkReview(
                        favoriteFile = favoriteFile,
                        candidates = availableCandidates
                    )
                )
            }
        }
    }

    return FavoriteLinkMigrationResult(
        updatedMappings = updatedMappings,
        reviews = reviews,
        automaticallyLinked = automaticallyLinked,
        noMatchCount = noMatchCount
    )
}

/**
 * Extracts a useful relative path from a Storage Access Framework URI.
 *
 * Example document ID:
 *
 * C11A-1604:pics/vacation/01.jpeg
 *
 * Stored relative path:
 *
 * pics/vacation/01.jpeg
 */
private fun createSourceCandidate(
    sourceUri: Uri
): FavoriteSourceCandidate? {

    val documentId = runCatching {
        DocumentsContract.getDocumentId(sourceUri)
    }.getOrNull()

    val relativePath = when {
        !documentId.isNullOrBlank() -> {
            Uri.decode(
                documentId.substringAfter(
                    delimiter = ":",
                    missingDelimiterValue = documentId
                )
            )
        }

        else -> {
            Uri.decode(
                sourceUri.lastPathSegment.orEmpty()
            )
        }
    }
        .trim()
        .trimStart('/')

    val fileName = relativePath
        .substringAfterLast("/")
        .ifBlank {
            Uri.decode(
                sourceUri.lastPathSegment.orEmpty()
            )
        }

    if (fileName.isBlank()) {
        return null
    }

    return FavoriteSourceCandidate(
        sourceUri = sourceUri,
        relativePath = relativePath,
        fileName = fileName
    )
}

/**
 * Reproduces the basic filename matching used by older PicRoulette versions.
 *
 * Examples:
 *
 * PR_FAV_IMG_01.jpg -> 01
 * IMG_01.jpeg       -> 01
 * 01.jpg            -> 01
 */
private fun favoriteMatchKey(
    rawName: String
): String {

    val decodedName = Uri.decode(rawName)

    val fileName = decodedName
        .substringAfterLast("/")

    val withoutExtension = fileName
        .substringBeforeLast(
            delimiter = ".",
            missingDelimiterValue = fileName
        )

    /*
     * Durable favorites start with PRF_<UUID>_. Remove that identity prefix
     * before applying the legacy filename heuristics below.
     */
    val withoutDurableId = withoutExtension.replace(
        Regex(
            "^PRF_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_",
            RegexOption.IGNORE_CASE
        ),
        ""
    )

    /*
     * Current PicRoulette favorites are saved as
     * <original-stem>_yyyyMMddHHmmssSSS.jpg. Strip that generated
     * 17-digit suffix before comparing the favorite to its source.
     */
    val withoutGeneratedTimestamp = withoutDurableId
        .replace(
            Regex("_\\d{17}$"),
            ""
        )

    return withoutGeneratedTimestamp
        .replace(
            Regex(
                pattern =
                    "^(IMG_|PR_FAV_|Zoom_|Screenshot_|PR_SCREENShot_|_\\d+)*",

                option =
                    RegexOption.IGNORE_CASE
            ),
            replacement = ""
        )
        .trim()
        .lowercase(Locale.US)
}