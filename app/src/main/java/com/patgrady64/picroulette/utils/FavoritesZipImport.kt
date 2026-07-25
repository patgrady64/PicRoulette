package com.patgrady64.picroulette.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.patgrady64.picroulette.FavoriteFile
import com.patgrady64.picroulette.FavoriteLinkReview
import com.patgrady64.picroulette.FavoriteMapping
import com.patgrady64.picroulette.FavoriteSourceCandidate
import com.patgrady64.picroulette.getOriginalRelativePath
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Summary of a Favorites ZIP import.
 *
 * updatedMappings contains both the mappings that existed before the import and
 * any source-photo links restored from a version-2 backup manifest.
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
    val reviews: List<FavoriteLinkReview>
)

private data class BackupManifestFile(
    val archiveFile: String,
    val originalFavoriteName: String,
    val mimeType: String,
    val originalUri: String,
    val originalFileName: String,
    val originalRelativePath: String,
    val originalSha256: String,
    val hasSourceLink: Boolean
)

private data class BackupManifest(
    val backupVersion: Int,
    val containsSourceLinks: Boolean,
    val filesByArchiveName: Map<String, BackupManifestFile>
)

private data class CopiedZipEntry(
    val byteCount: Long,
    val sha256: String
)

private data class RestoredArchiveFavorite(
    val manifestFile: BackupManifestFile,
    val favoriteFile: FavoriteFile
)

private const val FAVORITES_RELATIVE_PATH =
    "Pictures/PR_FAVS"

private const val MAX_MANIFEST_BYTES =
    5L * 1024L * 1024L

private const val MAX_SINGLE_IMAGE_BYTES =
    500L * 1024L * 1024L

private const val MAX_TOTAL_IMPORT_BYTES =
    20L * 1024L * 1024L * 1024L

private const val MAX_ZIP_ENTRIES =
    100_000

/**
 * Imports a PicRoulette Favorites ZIP and restores version-2 source links.
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
    sourceImages: List<Uri>
): FavoritesZipImportResult {

    val manifest = readBackupManifest(
        context = context,
        sourceZipUri = sourceZipUri
    )

    /*
     * Keep every existing favorite with the same hash in a queue. This matters
     * because two legitimate favorites may contain identical image bytes.
     */
    val existingFavoritesByHash =
        mutableMapOf<String, MutableList<FavoriteFile>>()

    existingFavorites.forEach { favorite ->
        val hash = sha256ForUri(
            context = context,
            uri = favorite.mediaUri
        )

        if (!hash.isNullOrBlank()) {
            existingFavoritesByHash
                .getOrPut(hash) { mutableListOf() }
                .add(favorite)
        }
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

    var importedCount = 0
    var skippedDuplicateCount = 0
    var failedCount = 0
    var ignoredEntryCount = 0
    var importedBytes = 0L
    var entryCount = 0

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

                val mimeType =
                    manifestFile
                        ?.mimeType
                        ?.takeIf { it.startsWith("image/") }
                        ?: mimeTypeFromFileName(desiredFileName)

                if (!mimeType.startsWith("image/")) {
                    ignoredEntryCount++
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

                    val existingFavorite =
                        existingFavoritesByHash[copiedEntry.sha256]
                            ?.firstOrNull { favorite ->
                                favorite.mediaUri.toString() !in
                                        usedExistingFavoriteUris
                            }

                    val restoredFavoriteFile =
                        if (existingFavorite != null) {
                            usedExistingFavoriteUris.add(
                                existingFavorite.mediaUri.toString()
                            )

                            skippedDuplicateCount++
                            existingFavorite
                        } else {
                            val safeFileName = makeUniqueFileName(
                                requestedName = desiredFileName,
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
                                )
                            }
                        }

                    if (
                        restoredFavoriteFile != null &&
                        manifestFile != null
                    ) {
                        restoredArchiveFavorites.add(
                            RestoredArchiveFavorite(
                                manifestFile = manifestFile,
                                favoriteFile = restoredFavoriteFile
                            )
                        )
                    }

                } catch (exception: Exception) {
                    exception.printStackTrace()
                    failedCount++
                } finally {
                    tempFile.delete()
                }

            } finally {
                runCatching {
                    zipInput.closeEntry()
                }
            }
        }
    }

    val linkResult = restoreSourceLinks(
        context = context,
        restoredArchiveFavorites = restoredArchiveFavorites,
        existingMappings = existingMappings,
        sourceImages = sourceImages
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
        reviews = linkResult.reviews
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
    sourceImages: List<Uri>
): SourceLinkRestoreResult {

    val updatedMappings = existingMappings.toMutableList()

    val sourceCandidates = sourceImages
        .distinctBy { it.toString() }
        .map { uri ->
            val relativePath = getOriginalRelativePath(uri)

            FavoriteSourceCandidate(
                sourceUri = uri,
                relativePath = relativePath,
                fileName = relativePath
                    .substringAfterLast("/")
                    .ifBlank {
                        uri.lastPathSegment ?: "image"
                    }
            )
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

    restoredArchiveFavorites.forEach { restored ->
        val manifestFile = restored.manifestFile
        val favoriteFile = restored.favoriteFile
        val favoriteUriString = favoriteFile.mediaUri.toString()

        if (
            updatedMappings.any {
                it.favoriteUri == favoriteUriString
            }
        ) {
            existingLinkCount++
            return@forEach
        }

        val hasPortableSourceData =
            manifestFile.hasSourceLink ||
                    manifestFile.originalUri.isNotBlank() ||
                    manifestFile.originalRelativePath.isNotBlank() ||
                    manifestFile.originalSha256.isNotBlank()

        if (!hasPortableSourceData) {
            missingSourceMetadataCount++
            return@forEach
        }

        val usedOriginalUris = updatedMappings
            .map { it.originalUri }
            .filter { it.isNotBlank() }
            .toSet()

        val exactUriCandidate =
            manifestFile.originalUri
                .takeIf { it.isNotBlank() }
                ?.let { originalUriString ->
                    sourceByUri[originalUriString]
                        ?: readableCandidateFromStoredUri(
                            context = context,
                            storedUriString = originalUriString,
                            fallbackRelativePath =
                                manifestFile.originalRelativePath,
                            fallbackFileName =
                                manifestFile.originalFileName
                        )
                }
                ?.takeUnless {
                    it.sourceUri.toString() in usedOriginalUris
                }

        val resolvedCandidates = when {
            exactUriCandidate != null -> {
                listOf(exactUriCandidate)
            }

            manifestFile.originalRelativePath.isNotBlank() -> {
                val pathMatches =
                    sourceByRelativePath[
                        normalizeRelativePath(
                            manifestFile.originalRelativePath
                        )
                    ]
                        .orEmpty()
                        .filterNot {
                            it.sourceUri.toString() in usedOriginalUris
                        }

                when {
                    pathMatches.size <= 1 -> pathMatches

                    manifestFile.originalSha256.isNotBlank() -> {
                        pathMatches.filter { candidate ->
                            sha256ForCandidate(
                                context = context,
                                candidate = candidate,
                                cache = sourceHashCache
                            ) == manifestFile.originalSha256
                        }
                    }

                    else -> pathMatches
                }
            }

            else -> emptyList()
        }.toMutableList()

        if (
            resolvedCandidates.isEmpty() &&
            manifestFile.originalSha256.isNotBlank()
        ) {
            sourceCandidates.forEach { candidate ->
                if (
                    candidate.sourceUri.toString() !in usedOriginalUris &&
                    sha256ForCandidate(
                        context = context,
                        candidate = candidate,
                        cache = sourceHashCache
                    ) == manifestFile.originalSha256
                ) {
                    resolvedCandidates.add(candidate)
                }
            }
        }

        val distinctCandidates = resolvedCandidates
            .distinctBy { it.sourceUri.toString() }

        when (distinctCandidates.size) {
            0 -> {
                unresolvedLinkCount++
            }

            1 -> {
                val candidate = distinctCandidates.first()

                updatedMappings.add(
                    FavoriteMapping(
                        originalUri = candidate.sourceUri.toString(),
                        favoriteUri = favoriteUriString,
                        originalFileName =
                            manifestFile.originalFileName
                                .ifBlank { candidate.fileName },
                        originalRelativePath =
                            candidate.relativePath.ifBlank {
                                manifestFile.originalRelativePath
                            },
                        originalSha256 =
                            manifestFile.originalSha256,
                        dateAdded = System.currentTimeMillis()
                    )
                )

                restoredLinkCount++
            }

            else -> {
                reviews.add(
                    FavoriteLinkReview(
                        favoriteFile = favoriteFile,
                        candidates = distinctCandidates
                    )
                )
            }
        }
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
    sourceZipUri: Uri
): BackupManifest {

    val inputStream = context.contentResolver
        .openInputStream(sourceZipUri)
        ?: throw IllegalStateException(
            "Could not open the selected ZIP file."
        )

    var manifestText: String? = null

    ZipInputStream(inputStream.buffered()).use { zipInput ->
        while (true) {
            val entry = zipInput.nextEntry ?: break

            try {
                val normalizedName =
                    normalizeArchiveEntryName(entry.name)

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
        filesByArchiveName = filesByArchiveName
    )
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

    val values = ContentValues().apply {
        put(
            MediaStore.MediaColumns.DISPLAY_NAME,
            displayName
        )

        put(
            MediaStore.MediaColumns.MIME_TYPE,
            mimeType
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                FAVORITES_RELATIVE_PATH
            )

            put(
                MediaStore.MediaColumns.IS_PENDING,
                1
            )
        }
    }

    val importedUri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: return null

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val completedValues = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    0
                )
            }

            context.contentResolver.update(
                importedUri,
                completedValues,
                null,
                null
            )
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