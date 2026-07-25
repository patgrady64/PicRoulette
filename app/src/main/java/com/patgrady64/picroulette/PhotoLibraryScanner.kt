package com.patgrady64.picroulette

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Progress information for a library scan.
 *
 * The total number of photos is not known until the scan finishes, so this
 * reports a truthful live count instead of pretending to know a percentage.
 */
data class PhotoScanProgress(
    val photosFound: Int,
    val foldersCompleted: Int,
    val totalFolders: Int,
    val currentFolderName: String
)

/**
 * Scans every configured folder and reports a live count as photos are found.
 *
 * Progress is sent to the main thread in small batches so scanning thousands
 * of files does not overwhelm Compose with a recomposition for every image.
 */
suspend fun scanPhotoLibrary(
    context: Context,
    folders: List<FolderConfig>,
    onProgress: (PhotoScanProgress) -> Unit
): List<Uri> = withContext(Dispatchers.IO) {

    val uniqueImages = LinkedHashSet<Uri>()
    val totalFolders = folders.size

    if (folders.isEmpty()) {
        withContext(Dispatchers.Main) {
            onProgress(
                PhotoScanProgress(
                    photosFound = 0,
                    foldersCompleted = 0,
                    totalFolders = 0,
                    currentFolderName = ""
                )
            )
        }

        return@withContext emptyList()
    }

    folders.forEachIndexed { folderIndex, config ->
        val folderName = readableFolderName(config.uri)

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                config.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(config.uri)
        }.getOrNull()

        if (rootDocumentId != null) {
            scanDirectoryWithProgress(
                context = context,
                treeUri = config.uri,
                parentDocumentId = rootDocumentId,
                recursive = config.includeSubfolders
            ) { imageUri ->
                val wasAdded = uniqueImages.add(imageUri)

                if (
                    wasAdded &&
                    (uniqueImages.size == 1 || uniqueImages.size % 25 == 0)
                ) {
                    withContext(Dispatchers.Main) {
                        onProgress(
                            PhotoScanProgress(
                                photosFound = uniqueImages.size,
                                foldersCompleted = folderIndex,
                                totalFolders = totalFolders,
                                currentFolderName = folderName
                            )
                        )
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            onProgress(
                PhotoScanProgress(
                    photosFound = uniqueImages.size,
                    foldersCompleted = folderIndex + 1,
                    totalFolders = totalFolders,
                    currentFolderName = folderName
                )
            )
        }
    }

    uniqueImages.toList()
}

private suspend fun scanDirectoryWithProgress(
    context: Context,
    treeUri: Uri,
    parentDocumentId: String,
    recursive: Boolean,
    onImageFound: suspend (Uri) -> Unit
) {
    val childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        )

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    runCatching {
        context.contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val documentIdColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )

            val mimeTypeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(documentIdColumn)
                val mimeType = cursor.getString(mimeTypeColumn)

                when {
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                            recursive -> {
                        scanDirectoryWithProgress(
                            context = context,
                            treeUri = treeUri,
                            parentDocumentId = documentId,
                            recursive = true,
                            onImageFound = onImageFound
                        )
                    }

                    mimeType?.startsWith("image/") == true -> {
                        onImageFound(
                            DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                documentId
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun readableFolderName(uri: Uri): String {
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull()

    return Uri.decode(
        documentId
            ?.substringAfter(":")
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "Library folder"
    )
}
