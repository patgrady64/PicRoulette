package com.patgrady64.picroulette.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.patgrady64.picroulette.FavoriteFile
import com.patgrady64.picroulette.FavoriteMapping
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
    mappings: List<FavoriteMapping>
): FavoritesZipExportResult {

    val outputStream = context.contentResolver
        .openOutputStream(destinationUri, "w")
        ?: throw IllegalStateException(
            "Could not open the selected backup destination."
        )

    val manifestFiles = JSONArray()

    var exportedCount = 0
    var failedCount = 0
    var totalBytesCopied = 0L

    ZipOutputStream(outputStream.buffered()).use { zipOutput ->

        favorites.forEachIndexed { index, favorite ->

            val mapping =
                mappings.firstOrNull {
                    it.favoriteUri ==
                            favorite.mediaUri.toString()
                }

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
                                        mapping.originalUri.isNotBlank()
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
                2
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
             * This first backup protects the edited copies.
             * Source-photo links will be added in the migration phase.
             */
            put(
                "containsSourceLinks",
                mappings.any {
                    it.favoriteUri.isNotBlank() &&
                        (it.isDeleted || it.originalUri.isNotBlank())
                }
            )

            put(
                "files",
                manifestFiles
            )
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

    val mimeType = context.contentResolver
        .getType(uri)
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