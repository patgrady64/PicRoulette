package com.patgrady64.picroulette.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.patgrady64.picroulette.FavoriteFile
import com.patgrady64.picroulette.FavoriteMapping
import com.patgrady64.picroulette.PhotoAlbum
import com.patgrady64.picroulette.PicRouletteDatabase
import com.patgrady64.picroulette.favoriteIdFromFileName
import com.patgrady64.picroulette.findFavoriteMappingForFile
import com.patgrady64.picroulette.getOriginalRelativePath
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    albums: List<PhotoAlbum> = emptyList()
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

    ZipOutputStream(outputStream.buffered()).use { zipOutput ->

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

                        val bytesCopied = inputStream.copyTo(zipOutput)

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
        }

        /*
         * Write the manifest after processing the files so its counts reflect
         * what was actually copied successfully.
         */
        val manifest = JSONObject().apply {
            put(
                "backupType",
                "PicRoulette Favorites ZIP"
            )

            put(
                "backupVersion",
                4
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

            put(
                "files",
                manifestFiles
            )

            put("albums", JSONArray().apply {
                albums.forEach { album ->
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
                }
            })
        }

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
