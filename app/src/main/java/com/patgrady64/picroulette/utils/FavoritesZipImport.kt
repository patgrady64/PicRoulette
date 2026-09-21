package com.patgrady64.picroulette.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.patgrady64.picroulette.AlbumEntity
import com.patgrady64.picroulette.AlbumPhotoCrossRef
import com.patgrady64.picroulette.FavoriteFile
import com.patgrady64.picroulette.FavoriteLinkReview
import com.patgrady64.picroulette.FavoriteMapping
import com.patgrady64.picroulette.FavoriteSourceCandidate
import com.patgrady64.picroulette.PicRouletteDatabase
import com.patgrady64.picroulette.createFavoriteDestination
import com.patgrady64.picroulette.ensureCatalogPhoto
import com.patgrady64.picroulette.ensureFavoriteFileIdentity
import com.patgrady64.picroulette.favoriteFileNameWithId
import com.patgrady64.picroulette.favoriteIdFromFileName
import com.patgrady64.picroulette.findFavoriteMappingForFile
import com.patgrady64.picroulette.getOriginalRelativePath
import com.patgrady64.picroulette.moveAlbumMembershipsToPhoto
import com.patgrady64.picroulette.newFavoriteId
import com.patgrady64.picroulette.publishFavoriteDestination
import com.patgrady64.picroulette.relinkFavoriteAlbumMembershipsToOriginals
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Summary of a Favorites ZIP import.
 *
 * updatedMappings contains both the mappings that existed before the import and
 * any durable identities/source-photo links restored from the backup manifest.
 */
data class FavoritesZipImportResult(
    val importedCount: Int,
    val skippedDuplicateCount: Int,
    val failedCount: Int,
    val ignoredEntryCount: Int,
    val importedBytes: Long,
    val backupVersion: Int,
    val containsSourceLinks: Boolean,
    val updatedMappings: MutableList<FavoriteMapping>,
    val restoredLinkCount: Int,
    val existingLinkCount: Int,
    val unresolvedLinkCount: Int,
    val missingSourceMetadataCount: Int,
    val reviews: List<FavoriteLinkReview>,
    val restoredAlbumCount: Int = 0,
    val restoredAlbumMembershipCount: Int = 0,
    val unresolvedAlbumPhotoCount: Int = 0
)

enum class FavoritesZipImportPhase {
    READING_BACKUP,
    CHECKING_EXISTING_FAVORITES,
    IMPORTING_FAVORITES,
    PREPARING_SOURCE_LIBRARY,
    RESTORING_LINKS,
    COMPARING_SOURCE_PHOTOS,
    FINISHING
}

data class FavoritesZipImportProgress(
    val phase: FavoritesZipImportPhase,
    val completed: Int = 0,
    val total: Int = 0,
    val currentFileName: String = "",
    val importedCount: Int = 0,
    val skippedDuplicateCount: Int = 0,
    val failedCount: Int = 0
)

private data class BackupManifestFile(
    val archiveFile: String,
    val originalFavoriteName: String,
    val mimeType: String,
    val originalUri: String,
    val originalFileName: String,
    val photoId: String,
    val favoriteId: String,
    val favoriteFileName: String,
    val originalRelativePath: String,
    val originalSha256: String,
    val hasSourceLink: Boolean,
    val originalDeleted: Boolean
)

private data class BackupManifest(
    val backupVersion: Int,
    val containsSourceLinks: Boolean,
    val filesByArchiveName: Map<String, BackupManifestFile>,
    val albums: List<BackupAlbum>
)

private data class BackupAlbum(
    val id: String,
    val name: String,
    val createdAt: Long,
    val photos: List<BackupAlbumPhoto>
)

private data class BackupAlbumPhoto(
    val uri: String,
    val photoId: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val originalSha256: String,
    val favoriteId: String,
    val favoriteArchiveFile: String
)

private data class CopiedZipEntry(
    val byteCount: Long,
    val sha256: String
)

private data class RestoredArchiveFavorite(
    val manifestFile: BackupManifestFile,
    val favoriteFile: FavoriteFile,
    val favoriteId: String
)

private const val MAX_MANIFEST_BYTES =
    5L * 1024L * 1024L

private const val MAX_SINGLE_IMAGE_BYTES =
    500L * 1024L * 1024L

private const val MAX_TOTAL_IMPORT_BYTES =
    20L * 1024L * 1024L * 1024L

private const val MAX_ZIP_ENTRIES =
    100_000

/**
 * Imports a PicRoulette Favorites ZIP and restores durable favorite/source links.
 *
 * Safety rules:
 * - Existing favorite files are never deleted or overwritten.
 * - Existing mappings are never discarded.
 * - A matching existing favorite is reused instead of duplicated.
 * - Ambiguous source-photo matches are returned for manual review.
 */
fun importFavoritesZip(
    context: Context,
    sourceZipUri: Uri,
    existingFavorites: List<FavoriteFile>,
    existingMappings: List<FavoriteMapping>,
    sourceImages: List<Uri>,
    onProgress: (FavoritesZipImportProgress) -> Unit = {}
): FavoritesZipImportResult {

    onProgress(
        FavoritesZipImportProgress(
            phase = FavoritesZipImportPhase.READING_BACKUP
        )
    )

    val manifest = readBackupManifest(
        context = context,
        sourceZipUri = sourceZipUri,
        onProgress = onProgress
    )

    var importedCount = 0
    var skippedDuplicateCount = 0
    var failedCount = 0
    var ignoredEntryCount = 0
    var importedBytes = 0L
    var entryCount = 0

    fun reportProgress(
        phase: FavoritesZipImportPhase,
        completed: Int = 0,
        total: Int = 0,
        currentFileName: String = ""
    ) {
        onProgress(
            FavoritesZipImportProgress(
                phase = phase,
                completed = completed,
                total = total,
                currentFileName = currentFileName,
                importedCount = importedCount,
                skippedDuplicateCount = skippedDuplicateCount,
                failedCount = failedCount
            )
        )
    }

    /*
     * Keep every existing favorite with the same hash in a queue. This matters
     * because two legitimate favorites may contain identical image bytes.
     */
    val existingFavoritesByHash =
        mutableMapOf<String, MutableList<FavoriteFile>>()

    val existingFavoritesById = existingFavorites
        .mapNotNull { favorite ->
            val embeddedId = favoriteIdFromFileName(favorite.fileNameOnDisk)
            val mappedId = findFavoriteMappingForFile(
                mappings = existingMappings,
                favoriteFile = favorite
            )?.favoriteId.orEmpty()
            embeddedId.ifBlank { mappedId }
                .takeIf { it.isNotBlank() }
                ?.let { it.lowercase(Locale.US) to favorite }
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (id, files) ->
            files.singleOrNull()?.let { id to it }
        }
        .toMap()

    reportProgress(
        phase =
            FavoritesZipImportPhase.CHECKING_EXISTING_FAVORITES,
        completed = 0,
        total = existingFavorites.size
    )

    existingFavorites.forEachIndexed { index, favorite ->
        val hash = sha256ForUri(
            context = context,
            uri = favorite.mediaUri
        )

        if (!hash.isNullOrBlank()) {
            existingFavoritesByHash
                .getOrPut(hash) { mutableListOf() }
                .add(favorite)
        }

        reportProgress(
            phase =
                FavoritesZipImportPhase.CHECKING_EXISTING_FAVORITES,
            completed = index + 1,
            total = existingFavorites.size,
            currentFileName = favorite.fileNameOnDisk
        )
    }

    val usedExistingFavoriteUris =
        mutableSetOf<String>()

    val usedFileNames = existingFavorites
        .map {
            it.fileNameOnDisk.lowercase(Locale.US)
        }
        .toMutableSet()

    val restoredArchiveFavorites =
        mutableListOf<RestoredArchiveFavorite>()

    val totalFavoriteEntries =
        manifest.filesByArchiveName.size

    var processedFavoriteEntries = 0

    reportProgress(
        phase = FavoritesZipImportPhase.IMPORTING_FAVORITES,
        completed = 0,
        total = totalFavoriteEntries
    )

    val inputStream = context.contentResolver
        .openInputStream(sourceZipUri)
        ?: throw IllegalStateException(
            "Could not open the selected ZIP file."
        )

    ZipInputStream(inputStream.buffered()).use { zipInput ->

        while (true) {
            val entry = zipInput.nextEntry ?: break

            entryCount++

            if (entryCount > MAX_ZIP_ENTRIES) {
                throw IllegalStateException(
                    "This ZIP contains too many entries."
                )
            }

            try {
                val normalizedEntryName =
                    normalizeArchiveEntryName(entry.name)

                if (
                    entry.isDirectory ||
                    normalizedEntryName == null ||
                    !normalizedEntryName.startsWith("favorites/")
                ) {
                    ignoredEntryCount++
                    continue
                }

                val manifestFile =
                    manifest.filesByArchiveName[normalizedEntryName]

                val archiveBaseName =
                    normalizedEntryName.substringAfterLast("/")

                val fallbackFileName =
                    archiveBaseName.replace(
                        Regex("^\\d{4}_"),
                        ""
                    )

                val desiredFileName =
                    manifestFile
                        ?.originalFavoriteName
                        ?.takeIf { it.isNotBlank() }
                        ?: fallbackFileName

                reportProgress(
                    phase =
                        FavoritesZipImportPhase.IMPORTING_FAVORITES,
                    completed = processedFavoriteEntries,
                    total = totalFavoriteEntries,
                    currentFileName = desiredFileName
                )

                val mimeType =
                    manifestFile
                        ?.mimeType
                        ?.takeIf { it.startsWith("image/") }
                        ?: mimeTypeFromFileName(desiredFileName)

                if (!mimeType.startsWith("image/")) {
                    ignoredEntryCount++
                    processedFavoriteEntries++

                    reportProgress(
                        phase =
                            FavoritesZipImportPhase.IMPORTING_FAVORITES,
                        completed = processedFavoriteEntries,
                        total = totalFavoriteEntries,
                        currentFileName = desiredFileName
                    )

                    continue
                }

                val tempFile = File.createTempFile(
                    "picroulette_import_",
                    ".tmp",
                    context.cacheDir
                )

                try {
                    val copiedEntry =
                        copyCurrentZipEntryToTemp(
                            zipInput = zipInput,
                            destination = tempFile
                        )

                    if (
                        importedBytes + copiedEntry.byteCount >
                        MAX_TOTAL_IMPORT_BYTES
                    ) {
                        throw IllegalStateException(
                            "This backup is too large to import safely."
                        )
                    }

                    val backupFavoriteId = manifestFile
                        ?.favoriteId
                        ?.takeIf { it.isNotBlank() }
                        ?: manifestFile
                            ?.originalFavoriteName
                            ?.let(::favoriteIdFromFileName)
                            ?.takeIf { it.isNotBlank() }
                        ?: manifestFile
                            ?.favoriteFileName
                            ?.let(::favoriteIdFromFileName)
                            .orEmpty()

                    /*
                     * Version-4 backups use favoriteId as the authoritative
                     * duplicate key. Hash matching remains only for older
                     * backups that did not have durable identities; otherwise
                     * two intentionally separate but byte-identical favorites
                     * could be incorrectly merged.
                     */
                    val existingFavoriteById = backupFavoriteId
                        .takeIf { it.isNotBlank() }
                        ?.let { existingFavoritesById[it.lowercase(Locale.US)] }
                        ?.takeIf { favorite ->
                            favorite.mediaUri.toString() !in usedExistingFavoriteUris
                        }

                    val existingFavoriteByHash = if (backupFavoriteId.isBlank()) {
                        existingFavoritesByHash[copiedEntry.sha256]
                            ?.firstOrNull { favorite ->
                                favorite.mediaUri.toString() !in usedExistingFavoriteUris
                            }
                    } else {
                        null
                    }

                    val existingFavorite =
                        existingFavoriteById ?: existingFavoriteByHash

                    val restoredPair: Pair<FavoriteFile, String>? =
                        if (existingFavorite != null) {
                            usedExistingFavoriteUris.add(
                                existingFavorite.mediaUri.toString()
                            )

                            val existingMappingId =
                                findFavoriteMappingForFile(
                                    mappings = existingMappings,
                                    favoriteFile = existingFavorite
                                )?.favoriteId.orEmpty()

                            val durableFavoriteId = backupFavoriteId
                                .ifBlank { favoriteIdFromFileName(existingFavorite.fileNameOnDisk) }
                                .ifBlank { existingMappingId }
                                .ifBlank { newFavoriteId() }

                            val identified = ensureFavoriteFileIdentity(
                                context = context,
                                favoriteUri = existingFavorite.mediaUri,
                                favoriteId = durableFavoriteId,
                                knownFileName = existingFavorite.fileNameOnDisk
                            )

                            usedFileNames.add(
                                identified.second.lowercase(Locale.US)
                            )
                            skippedDuplicateCount++

                            FavoriteFile(
                                fileNameOnDisk = identified.second,
                                mediaUri = identified.first
                            ) to durableFavoriteId
                        } else {
                            val durableFavoriteId = backupFavoriteId
                                .ifBlank { newFavoriteId() }
                            val durableRequestedName = favoriteFileNameWithId(
                                favoriteId = durableFavoriteId,
                                existingFileName = manifestFile
                                    ?.favoriteFileName
                                    ?.takeIf { it.isNotBlank() }
                                    ?: desiredFileName
                            )
                            val safeFileName = makeUniqueFileName(
                                requestedName = durableRequestedName,
                                mimeType = mimeType,
                                usedNames = usedFileNames
                            )

                            val importedUri = insertFavoriteImage(
                                context = context,
                                sourceFile = tempFile,
                                displayName = safeFileName,
                                mimeType = mimeType
                            )

                            if (importedUri == null) {
                                failedCount++
                                null
                            } else {
                                usedFileNames.add(
                                    safeFileName.lowercase(Locale.US)
                                )

                                importedCount++
                                importedBytes += copiedEntry.byteCount

                                FavoriteFile(
                                    fileNameOnDisk = safeFileName,
                                    mediaUri = importedUri
                                ) to durableFavoriteId
                            }
                        }

                    if (restoredPair != null && manifestFile != null) {
                        restoredArchiveFavorites.add(
                            RestoredArchiveFavorite(
                                manifestFile = manifestFile,
                                favoriteFile = restoredPair.first,
                                favoriteId = restoredPair.second
                            )
                        )
                    }

                } catch (exception: Exception) {
                    exception.printStackTrace()
                    failedCount++
                } finally {
                    tempFile.delete()
                    processedFavoriteEntries++

                    reportProgress(
                        phase =
                            FavoritesZipImportPhase.IMPORTING_FAVORITES,
                        completed = processedFavoriteEntries,
                        total = totalFavoriteEntries,
                        currentFileName = desiredFileName
                    )
                }

            } finally {
                runCatching {
                    zipInput.closeEntry()
                }
            }
        }
    }

    reportProgress(
        phase = FavoritesZipImportPhase.RESTORING_LINKS,
        completed = 0,
        total = restoredArchiveFavorites.size
    )

    val linkResult = restoreSourceLinks(
        context = context,
        restoredArchiveFavorites = restoredArchiveFavorites,
        existingMappings = existingMappings,
        sourceImages = sourceImages,
        reportProgress = { phase, completed, total, fileName ->
            reportProgress(
                phase = phase,
                completed = completed,
                total = total,
                currentFileName = fileName
            )
        }
    )

    val albumResult = restoreAlbums(
        context = context,
        albums = manifest.albums,
        sourceImages = sourceImages,
        restoredFavorites = restoredArchiveFavorites,
        restoredMappings = linkResult.updatedMappings
    )

    relinkFavoriteAlbumMembershipsToOriginals(
        context = context,
        mappings = linkResult.updatedMappings
    )

    reportProgress(
        phase = FavoritesZipImportPhase.FINISHING,
        completed = 1,
        total = 1
    )

    return FavoritesZipImportResult(
        importedCount = importedCount,
        skippedDuplicateCount = skippedDuplicateCount,
        failedCount = failedCount,
        ignoredEntryCount = ignoredEntryCount,
        importedBytes = importedBytes,
        backupVersion = manifest.backupVersion,
        containsSourceLinks = manifest.containsSourceLinks,
        updatedMappings = linkResult.updatedMappings,
        restoredLinkCount = linkResult.restoredLinkCount,
        existingLinkCount = linkResult.existingLinkCount,
        unresolvedLinkCount = linkResult.unresolvedLinkCount,
        missingSourceMetadataCount =
            linkResult.missingSourceMetadataCount,
        reviews = linkResult.reviews,
        restoredAlbumCount = albumResult.first,
        restoredAlbumMembershipCount = albumResult.second,
        unresolvedAlbumPhotoCount = albumResult.third
    )
}

private data class SourceLinkRestoreResult(
    val updatedMappings: MutableList<FavoriteMapping>,
    val restoredLinkCount: Int,
    val existingLinkCount: Int,
    val unresolvedLinkCount: Int,
    val missingSourceMetadataCount: Int,
    val reviews: List<FavoriteLinkReview>
)

private fun restoreSourceLinks(
    context: Context,
    restoredArchiveFavorites: List<RestoredArchiveFavorite>,
    existingMappings: List<FavoriteMapping>,
    sourceImages: List<Uri>,
    reportProgress: (
        FavoritesZipImportPhase,
        Int,
        Int,
        String
    ) -> Unit
): SourceLinkRestoreResult {

    val updatedMappings = existingMappings.toMutableList()
    val dao = PicRouletteDatabase.get(context).albumDao()

    val distinctSourceImages = sourceImages
        .distinctBy { it.toString() }

    reportProgress(
        FavoritesZipImportPhase.PREPARING_SOURCE_LIBRARY,
        0,
        distinctSourceImages.size,
        ""
    )

    val sourceCandidates = distinctSourceImages
        .mapIndexed { index, uri ->
            val relativePath = getOriginalRelativePath(uri)

            val candidate = FavoriteSourceCandidate(
                sourceUri = uri,
                relativePath = relativePath,
                fileName = relativePath
                    .substringAfterLast("/")
                    .ifBlank {
                        uri.lastPathSegment ?: "image"
                    }
            )

            if (
                index == 0 ||
                (index + 1) % 25 == 0 ||
                index == distinctSourceImages.lastIndex
            ) {
                reportProgress(
                    FavoritesZipImportPhase.PREPARING_SOURCE_LIBRARY,
                    index + 1,
                    distinctSourceImages.size,
                    candidate.fileName
                )
            }

            candidate
        }

    val sourceByUri = sourceCandidates
        .associateBy { it.sourceUri.toString() }

    val sourceByRelativePath = sourceCandidates
        .groupBy {
            normalizeRelativePath(it.relativePath)
        }

    val sourceHashCache = mutableMapOf<String, String>()
    val reviews = mutableListOf<FavoriteLinkReview>()

    var restoredLinkCount = 0
    var existingLinkCount = 0
    var unresolvedLinkCount = 0
    var missingSourceMetadataCount = 0

    fun mappingIndexFor(
        favoriteId: String,
        favoriteUri: String
    ): Int = updatedMappings.indexOfFirst { mapping ->
        (favoriteId.isNotBlank() &&
            mapping.favoriteId.equals(favoriteId, ignoreCase = true)) ||
            mapping.favoriteUri == favoriteUri
    }

    fun putMapping(index: Int, mapping: FavoriteMapping) {
        if (index >= 0) {
            updatedMappings[index] = mapping
        } else {
            updatedMappings.add(mapping)
        }
    }

    val totalLinks = restoredArchiveFavorites.size

    restoredArchiveFavorites.forEachIndexed restoredLoop@ {
            restoredIndex,
            restored ->

        val manifestFile = restored.manifestFile
        val favoriteFile = restored.favoriteFile
        val favoriteUriString = favoriteFile.mediaUri.toString()
        val durableFavoriteId = restored.favoriteId
        val currentFavoriteName = favoriteFile.fileNameOnDisk

        val currentFileName =
            manifestFile.originalFavoriteName
                .ifBlank { currentFavoriteName }

        reportProgress(
            FavoritesZipImportPhase.RESTORING_LINKS,
            restoredIndex,
            totalLinks,
            currentFileName
        )

        val mappingIndex = mappingIndexFor(
            favoriteId = durableFavoriteId,
            favoriteUri = favoriteUriString
        )
        val existing = updatedMappings.getOrNull(mappingIndex)

        val existingPhotoId = existing?.photoId.orEmpty()
        val validExistingPhotoId = existingPhotoId.takeIf { id ->
            id.isNotBlank() && dao.photoById(id) != null
        }.orEmpty().ifBlank {
            manifestFile.photoId.takeIf { id ->
                id.isNotBlank() && dao.photoById(id) != null
            }.orEmpty()
        }

        val baseMapping = existing?.copy(
            favoriteUri = favoriteUriString,
            favoriteId = existing.favoriteId.ifBlank { durableFavoriteId },
            favoriteFileName = currentFavoriteName,
            originalFileName = existing.originalFileName.ifBlank {
                manifestFile.originalFileName
            },
            originalRelativePath = existing.originalRelativePath.ifBlank {
                manifestFile.originalRelativePath
            },
            originalSha256 = existing.originalSha256.ifBlank {
                manifestFile.originalSha256
            },
            photoId = validExistingPhotoId
        )

        if (baseMapping != null && baseMapping != existing) {
            putMapping(mappingIndex, baseMapping)
        }

        if (baseMapping?.isDeleted == true || manifestFile.originalDeleted) {
            val deletedMapping = (baseMapping ?: FavoriteMapping(
                originalUri = "",
                favoriteUri = favoriteUriString,
                originalFileName = manifestFile.originalFileName,
                favoriteId = durableFavoriteId,
                favoriteFileName = currentFavoriteName,
                originalRelativePath = manifestFile.originalRelativePath,
                originalSha256 = manifestFile.originalSha256,
                dateAdded = System.currentTimeMillis(),
                isDeleted = true
            )).copy(
                favoriteUri = favoriteUriString,
                favoriteId = baseMapping?.favoriteId
                    ?.ifBlank { durableFavoriteId }
                    ?: durableFavoriteId,
                favoriteFileName = currentFavoriteName,
                isDeleted = true
            )

            putMapping(mappingIndex, deletedMapping)
            reportProgress(
                FavoritesZipImportPhase.RESTORING_LINKS,
                restoredIndex + 1,
                totalLinks,
                currentFileName
            )
            return@restoredLoop
        }

        val effectiveOriginalUri = baseMapping?.originalUri
            ?.takeIf { it.isNotBlank() }
            ?: manifestFile.originalUri
        val effectiveOriginalFileName = baseMapping?.originalFileName
            ?.takeIf { it.isNotBlank() }
            ?: manifestFile.originalFileName
        val effectiveRelativePath = baseMapping?.originalRelativePath
            ?.takeIf { it.isNotBlank() }
            ?: manifestFile.originalRelativePath
        val effectiveSha256 = baseMapping?.originalSha256
            ?.takeIf { it.isNotBlank() }
            ?: manifestFile.originalSha256

        val hasPortableSourceData =
            manifestFile.hasSourceLink ||
                effectiveOriginalUri.isNotBlank() ||
                effectiveRelativePath.isNotBlank() ||
                effectiveSha256.isNotBlank() ||
                validExistingPhotoId.isNotBlank()

        if (!hasPortableSourceData) {
            val identityOnlyMapping = baseMapping ?: FavoriteMapping(
                originalUri = "",
                favoriteUri = favoriteUriString,
                originalFileName = effectiveOriginalFileName,
                favoriteId = durableFavoriteId,
                favoriteFileName = currentFavoriteName,
                dateAdded = System.currentTimeMillis()
            )
            putMapping(mappingIndex, identityOnlyMapping)
            missingSourceMetadataCount++

            reportProgress(
                FavoritesZipImportPhase.RESTORING_LINKS,
                restoredIndex + 1,
                totalLinks,
                currentFileName
            )
            return@restoredLoop
        }

        /*
         * If the existing durable catalog ID still resolves, the source link is
         * already restored. Refresh the URI and keep the local ID rather than
         * replacing it with the UUID from another device/installation.
         */
        if (validExistingPhotoId.isNotBlank()) {
            val catalogPhoto = dao.photoById(validExistingPhotoId)
            val currentOriginalUri = catalogPhoto?.currentUri.orEmpty()
            val currentOriginalIsReadable = currentOriginalUri
                .takeIf { it.isNotBlank() }
                ?.let { storedUri ->
                    runCatching {
                        context.contentResolver
                            .openInputStream(Uri.parse(storedUri))
                            ?.use { true } == true
                    }.getOrDefault(false)
                } == true

            if (currentOriginalIsReadable) {
                val complete = (baseMapping ?: FavoriteMapping(
                    originalUri = currentOriginalUri,
                    favoriteUri = favoriteUriString,
                    originalFileName = effectiveOriginalFileName,
                    photoId = validExistingPhotoId,
                    favoriteId = durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalRelativePath = effectiveRelativePath,
                    originalSha256 = effectiveSha256,
                    dateAdded = System.currentTimeMillis()
                )).copy(
                    originalUri = currentOriginalUri,
                    photoId = validExistingPhotoId,
                    favoriteUri = favoriteUriString,
                    favoriteId = baseMapping?.favoriteId
                        ?.ifBlank { durableFavoriteId }
                        ?: durableFavoriteId,
                    favoriteFileName = currentFavoriteName
                )
                putMapping(mappingIndex, complete)
                existingLinkCount++

                reportProgress(
                    FavoritesZipImportPhase.RESTORING_LINKS,
                    restoredIndex + 1,
                    totalLinks,
                    currentFileName
                )
                return@restoredLoop
            }
        }

        val usedOriginalUris = updatedMappings
            .filterIndexed { index, _ -> index != mappingIndex }
            .map { it.originalUri }
            .filter { it.isNotBlank() }
            .toSet()

        val exactUriCandidate =
            effectiveOriginalUri
                .takeIf { it.isNotBlank() }
                ?.let { originalUriString ->
                    sourceByUri[originalUriString]
                        ?: readableCandidateFromStoredUri(
                            context = context,
                            storedUriString = originalUriString,
                            fallbackRelativePath = effectiveRelativePath,
                            fallbackFileName = effectiveOriginalFileName
                        )
                }
                ?.takeUnless {
                    it.sourceUri.toString() in usedOriginalUris
                }
                ?.takeIf { candidate ->
                    effectiveSha256.isBlank() ||
                        sha256ForCandidate(
                            context = context,
                            candidate = candidate,
                            cache = sourceHashCache
                        ).equals(effectiveSha256, ignoreCase = true)
                }

        val resolvedCandidates = when {
            exactUriCandidate != null -> {
                listOf(exactUriCandidate)
            }

            effectiveRelativePath.isNotBlank() -> {
                val pathMatches =
                    sourceByRelativePath[
                        normalizeRelativePath(effectiveRelativePath)
                    ]
                        .orEmpty()
                        .filterNot {
                            it.sourceUri.toString() in usedOriginalUris
                        }

                when {
                    pathMatches.size <= 1 && effectiveSha256.isBlank() ->
                        pathMatches

                    effectiveSha256.isNotBlank() -> {
                        pathMatches.filterIndexed {
                                candidateIndex,
                                candidate ->

                            if (
                                candidateIndex == 0 ||
                                (candidateIndex + 1) % 25 == 0 ||
                                candidateIndex == pathMatches.lastIndex
                            ) {
                                reportProgress(
                                    FavoritesZipImportPhase.COMPARING_SOURCE_PHOTOS,
                                    candidateIndex + 1,
                                    pathMatches.size,
                                    currentFileName
                                )
                            }

                            sha256ForCandidate(
                                context = context,
                                candidate = candidate,
                                cache = sourceHashCache
                            ).equals(effectiveSha256, ignoreCase = true)
                        }
                    }

                    else -> pathMatches
                }
            }

            else -> emptyList()
        }.toMutableList()

        if (
            resolvedCandidates.isEmpty() &&
            effectiveSha256.isNotBlank()
        ) {
            sourceCandidates.forEachIndexed {
                    candidateIndex,
                    candidate ->

                if (
                    candidateIndex == 0 ||
                    (candidateIndex + 1) % 25 == 0 ||
                    candidateIndex == sourceCandidates.lastIndex
                ) {
                    reportProgress(
                        FavoritesZipImportPhase.COMPARING_SOURCE_PHOTOS,
                        candidateIndex + 1,
                        sourceCandidates.size,
                        currentFileName
                    )
                }

                if (
                    candidate.sourceUri.toString() !in usedOriginalUris &&
                    sha256ForCandidate(
                        context = context,
                        candidate = candidate,
                        cache = sourceHashCache
                    ).equals(effectiveSha256, ignoreCase = true)
                ) {
                    resolvedCandidates.add(candidate)
                }
            }
        }

        val distinctCandidates = resolvedCandidates
            .distinctBy { it.sourceUri.toString() }

        when (distinctCandidates.size) {
            0 -> {
                /*
                 * Preserve the portable source metadata even when the original
                 * is not currently mounted. Fix favorites mapping can use it
                 * later instead of starting from filename guessing again.
                 */
                val unresolved = (baseMapping ?: FavoriteMapping(
                    originalUri = effectiveOriginalUri,
                    favoriteUri = favoriteUriString,
                    originalFileName = effectiveOriginalFileName,
                    favoriteId = durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalRelativePath = effectiveRelativePath,
                    originalSha256 = effectiveSha256,
                    dateAdded = System.currentTimeMillis()
                )).copy(
                    originalUri = effectiveOriginalUri,
                    photoId = validExistingPhotoId,
                    favoriteUri = favoriteUriString,
                    favoriteId = baseMapping?.favoriteId
                        ?.ifBlank { durableFavoriteId }
                        ?: durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalFileName = effectiveOriginalFileName,
                    originalRelativePath = effectiveRelativePath,
                    originalSha256 = effectiveSha256,
                    isDeleted = false
                )
                putMapping(mappingIndex, unresolved)
                unresolvedLinkCount++
            }

            1 -> {
                val candidate = distinctCandidates.first()
                val catalogPhoto = ensureCatalogPhoto(context, candidate.sourceUri)

                if (
                    validExistingPhotoId.isNotBlank() &&
                    validExistingPhotoId != catalogPhoto.id
                ) {
                    moveAlbumMembershipsToPhoto(
                        context = context,
                        fromPhotoId = validExistingPhotoId,
                        toPhotoId = catalogPhoto.id
                    )
                }

                val restoredMapping = (baseMapping ?: FavoriteMapping(
                    originalUri = candidate.sourceUri.toString(),
                    favoriteUri = favoriteUriString,
                    originalFileName = effectiveOriginalFileName
                        .ifBlank { candidate.fileName },
                    photoId = catalogPhoto.id,
                    favoriteId = durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalRelativePath = candidate.relativePath,
                    originalSha256 = effectiveSha256,
                    dateAdded = System.currentTimeMillis()
                )).copy(
                    originalUri = candidate.sourceUri.toString(),
                    favoriteUri = favoriteUriString,
                    originalFileName = effectiveOriginalFileName
                        .ifBlank { candidate.fileName },
                    photoId = catalogPhoto.id,
                    favoriteId = baseMapping?.favoriteId
                        ?.ifBlank { durableFavoriteId }
                        ?: durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalRelativePath = candidate.relativePath.ifBlank {
                        effectiveRelativePath
                    },
                    originalSha256 = effectiveSha256,
                    isDeleted = false
                )

                putMapping(mappingIndex, restoredMapping)
                restoredLinkCount++
            }

            else -> {
                val unresolved = (baseMapping ?: FavoriteMapping(
                    originalUri = effectiveOriginalUri,
                    favoriteUri = favoriteUriString,
                    originalFileName = effectiveOriginalFileName,
                    favoriteId = durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalRelativePath = effectiveRelativePath,
                    originalSha256 = effectiveSha256,
                    dateAdded = System.currentTimeMillis()
                )).copy(
                    photoId = validExistingPhotoId,
                    favoriteUri = favoriteUriString,
                    favoriteId = baseMapping?.favoriteId
                        ?.ifBlank { durableFavoriteId }
                        ?: durableFavoriteId,
                    favoriteFileName = currentFavoriteName,
                    originalFileName = effectiveOriginalFileName,
                    originalRelativePath = effectiveRelativePath,
                    originalSha256 = effectiveSha256,
                    isDeleted = false
                )
                putMapping(mappingIndex, unresolved)

                reviews.add(
                    FavoriteLinkReview(
                        favoriteFile = favoriteFile,
                        candidates = distinctCandidates
                    )
                )
            }
        }

        reportProgress(
            FavoritesZipImportPhase.RESTORING_LINKS,
            restoredIndex + 1,
            totalLinks,
            currentFileName
        )
    }

    return SourceLinkRestoreResult(
        updatedMappings = updatedMappings,
        restoredLinkCount = restoredLinkCount,
        existingLinkCount = existingLinkCount,
        unresolvedLinkCount = unresolvedLinkCount,
        missingSourceMetadataCount = missingSourceMetadataCount,
        reviews = reviews
    )
}

private fun readableCandidateFromStoredUri(
    context: Context,
    storedUriString: String,
    fallbackRelativePath: String,
    fallbackFileName: String
): FavoriteSourceCandidate? {

    val uri = runCatching {
        Uri.parse(storedUriString)
    }.getOrNull() ?: return null

    val isReadable = runCatching {
        context.contentResolver
            .openAssetFileDescriptor(uri, "r")
            ?.use { true }
            ?: false
    }.getOrDefault(false)

    if (!isReadable) {
        return null
    }

    val relativePath = fallbackRelativePath
        .ifBlank {
            getOriginalRelativePath(uri)
        }

    return FavoriteSourceCandidate(
        sourceUri = uri,
        relativePath = relativePath,
        fileName = fallbackFileName
            .ifBlank {
                relativePath
                    .substringAfterLast("/")
                    .ifBlank {
                        uri.lastPathSegment ?: "image"
                    }
            }
    )
}

private fun sha256ForCandidate(
    context: Context,
    candidate: FavoriteSourceCandidate,
    cache: MutableMap<String, String>
): String {

    val key = candidate.sourceUri.toString()

    return cache.getOrPut(key) {
        sha256ForUri(
            context = context,
            uri = candidate.sourceUri
        ).orEmpty()
    }
}

private fun readBackupManifest(
    context: Context,
    sourceZipUri: Uri,
    onProgress: (FavoritesZipImportProgress) -> Unit
): BackupManifest {

    val inputStream = context.contentResolver
        .openInputStream(sourceZipUri)
        ?: throw IllegalStateException(
            "Could not open the selected ZIP file."
        )

    var manifestText: String? = null
    var entriesChecked = 0

    ZipInputStream(inputStream.buffered()).use { zipInput ->
        while (true) {
            val entry = zipInput.nextEntry ?: break

            entriesChecked++

            try {
                val normalizedName =
                    normalizeArchiveEntryName(entry.name)

                if (
                    entriesChecked == 1 ||
                    entriesChecked % 10 == 0 ||
                    normalizedName == "manifest.json"
                ) {
                    onProgress(
                        FavoritesZipImportProgress(
                            phase =
                                FavoritesZipImportPhase.READING_BACKUP,
                            completed = entriesChecked,
                            total = 0,
                            currentFileName =
                                normalizedName ?: entry.name
                        )
                    )
                }

                if (
                    !entry.isDirectory &&
                    normalizedName == "manifest.json"
                ) {
                    manifestText = readCurrentZipEntryText(
                        zipInput = zipInput,
                        maximumBytes = MAX_MANIFEST_BYTES
                    )

                    break
                }
            } finally {
                runCatching {
                    zipInput.closeEntry()
                }
            }
        }
    }

    val text = manifestText
        ?: throw IllegalArgumentException(
            "This is not a PicRoulette Favorites backup."
        )

    val json = JSONObject(text)

    val backupType = json.optString(
        "backupType",
        ""
    )

    if (backupType != "PicRoulette Favorites ZIP") {
        throw IllegalArgumentException(
            "This is not a PicRoulette Favorites backup."
        )
    }

    val filesByArchiveName =
        mutableMapOf<String, BackupManifestFile>()

    val filesArray = json.optJSONArray("files")

    if (filesArray != null) {
        for (index in 0 until filesArray.length()) {
            val fileObject =
                filesArray.optJSONObject(index) ?: continue

            val archiveFile = normalizeArchiveEntryName(
                fileObject.optString(
                    "archiveFile",
                    ""
                )
            ) ?: continue

            filesByArchiveName[archiveFile] =
                BackupManifestFile(
                    archiveFile = archiveFile,
                    originalFavoriteName =
                        fileObject.optString(
                            "originalFavoriteName",
                            ""
                        ),
                    mimeType =
                        fileObject.optString(
                            "mimeType",
                            ""
                        ),
                    originalUri =
                        fileObject.optString(
                            "originalUri",
                            ""
                        ),
                    originalFileName =
                        fileObject.optString(
                            "originalFileName",
                            ""
                        ),
                    photoId =
                        fileObject.optString(
                            "photoId",
                            ""
                        ),
                    favoriteId =
                        fileObject.optString(
                            "favoriteId",
                            ""
                        ),
                    favoriteFileName =
                        fileObject.optString(
                            "favoriteFileName",
                            fileObject.optString("originalFavoriteName", "")
                        ),
                    originalRelativePath =
                        fileObject.optString(
                            "originalRelativePath",
                            ""
                        ),
                    originalSha256 =
                        fileObject.optString(
                            "originalSha256",
                            ""
                        ),
                    hasSourceLink =
                        fileObject.optBoolean(
                            "hasSourceLink",
                            false
                        ),
                    originalDeleted =
                        fileObject.optBoolean(
                            "originalDeleted",
                            false
                        )
                )
        }
    }

    return BackupManifest(
        backupVersion = json.optInt(
            "backupVersion",
            1
        ),
        containsSourceLinks = json.optBoolean(
            "containsSourceLinks",
            false
        ),
        filesByArchiveName = filesByArchiveName,
        albums = parseBackupAlbums(json)
    )
}

private fun parseBackupAlbums(json: JSONObject): List<BackupAlbum> {
    val array = json.optJSONArray("albums") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim()
        val name = item.optString("name").trim()
        if (id.isBlank() || name.isBlank()) return@mapNotNull null
        val photosArray = item.optJSONArray("photos")
        val photos = if (photosArray == null) emptyList() else (0 until photosArray.length()).mapNotNull photos@ { photoIndex ->
            val photo = photosArray.optJSONObject(photoIndex) ?: return@photos null
            BackupAlbumPhoto(
                uri = photo.optString("uri"),
                photoId = photo.optString("photoId"),
                relativePath = photo.optString("relativePath"),
                displayName = photo.optString("displayName"),
                sizeBytes = photo.optLong("sizeBytes", -1L),
                originalSha256 = photo.optString("originalSha256"),
                favoriteId = photo.optString("favoriteId"),
                favoriteArchiveFile = photo.optString("favoriteArchiveFile")
            )
        }
        BackupAlbum(id, name, item.optLong("createdAt", System.currentTimeMillis()), photos)
    }
}

private fun restoreAlbums(
    context: Context,
    albums: List<BackupAlbum>,
    sourceImages: List<Uri>,
    restoredFavorites: List<RestoredArchiveFavorite>,
    restoredMappings: MutableList<FavoriteMapping>
): Triple<Int, Int, Int> {
    if (albums.isEmpty()) return Triple(0, 0, 0)

    val dao = PicRouletteDatabase.get(context).albumDao()
    val favoritesByArchive = restoredFavorites.associateBy {
        it.manifestFile.archiveFile
    }
    val favoritesById = restoredFavorites
        .filter { it.favoriteId.isNotBlank() }
        .associateBy { it.favoriteId.lowercase(Locale.US) }
    val favoritesByBackupPhotoId = restoredFavorites
        .filter { it.manifestFile.photoId.isNotBlank() }
        .groupBy { it.manifestFile.photoId }
        .mapNotNull { (photoId, favorites) ->
            favorites.singleOrNull()?.let { photoId to it }
        }
        .toMap()

    val candidates = sourceImages.distinctBy(Uri::toString)
    val candidatesByUri = candidates.associateBy(Uri::toString)
    val candidatesByPath = candidates.groupBy { uri ->
        normalizeRelativePath(getOriginalRelativePath(uri))
    }
    val metadataCache = mutableMapOf<String, Pair<String, Long>>()
    val hashCache = mutableMapOf<String, String>()

    fun metadata(uri: Uri): Pair<String, Long> =
        metadataCache.getOrPut(uri.toString()) {
            var name = uri.lastPathSegment.orEmpty()
            var size = -1L
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
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex)
                        }
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }
            name to size
        }

    fun hash(uri: Uri): String = hashCache.getOrPut(uri.toString()) {
        sha256ForUri(context, uri).orEmpty()
    }

    fun readable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } == true
    }.getOrDefault(false)

    fun uniqueHashMatch(
        sourceCandidates: List<Uri>,
        expectedHash: String
    ): Uri? {
        if (expectedHash.isBlank()) return null
        return sourceCandidates
            .distinctBy(Uri::toString)
            .filter { candidate ->
                hash(candidate).equals(expectedHash, ignoreCase = true)
            }
            .singleOrNull()
    }

    fun mappingFor(saved: BackupAlbumPhoto): FavoriteMapping? {
        if (saved.favoriteId.isNotBlank()) {
            restoredMappings.firstOrNull { mapping ->
                mapping.favoriteId.equals(saved.favoriteId, ignoreCase = true)
            }?.let { return it }
        }

        if (saved.photoId.isNotBlank()) {
            val restoredFavorite = favoritesByBackupPhotoId[saved.photoId]
            val favoriteId = restoredFavorite?.favoriteId.orEmpty()
            if (favoriteId.isNotBlank()) {
                restoredMappings.firstOrNull { mapping ->
                    mapping.favoriteId.equals(favoriteId, ignoreCase = true)
                }?.let { return it }
            }
        }

        return restoredMappings.firstOrNull { mapping ->
            mapping.originalUri == saved.uri
        }
    }

    fun updateMappingFromOriginal(
        favoriteId: String,
        originalUri: Uri,
        photoId: String,
        saved: BackupAlbumPhoto
    ) {
        if (favoriteId.isBlank()) return
        val index = restoredMappings.indexOfFirst { mapping ->
            mapping.favoriteId.equals(favoriteId, ignoreCase = true)
        }
        if (index < 0) return

        val mapping = restoredMappings[index]

        if (
            mapping.photoId.isNotBlank() &&
            mapping.photoId != photoId
        ) {
            moveAlbumMembershipsToPhoto(
                context = context,
                fromPhotoId = mapping.photoId,
                toPhotoId = photoId
            )
        }

        restoredMappings[index] = mapping.copy(
            originalUri = originalUri.toString(),
            photoId = photoId,
            originalRelativePath = getOriginalRelativePath(originalUri),
            originalSha256 = mapping.originalSha256.ifBlank {
                saved.originalSha256
            },
            isDeleted = false
        )
    }

    var memberships = 0
    var unresolved = 0

    albums.forEach { album ->
        dao.putAlbum(
            AlbumEntity(
                id = album.id,
                name = album.name,
                createdAt = album.createdAt
            )
        )

        album.photos.forEach { saved ->
            val mapping = mappingFor(saved)

            val mappedCatalogPhoto = mapping
                ?.photoId
                ?.takeIf { it.isNotBlank() }
                ?.let(dao::photoById)

            val mappedOriginalUriText = mapping?.originalUri.orEmpty()
            val mappedOriginal = when {
                mappedCatalogPhoto != null ->
                    runCatching {
                        Uri.parse(mappedCatalogPhoto.currentUri)
                    }.getOrNull()?.takeIf(::readable)

                mappedOriginalUriText.isNotBlank() ->
                    candidatesByUri[mappedOriginalUriText]
                        ?: runCatching { Uri.parse(mappedOriginalUriText) }
                            .getOrNull()
                            ?.takeIf(::readable)

                else -> null
            }

            val exact = candidatesByUri[saved.uri]
                ?.takeIf { candidate ->
                    saved.originalSha256.isBlank() ||
                        hash(candidate).equals(
                            saved.originalSha256,
                            ignoreCase = true
                        )
                }

            val pathMatches = if (
                exact == null &&
                mappedOriginal == null &&
                saved.relativePath.isNotBlank()
            ) {
                candidatesByPath[
                    normalizeRelativePath(saved.relativePath)
                ].orEmpty()
            } else {
                emptyList()
            }

            val pathResolved = when {
                pathMatches.size == 1 && saved.originalSha256.isBlank() ->
                    pathMatches.single()

                pathMatches.isNotEmpty() && saved.originalSha256.isNotBlank() ->
                    uniqueHashMatch(pathMatches, saved.originalSha256)

                else -> null
            }

            val nameMatches = if (
                mappedOriginal == null &&
                exact == null &&
                pathResolved == null &&
                saved.displayName.isNotBlank()
            ) {
                candidates.filter { uri ->
                    metadata(uri).let { data ->
                        data.first.equals(saved.displayName, true) &&
                            (saved.sizeBytes < 0 || data.second == saved.sizeBytes)
                    }
                }
            } else {
                emptyList()
            }

            val nameResolved = when {
                nameMatches.size == 1 && saved.originalSha256.isBlank() ->
                    nameMatches.single()

                nameMatches.isNotEmpty() && saved.originalSha256.isNotBlank() ->
                    uniqueHashMatch(nameMatches, saved.originalSha256)

                else -> null
            }

            val hashResolved = if (
                mappedOriginal == null &&
                exact == null &&
                pathResolved == null &&
                nameResolved == null &&
                saved.originalSha256.isNotBlank()
            ) {
                uniqueHashMatch(candidates, saved.originalSha256)
            } else {
                null
            }

            val sourceUri = mappedOriginal
                ?: exact
                ?: pathResolved
                ?: nameResolved
                ?: hashResolved

            val sourceCatalogPhoto = sourceUri?.let { uri ->
                runCatching { ensureCatalogPhoto(context, uri) }.getOrNull()
            }

            val restoredFavorite = when {
                saved.favoriteId.isNotBlank() ->
                    favoritesById[saved.favoriteId.lowercase(Locale.US)]

                saved.favoriteArchiveFile.isNotBlank() ->
                    favoritesByArchive[saved.favoriteArchiveFile]

                saved.photoId.isNotBlank() ->
                    favoritesByBackupPhotoId[saved.photoId]

                else -> null
            }

            val albumCatalogPhoto = sourceCatalogPhoto ?: restoredFavorite
                ?.favoriteFile
                ?.mediaUri
                ?.let { favoriteUri ->
                    runCatching {
                        ensureCatalogPhoto(context, favoriteUri)
                    }.getOrNull()
                }

            if (albumCatalogPhoto == null) {
                unresolved++
            } else {
                /*
                 * If the source is temporarily unavailable, use the restored
                 * favorite copy as the album's visible fallback instead of
                 * leaving a second, broken membership on the stale source row.
                 * The repair flow moves it back when the original is found.
                 */
                if (
                    sourceCatalogPhoto == null &&
                    mapping?.photoId?.isNotBlank() == true &&
                    mapping.photoId != albumCatalogPhoto.id
                ) {
                    dao.removeFromAlbum(
                        albumId = album.id,
                        photoId = mapping.photoId
                    )
                }

                if (
                    dao.membershipCount(
                        albumId = album.id,
                        photoId = albumCatalogPhoto.id
                    ) == 0
                ) {
                    dao.addToAlbum(
                        AlbumPhotoCrossRef(
                            albumId = album.id,
                            photoId = albumCatalogPhoto.id
                        )
                    )
                    memberships++
                }

                if (sourceCatalogPhoto != null) {
                    val durableFavoriteId = mapping?.favoriteId
                        ?.takeIf { it.isNotBlank() }
                        ?: saved.favoriteId.ifBlank {
                            restoredFavorite?.favoriteId.orEmpty()
                        }

                    updateMappingFromOriginal(
                        favoriteId = durableFavoriteId,
                        originalUri = sourceCatalogPhoto.currentUri.let(Uri::parse),
                        photoId = sourceCatalogPhoto.id,
                        saved = saved
                    )
                }
            }
        }
    }

    return Triple(albums.size, memberships, unresolved)
}

private fun copyCurrentZipEntryToTemp(
    zipInput: ZipInputStream,
    destination: File
): CopiedZipEntry {

    val digest = MessageDigest.getInstance("SHA-256")
    var totalBytes = 0L

    destination.outputStream()
        .buffered()
        .use { output ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val read = zipInput.read(buffer)

                if (read < 0) {
                    break
                }

                if (read == 0) {
                    continue
                }

                totalBytes += read

                if (totalBytes > MAX_SINGLE_IMAGE_BYTES) {
                    throw IllegalArgumentException(
                        "An image in this ZIP is too large."
                    )
                }

                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }

    return CopiedZipEntry(
        byteCount = totalBytes,
        sha256 = digest.digest().toHexString()
    )
}

private fun insertFavoriteImage(
    context: Context,
    sourceFile: File,
    displayName: String,
    mimeType: String
): Uri? {

    val destination = createFavoriteDestination(
        context = context,
        fileName = displayName,
        mimeType = mimeType
    ) ?: return null
    val importedUri = destination.uri

    return try {
        context.contentResolver
            .openOutputStream(importedUri, "w")
            ?.use { output ->
                sourceFile.inputStream()
                    .buffered()
                    .use { input ->
                        input.copyTo(output)
                    }
            }
            ?: throw IllegalStateException(
                "Could not write the imported image."
            )

        if (!publishFavoriteDestination(context, destination)) {
            throw IllegalStateException("Could not finish the imported image.")
        }

        importedUri

    } catch (exception: Exception) {
        exception.printStackTrace()

        runCatching {
            context.contentResolver.delete(
                importedUri,
                null,
                null
            )
        }

        null
    }
}

private fun sha256ForUri(
    context: Context,
    uri: Uri
): String? {

    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")

        val input = context.contentResolver
            .openInputStream(uri)
            ?: return@runCatching null

        input.buffered().use { inputStream ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val read = inputStream.read(buffer)

                if (read < 0) {
                    break
                }

                if (read > 0) {
                    digest.update(buffer, 0, read)
                }
            }
        }

        digest.digest().toHexString()
    }.getOrNull()
}

private fun readCurrentZipEntryText(
    zipInput: ZipInputStream,
    maximumBytes: Long
): String {

    val output = java.io.ByteArrayOutputStream()
    var totalBytes = 0L
    val buffer = ByteArray(16 * 1024)

    while (true) {
        val read = zipInput.read(buffer)

        if (read < 0) {
            break
        }

        if (read == 0) {
            continue
        }

        totalBytes += read

        if (totalBytes > maximumBytes) {
            throw IllegalArgumentException(
                "The backup manifest is too large."
            )
        }

        output.write(buffer, 0, read)
    }

    return output.toString(Charsets.UTF_8.name())
}

private fun normalizeArchiveEntryName(
    rawName: String
): String? {

    val normalized = rawName
        .replace('\\', '/')
        .removePrefix("./")
        .trim()

    if (
        normalized.isBlank() ||
        normalized.startsWith("/") ||
        normalized
            .split("/")
            .any { it == ".." }
    ) {
        return null
    }

    return normalized
}

private fun normalizeRelativePath(
    path: String
): String {
    return Uri.decode(path)
        .replace('\\', '/')
        .trim()
        .trimStart('/')
        .lowercase(Locale.US)
}

private fun makeUniqueFileName(
    requestedName: String,
    mimeType: String,
    usedNames: MutableSet<String>
): String {

    var cleanedName = requestedName
        .substringAfterLast("/")
        .substringAfterLast("\\")
        .replace(
            Regex("""[/:*?"<>|\u0000-\u001F]"""),
            "_"
        )
        .trim()

    if (cleanedName.isBlank()) {
        cleanedName = "PicRoulette_Favorite"
    }

    if (!cleanedName.contains(".")) {
        cleanedName += extensionForMimeType(mimeType)
    }

    if (cleanedName.lowercase(Locale.US) !in usedNames) {
        return cleanedName
    }

    val extension =
        if (cleanedName.contains(".")) {
            "." + cleanedName.substringAfterLast(".")
        } else {
            ""
        }

    val baseName =
        if (extension.isNotEmpty()) {
            cleanedName.dropLast(extension.length)
        } else {
            cleanedName
        }

    var number = 2

    while (true) {
        val candidate = "$baseName ($number)$extension"

        if (candidate.lowercase(Locale.US) !in usedNames) {
            return candidate
        }

        number++
    }
}

private fun mimeTypeFromFileName(
    fileName: String
): String {

    return when (
        fileName
            .substringAfterLast(".", "")
            .lowercase(Locale.US)
    ) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "image/jpeg"
    }
}

private fun extensionForMimeType(
    mimeType: String
): String {

    return when (mimeType) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        else -> ".jpg"
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF)
            .toString(16)
            .padStart(2, '0')
    }
}
