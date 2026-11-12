package com.pgdevhouse.picroulette.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.pgdevhouse.picroulette.FavoriteFile
import com.pgdevhouse.picroulette.FavoriteMapping
import com.pgdevhouse.picroulette.PhotoAlbum
import com.pgdevhouse.picroulette.PicRouletteDatabase
import com.pgdevhouse.picroulette.favoriteIdFromFileName
import com.pgdevhouse.picroulette.findFavoriteMappingForFile
import com.pgdevhouse.picroulette.getOriginalRelativePath
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class FavoritesZipExportPhase {
    PREPARING, COPYING_FAVORITES, SAVING_LIBRARY_INDEX, SAVING_ALBUMS, FINALIZING
}

data class FavoritesZipExportProgress(
    val phase: FavoritesZipExportPhase,
    val completed: Int = 0,
    val total: Int = 0,
    val currentFileName: String = "",
    val copiedBytes: Long = 0L,
    val activeFileBytes: Long = 0L,
    val failedCount: Int = 0
)

data class FavoritesZipExportResult(
    val requestedCount: Int,
    val exportedCount: Int,
    val failedCount: Int,
    val sourceBytesCopied: Long
)

/**
 * Exports the exact edited files currently stored as PicRoulette favorites.
 *
 * This function only reads the favorites. It does not rename, delete,
 * overwrite, recompress, or otherwise modify them.
 */
fun exportFavoritesZip(
    context: Context,
    destinationUri: Uri,
    favorites: List<FavoriteFile>,
    mappings: List<FavoriteMapping>,
    albums: List<PhotoAlbum> = emptyList(),
    libraryRoots: List<Uri> = emptyList(),
    libraryImages: List<Uri> = emptyList(),
    onProgress: (FavoritesZipExportProgress) -> Unit = {}
): FavoritesZipExportResult {

    val outputStream = context.contentResolver
        .openOutputStream(destinationUri, "w")
        ?: throw IllegalStateException(
            "Could not open the selected backup destination."
        )

    val manifestFiles = JSONArray()
    val dao = PicRouletteDatabase.get(context).albumDao()

    var exportedCount = 0
    var failedCount = 0
    var totalBytesCopied = 0L

    fun report(phase: FavoritesZipExportPhase, completed: Int = 0, total: Int = 0, name: String = "", activeBytes: Long = 0L) {
        onProgress(FavoritesZipExportProgress(
            phase = phase, completed = completed, total = total,
            currentFileName = name, copiedBytes = totalBytesCopied,
            activeFileBytes = activeBytes,
            failedCount = failedCount
        ))
    }
    report(FavoritesZipExportPhase.PREPARING)

    ZipOutputStream(outputStream.buffered()).use { zipOutput ->

        report(FavoritesZipExportPhase.COPYING_FAVORITES, 0, favorites.size)
        favorites.forEachIndexed { index, favorite ->

            val mapping = findFavoriteMappingForFile(
                mappings = mappings,
                favoriteFile = favorite
            )

            val sourceUri = favorite.mediaUri

            val mediaDetails = readMediaDetails(
                context = context,
                uri = sourceUri,
                fallbackName = favorite.fileNameOnDisk
            )

            /*
             * Prefixing the filename with a number guarantees that two files
             * with the same displayed filename cannot collide inside the ZIP.
             */
            val archiveName = buildArchiveName(
                index = index,
                originalName = mediaDetails.displayName
            )

            try {
                context.contentResolver
                    .openInputStream(sourceUri)
                    ?.use { inputStream ->

                        val zipEntry = ZipEntry(
                            "favorites/$archiveName"
                        )

                        zipOutput.putNextEntry(zipEntry)

                        val buffer = ByteArray(64 * 1024)
                        var bytesCopied = 0L
                        var lastByteUpdate = android.os.SystemClock.elapsedRealtime()
                        while (true) {
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead < 0) break
                            zipOutput.write(buffer, 0, bytesRead)
                            bytesCopied += bytesRead
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastByteUpdate > 400L) {
                                report(FavoritesZipExportPhase.COPYING_FAVORITES, index, favorites.size, mediaDetails.displayName, bytesCopied)
                                lastByteUpdate = now
                            }
                        }

                        zipOutput.closeEntry()

                        totalBytesCopied += bytesCopied
                        exportedCount++

                        manifestFiles.put(
                            JSONObject().apply {
                                put(
                                    "archiveFile",
                                    "favorites/$archiveName"
                                )

                                put(
                                    "originalFavoriteName",
                                    mediaDetails.displayName
                                )

                                put(
                                    "originalUri",
                                    mapping?.originalUri.orEmpty()
                                )

                                put(
                                    "originalFileName",
                                    mapping?.originalFileName.orEmpty()
                                )

                                put(
                                    "photoId",
                                    mapping?.photoId.orEmpty()
                                )

                                put(
                                    "favoriteId",
                                    mapping?.favoriteId
                                        ?.takeIf { it.isNotBlank() }
                                        ?: favoriteIdFromFileName(mediaDetails.displayName)
                                )

                                put(
                                    "favoriteFileName",
                                    mapping?.favoriteFileName
                                        ?.takeIf { it.isNotBlank() }
                                        ?: mediaDetails.displayName
                                )

                                put(
                                    "originalRelativePath",
                                    mapping
                                        ?.originalRelativePath
                                        .orEmpty()
                                )

                                put(
                                    "originalSha256",
                                    mapping
                                        ?.originalSha256
                                        .orEmpty()
                                )

                                put(
                                    "hasSourceLink",
                                    mapping != null &&
                                        !mapping.isDeleted &&
                                        (mapping.photoId.isNotBlank() ||
                                            mapping.originalUri.isNotBlank() ||
                                            mapping.originalSha256.isNotBlank())
                                )

                                put(
                                    "originalDeleted",
                                    mapping?.isDeleted == true
                                )

                                put(
                                    "favoriteMediaUri",
                                    sourceUri.toString()
                                )

                                put(
                                    "mimeType",
                                    mediaDetails.mimeType
                                )

                                put(
                                    "sizeBytes",
                                    bytesCopied
                                )
                            }
                        )
                    }
                    ?: run {
                        failedCount++
                    }

            } catch (exception: Exception) {
                exception.printStackTrace()
                failedCount++

                /*
                 * closeEntry() can throw if an entry was never opened,
                 * so this cleanup is intentionally protected.
                 */
                runCatching {
                    zipOutput.closeEntry()
                }
            }
            report(FavoritesZipExportPhase.COPYING_FAVORITES, index + 1, favorites.size, mediaDetails.displayName)
        }

        /*
         * Write the manifest after processing the files so its counts reflect
         * what was actually copied successfully.
         */
        report(FavoritesZipExportPhase.SAVING_LIBRARY_INDEX, 0, libraryImages.size)
        val manifest = JSONObject().apply {
            put(
                "backupType",
                "PicRoulette Favorites ZIP"
            )

            put(
                "backupVersion",
                5
            )

            put(
                "createdAt",
                formattedCurrentDate()
            )

            put(
                "requestedFavoriteCount",
                favorites.size
            )

            put(
                "exportedFavoriteCount",
                exportedCount
            )

            put(
                "failedFavoriteCount",
                failedCount
            )

            put(
                "sourceBytesCopied",
                totalBytesCopied
            )

            /*
             * Source-photo metadata and durable favorite/photo identities are
             * included whenever they are available. Older/partially repaired
             * mappings remain exportable without discarding the favorite copy.
             */
            put(
                "containsSourceLinks",
                mappings.any {
                    it.favoriteUri.isNotBlank() &&
                        (it.isDeleted ||
                            it.photoId.isNotBlank() ||
                            it.originalUri.isNotBlank() ||
                            it.originalSha256.isNotBlank())
                }
            )

            // Portable root-relative inventory. No device-specific storage URI
            // is needed on the destination phone.
            put("libraryRoots", JSONArray().apply {
                libraryRoots.forEach { root ->
                    val treeId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull().orEmpty()
                    val rootPath = Uri.decode(treeId.substringAfter(':', treeId)).trim('/')
                    val entries = libraryImages.mapNotNull { uri ->
                        val path = getOriginalRelativePath(uri)
                        if (path.startsWith("$rootPath/")) path.removePrefix("$rootPath/")
                        else null
                    }
                    put(JSONObject().apply {
                        put("name", rootPath.substringAfterLast('/'))
                        put("path", rootPath)
                        put("files", JSONArray(entries))
                        val folders = sortedSetOf<String>()
                        entries.forEach { path ->
                            val segments = path.split('/').dropLast(1)
                            segments.indices.forEach { index ->
                                folders.add(segments.take(index + 1).joinToString("/"))
                            }
                        }
                        put("folders", JSONArray(folders.toList()))
                    })
                }
            })

            put(
                "files",
                manifestFiles
            )

            report(FavoritesZipExportPhase.SAVING_ALBUMS, 0, albums.size)
            put("albums", JSONArray().apply {
                albums.forEachIndexed { albumIndex, album ->
                    put(JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("createdAt", album.createdAt)
                        put("photos", JSONArray().apply {
                            album.photoUris.forEach { uriText ->
                                val uri = Uri.parse(uriText)
                                val details = readMediaDetails(
                                    context,
                                    uri,
                                    uri.lastPathSegment ?: ""
                                )
                                val catalogPhoto = dao.photoByCurrentUri(uriText)
                                val mapping = mappings.firstOrNull { candidate ->
                                    (catalogPhoto != null &&
                                        candidate.photoId.isNotBlank() &&
                                        candidate.photoId == catalogPhoto.id) ||
                                        candidate.originalUri == uriText ||
                                        candidate.favoriteUri == uriText
                                }
                                val favoriteArchive = (0 until manifestFiles.length())
                                    .mapNotNull(manifestFiles::optJSONObject)
                                    .firstOrNull { fileObject ->
                                        val savedFavoriteId = fileObject.optString("favoriteId")
                                        (mapping?.favoriteId?.isNotBlank() == true &&
                                            savedFavoriteId.equals(mapping.favoriteId, ignoreCase = true)) ||
                                            fileObject.optString("favoriteMediaUri") == mapping?.favoriteUri
                                    }
                                    ?.optString("archiveFile")
                                    .orEmpty()

                                put(JSONObject().apply {
                                    put("uri", uriText)
                                    put(
                                        "photoId",
                                        when {
                                            mapping?.photoId?.isNotBlank() == true ->
                                                mapping?.photoId.orEmpty()

                                            mapping?.favoriteUri == uriText ->
                                                ""

                                            else ->
                                                catalogPhoto?.id.orEmpty()
                                        }
                                    )
                                    put(
                                        "relativePath",
                                        mapping?.originalRelativePath
                                            ?.takeIf { it.isNotBlank() }
                                            ?: getOriginalRelativePath(uri)
                                    )
                                    put(
                                        "displayName",
                                        mapping?.originalFileName
                                            ?.takeIf { it.isNotBlank() }
                                            ?: details.displayName
                                    )
                                    put(
                                        "sizeBytes",
                                        if (mapping?.favoriteUri == uriText) {
                                            -1L
                                        } else {
                                            querySize(context, uri)
                                        }
                                    )
                                    put("originalSha256", mapping?.originalSha256.orEmpty())
                                    put("favoriteId", mapping?.favoriteId.orEmpty())
                                    put("favoriteArchiveFile", favoriteArchive)
                                })
                            }
                        })
                    })
                    report(FavoritesZipExportPhase.SAVING_ALBUMS, albumIndex + 1, albums.size, album.name)
                }
            })
        }

        report(FavoritesZipExportPhase.FINALIZING)
        zipOutput.putNextEntry(
            ZipEntry("manifest.json")
        )

        zipOutput.write(
            manifest
                .toString(2)
                .toByteArray(Charsets.UTF_8)
        )

        zipOutput.closeEntry()
    }

    return FavoritesZipExportResult(
        requestedCount = favorites.size,
        exportedCount = exportedCount,
        failedCount = failedCount,
        sourceBytesCopied = totalBytesCopied
    )
}

private data class FavoriteMediaDetails(
    val displayName: String,
    val mimeType: String
)

private fun readMediaDetails(
    context: Context,
    uri: Uri,
    fallbackName: String
): FavoriteMediaDetails {

    var displayName = fallbackName

    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->

            val nameColumn = cursor.getColumnIndex(
                OpenableColumns.DISPLAY_NAME
            )

            if (
                cursor.moveToFirst() &&
                nameColumn >= 0
            ) {
                displayName = cursor
                    .getString(nameColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackName
            }
        }
    }

    val mimeType = runCatching {
        context.contentResolver.getType(uri)
    }.getOrNull()
        ?: mimeTypeFromFileName(displayName)

    return FavoriteMediaDetails(
        displayName = displayName,
        mimeType = mimeType
    )
}

private fun buildArchiveName(
    index: Int,
    originalName: String
): String {

    val safeOriginalName = originalName
        .replace(
            Regex("""[\\/:*?"<>|]"""),
            "_"
        )
        .ifBlank {
            "favorite.jpg"
        }

    val number = (index + 1)
        .toString()
        .padStart(4, '0')

    return "${number}_$safeOriginalName"
}

private fun mimeTypeFromFileName(
    fileName: String
): String {

    return when (
        fileName
            .substringAfterLast(".", "")
            .lowercase()
    ) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "image/jpeg"
    }
}

private fun formattedCurrentDate(): String {
    return SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        Locale.US
    ).format(Date())
}

private fun querySize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
    } ?: -1L
}.getOrDefault(-1L)
