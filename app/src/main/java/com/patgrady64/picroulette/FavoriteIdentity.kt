package com.patgrady64.picroulette

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.util.UUID

private const val FAVORITE_ID_PREFIX = "PRF_"
private val FAVORITE_ID_PATTERN = Regex(
    "^PRF_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})_"
)

fun newFavoriteId(): String = UUID.randomUUID().toString()

fun favoriteIdFromFileName(fileName: String): String =
    FAVORITE_ID_PATTERN.find(fileName)?.groupValues?.getOrNull(1).orEmpty()

fun favoriteFileNameWithId(
    favoriteId: String,
    existingFileName: String
): String {
    val currentId = favoriteIdFromFileName(existingFileName)
    if (currentId.equals(favoriteId, ignoreCase = true)) {
        return existingFileName
    }

    val withoutOldId = if (currentId.isNotBlank()) {
        existingFileName.replace(FAVORITE_ID_PATTERN, "")
    } else {
        existingFileName
    }

    val cleaned = withoutOldId
        .replace(Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "favorite.jpg" }

    return "$FAVORITE_ID_PREFIX${favoriteId}_$cleaned"
}

/**
 * Finds the mapping that belongs to a concrete favorite file. Durable IDs are
 * authoritative; URI equality is only a backwards-compatible fallback for
 * favorites created before durable IDs existed.
 */
fun findFavoriteMappingForFile(
    mappings: List<FavoriteMapping>,
    favoriteFile: FavoriteFile
): FavoriteMapping? {
    val embeddedId = favoriteIdFromFileName(favoriteFile.fileNameOnDisk)

    if (embeddedId.isNotBlank()) {
        mappings.firstOrNull { mapping ->
            mapping.favoriteId.equals(embeddedId, ignoreCase = true)
        }?.let { return it }
    }

    val uriText = favoriteFile.mediaUri.toString()
    return mappings.firstOrNull { mapping ->
        mapping.favoriteUri == uriText
    }
}

fun displayNameForUri(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ""
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")
}

/**
 * Gives a legacy favorite file its durable ID filename. If the provider changes
 * the document URI during the rename, the returned URI is the new one.
 */
fun ensureFavoriteFileIdentity(
    context: Context,
    favoriteUri: Uri,
    favoriteId: String,
    knownFileName: String = ""
): Pair<Uri, String> {
    val currentName = knownFileName.ifBlank {
        displayNameForUri(context, favoriteUri)
    }
    if (currentName.isBlank()) return favoriteUri to knownFileName

    val embeddedId = favoriteIdFromFileName(currentName)
    if (embeddedId.equals(favoriteId, ignoreCase = true)) {
        return favoriteUri to currentName
    }

    val desiredName = favoriteFileNameWithId(favoriteId, currentName)

    val renamedUri = if (DocumentsContract.isDocumentUri(context, favoriteUri)) {
        runCatching {
            DocumentsContract.renameDocument(
                context.contentResolver,
                favoriteUri,
                desiredName
            )
        }.getOrNull()
    } else {
        val changed = runCatching {
            context.contentResolver.update(
                favoriteUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, desiredName)
                },
                null,
                null
            )
        }.getOrDefault(0)
        if (changed > 0) favoriteUri else null
    }

    return if (renamedUri != null) {
        if (renamedUri != favoriteUri) {
            val dao = PicRouletteDatabase.get(context).albumDao()
            dao.photoByCurrentUri(favoriteUri.toString())?.let { catalogPhoto ->
                dao.putPhoto(
                    catalogPhoto.copy(
                        currentUri = renamedUri.toString(),
                        displayName = desiredName
                    )
                )
            }
        }
        renamedUri to desiredName
    } else {
        favoriteUri to currentName
    }
}

fun resolveCatalogPhotoId(
    context: Context,
    originalUri: Uri
): String {
    val dao = PicRouletteDatabase.get(context).albumDao()
    dao.photoByCurrentUri(originalUri.toString())?.let { return it.id }

    val canRead = runCatching {
        context.contentResolver.openInputStream(originalUri)?.use { true } == true
    }.getOrDefault(false)

    return if (canRead) {
        ensureCatalogPhoto(context, originalUri).id
    } else {
        ""
    }
}


data class DurableFavoriteReconcileResult(
    val mappings: MutableList<FavoriteMapping>,
    val changed: Boolean
)

/**
 * Repairs URI drift from durable IDs without deleting anything.
 *
 * favoriteId is recovered from the PR_FAVS filename; photoId is resolved from
 * the catalog row used by albums. URI strings are treated as locations only.
 */
fun reconcileDurableFavoriteMappings(
    context: Context,
    mappings: List<FavoriteMapping>,
    favoriteFiles: List<FavoriteFile>
): DurableFavoriteReconcileResult {
    val filesByFavoriteId = favoriteFiles
        .mapNotNull { file ->
            favoriteIdFromFileName(file.fileNameOnDisk)
                .takeIf { it.isNotBlank() }
                ?.let { id -> id.lowercase() to file }
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (id, files) ->
            files.singleOrNull()?.let { id to it }
        }
        .toMap()

    val filesByUri = favoriteFiles.associateBy { it.mediaUri.toString() }
    val filesByName = favoriteFiles
        .filter { it.fileNameOnDisk.isNotBlank() }
        .groupBy { it.fileNameOnDisk.lowercase() }
        .mapNotNull { (name, files) ->
            files.singleOrNull()?.let { name to it }
        }
        .toMap()
    val dao = PicRouletteDatabase.get(context).albumDao()
    var changed = false

    val updated = mappings.map { mapping ->
        var repaired = mapping

        val favoriteFile = when {
            mapping.favoriteId.isNotBlank() ->
                filesByFavoriteId[mapping.favoriteId.lowercase()]
                    ?: filesByUri[mapping.favoriteUri]
                    ?: mapping.favoriteFileName
                        .takeIf { it.isNotBlank() }
                        ?.lowercase()
                        ?.let(filesByName::get)
            else ->
                filesByUri[mapping.favoriteUri]
                    ?: mapping.favoriteFileName
                        .takeIf { it.isNotBlank() }
                        ?.lowercase()
                        ?.let(filesByName::get)
        }

        if (favoriteFile != null) {
            val embeddedId = favoriteIdFromFileName(favoriteFile.fileNameOnDisk)
            val newFavoriteId = repaired.favoriteId.ifBlank { embeddedId }
            val newFavoriteUri = favoriteFile.mediaUri.toString()

            if (
                newFavoriteUri != repaired.favoriteUri ||
                favoriteFile.fileNameOnDisk != repaired.favoriteFileName ||
                (repaired.favoriteId.isBlank() && newFavoriteId.isNotBlank())
            ) {
                repaired = repaired.copy(
                    favoriteUri = newFavoriteUri,
                    favoriteId = newFavoriteId,
                    favoriteFileName = favoriteFile.fileNameOnDisk
                )
            }
        }

        if (repaired.photoId.isNotBlank()) {
            dao.photoById(repaired.photoId)?.currentUri
                ?.takeIf { it.isNotBlank() }
                ?.let { currentOriginalUri ->
                    if (currentOriginalUri != repaired.originalUri) {
                        repaired = repaired.copy(originalUri = currentOriginalUri)
                    }
                }
        } else if (repaired.originalUri.isNotBlank()) {
            dao.photoByCurrentUri(repaired.originalUri)?.id
                ?.takeIf { it.isNotBlank() }
                ?.let { currentPhotoId ->
                    repaired = repaired.copy(photoId = currentPhotoId)
                }
        }

        if (repaired != mapping) changed = true
        repaired
    }.toMutableList()

    return DurableFavoriteReconcileResult(updated, changed)
}

data class FavoriteShaRepairResult(
    val mappings: MutableList<FavoriteMapping>,
    val repairedCount: Int
)

/**
 * Recovery fallback for mappings whose saved original location is no longer
 * usable. We narrow candidates by path/name and use the stored SHA-256 as the
 * authoritative identity check. A successful match is promoted to the current
 * catalog photoId so later refreshes are cheap and URI-independent.
 */
suspend fun repairFavoriteOriginalsBySha(
    context: Context,
    mappings: List<FavoriteMapping>,
    sourceImages: List<Uri>
): FavoriteShaRepairResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val distinctSources = sourceImages.distinctBy { it.toString() }
    val sourcesByUri = distinctSources.associateBy { it.toString() }
    val sourcesByPath = distinctSources.groupBy { uri ->
        getOriginalRelativePath(uri)
            .trim()
            .trimStart('/')
            .lowercase()
    }
    val sourcesByName = distinctSources.groupBy { uri ->
        getOriginalRelativePath(uri)
            .substringAfterLast('/')
            .lowercase()
    }

    val dao = PicRouletteDatabase.get(context).albumDao()
    val hashCache = mutableMapOf<String, String>()
    var repairedCount = 0

    suspend fun hash(uri: Uri): String {
        val key = uri.toString()
        hashCache[key]?.let { return it }

        val calculated = calculateOriginalSha256(context, uri)
        hashCache[key] = calculated
        return calculated
    }

    fun readable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } == true
    }.getOrDefault(false)

    suspend fun uniqueHashMatch(
        candidates: List<Uri>,
        expectedSha256: String
    ): Uri? {
        if (expectedSha256.isBlank()) return null
        return candidates
            .distinctBy { it.toString() }
            .filter { candidate ->
                val candidateHash = hash(candidate)
                candidateHash.isNotBlank() &&
                    candidateHash.equals(expectedSha256, ignoreCase = true)
            }
            .singleOrNull()
    }

    val updated = mappings.map { mapping ->
        if (mapping.isDeleted) {
            return@map mapping
        }

        /*
         * A valid catalog ID is already the strongest local identity. Keep it,
         * but refresh the saved URI from the catalog if Android changed it.
         */
        if (mapping.photoId.isNotBlank()) {
            val catalogPhoto = dao.photoById(mapping.photoId)
            val catalogUri = catalogPhoto?.currentUri
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)

            if (catalogUri != null && readable(catalogUri)) {
                return@map if (mapping.originalUri != catalogUri.toString()) {
                    mapping.copy(originalUri = catalogUri.toString())
                } else {
                    mapping
                }
            }
        }

        val expectedPath = mapping.originalRelativePath
            .trim()
            .trimStart('/')
            .lowercase()
        val expectedName = mapping.originalFileName.lowercase()
        val expectedSha = mapping.originalSha256

        val exactStoredUri = mapping.originalUri
            .takeIf { it.isNotBlank() }
            ?.let(sourcesByUri::get)

        val pathCandidates = expectedPath
            .takeIf { it.isNotBlank() }
            ?.let { sourcesByPath[it].orEmpty() }
            .orEmpty()

        val nameCandidates = expectedName
            .takeIf { it.isNotBlank() }
            ?.let { sourcesByName[it].orEmpty() }
            .orEmpty()

        val matchingUri = when {
            exactStoredUri != null -> exactStoredUri

            pathCandidates.size == 1 && expectedSha.isBlank() ->
                pathCandidates.single()

            pathCandidates.isNotEmpty() ->
                uniqueHashMatch(pathCandidates, expectedSha)

            nameCandidates.isNotEmpty() ->
                uniqueHashMatch(nameCandidates, expectedSha)

            expectedSha.isNotBlank() ->
                uniqueHashMatch(distinctSources, expectedSha)

            else -> null
        } ?: return@map mapping

        val candidateHash = if (expectedSha.isNotBlank()) hash(matchingUri) else ""
        if (
            expectedSha.isNotBlank() &&
            !candidateHash.equals(expectedSha, ignoreCase = true)
        ) {
            return@map mapping
        }

        val previousPhotoId = mapping.photoId
        val photoId = resolveCatalogPhotoId(context, matchingUri)
        if (photoId.isBlank()) return@map mapping

        if (
            previousPhotoId.isNotBlank() &&
            previousPhotoId != photoId
        ) {
            moveAlbumMembershipsToPhoto(
                context = context,
                fromPhotoId = previousPhotoId,
                toPhotoId = photoId
            )
        }

        val repaired = mapping.copy(
            originalUri = matchingUri.toString(),
            photoId = photoId,
            originalFileName = mapping.originalFileName.ifBlank {
                getOriginalRelativePath(matchingUri).substringAfterLast('/')
            },
            originalRelativePath = getOriginalRelativePath(matchingUri),
            originalSha256 = mapping.originalSha256.ifBlank { candidateHash }
        )

        if (repaired != mapping) repairedCount++
        repaired
    }.toMutableList()

    FavoriteShaRepairResult(
        mappings = updated,
        repairedCount = repairedCount
    )
}
