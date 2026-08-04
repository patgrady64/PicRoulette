package com.patgrady64.picroulette

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class PhotoFileDetails(
    val displayName: String,
    val sizeBytes: Long?
)

data class PhotoRenameResult(
    val renamedUri: Uri? = null,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = renamedUri != null && errorMessage == null
}

fun queryPhotoFileDetails(
    context: Context,
    uri: Uri
): PhotoFileDetails {
    var displayName = uri.lastPathSegment ?: "Unknown"
    var sizeBytes: Long? = null

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
            if (!cursor.moveToFirst()) {
                return@use
            }

            val nameIndex =
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex =
                cursor.getColumnIndex(OpenableColumns.SIZE)

            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                displayName = cursor.getString(nameIndex)
            }

            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }

    return PhotoFileDetails(
        displayName = displayName,
        sizeBytes = sizeBytes
    )
}

suspend fun renamePhotoFile(
    context: Context,
    uri: Uri,
    newDisplayName: String
): PhotoRenameResult = withContext(Dispatchers.IO) {
    val isDocumentUri =
        DocumentsContract.isDocumentUri(context, uri)

    try {
        val resolver = context.contentResolver

        val renamedUri =
            if (isDocumentUri) {
                DocumentsContract.renameDocument(
                    resolver,
                    uri,
                    newDisplayName
                ) ?: throw IOException(
                    "This folder does not support renaming files."
                )
            } else {
                val values = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        newDisplayName
                    )
                }

                val rowsUpdated = resolver.update(
                    uri,
                    values,
                    null,
                    null
                )

                if (rowsUpdated <= 0) {
                    throw IOException("The file could not be renamed.")
                }

                uri
            }

        PhotoRenameResult(renamedUri = renamedUri)
    } catch (_: SecurityException) {
        PhotoRenameResult(
            errorMessage =
                if (isDocumentUri) {
                    "PicRoulette does not have permission to rename this " +
                        "file. Remove and add its library folder again, " +
                        "then retry."
                } else {
                    "Android did not allow PicRoulette to rename this file."
                }
        )
    } catch (exception: Exception) {
        PhotoRenameResult(
            errorMessage = exception.message
                ?: "The file could not be renamed."
        )
    }
}

suspend fun deletePhotoFile(
    context: Context,
    uri: Uri
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val deleted =
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    uri
                )
            } else {
                context.contentResolver.delete(
                    uri,
                    null,
                    null
                ) > 0
            }

        if (!deleted) {
            throw IOException("The photo could not be deleted.")
        }
    }
}
