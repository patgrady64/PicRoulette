package com.patgrady64.picroulette

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class PhotoScanProgress(
    val photosFound: Int,
    val foldersCompleted: Int,
    val totalFolders: Int,
    val currentFolderName: String
)

data class PhotoScanFailure(
    val folderUri: Uri,
    val folderName: String,
    val reason: String
)

data class PhotoLibraryScanResult(
    val imagesByFolder: Map<String, List<Uri>>,
    val failures: List<PhotoScanFailure>
) {
    val images: List<Uri>
        get() = imagesByFolder.values
            .asSequence()
            .flatten()
            .distinctBy { it.toString() }
            .toList()
}

suspend fun scanPhotoLibrary(
    context: Context,
    folders: List<FolderConfig>,
    onProgress: (PhotoScanProgress) -> Unit
): PhotoLibraryScanResult = withContext(Dispatchers.IO) {
    val imagesByFolder = linkedMapOf<String, List<Uri>>()
    val failures = mutableListOf<PhotoScanFailure>()
    val allUniqueImages = LinkedHashSet<Uri>()
    val totalFolders = folders.size

    if (folders.isEmpty()) {
        withContext(Dispatchers.Main) {
            onProgress(PhotoScanProgress(0, 0, 0, ""))
        }
        return@withContext PhotoLibraryScanResult(emptyMap(), emptyList())
    }

    folders.forEachIndexed { folderIndex, config ->
        val folderName = readableFolderName(config.uri)
        val folderImages = LinkedHashSet<Uri>()

        val result = runCatching {
            // Re-taking an already persisted grant can throw on some providers;
            // scanning should still proceed because the existing grant may work.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    config.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val rootDocumentId = DocumentsContract.getTreeDocumentId(config.uri)

            scanDirectoryWithProgress(
                context = context,
                treeUri = config.uri,
                parentDocumentId = rootDocumentId,
                recursive = config.includeSubfolders
            ) { imageUri ->
                folderImages.add(imageUri)
                val wasNewGlobally = allUniqueImages.add(imageUri)

                if (
                    wasNewGlobally &&
                    (allUniqueImages.size == 1 || allUniqueImages.size % 25 == 0)
                ) {
                    withContext(Dispatchers.Main) {
                        onProgress(
                            PhotoScanProgress(
                                photosFound = allUniqueImages.size,
                                foldersCompleted = folderIndex,
                                totalFolders = totalFolders,
                                currentFolderName = folderName
                            )
                        )
                    }
                }
            }
        }

        if (result.isSuccess) {
            imagesByFolder[config.uri.toString()] = folderImages.toList()
        } else {
            failures += PhotoScanFailure(
                folderUri = config.uri,
                folderName = folderName,
                reason = result.exceptionOrNull()?.message
                    ?: "The folder could not be read."
            )
        }

        withContext(Dispatchers.Main) {
            onProgress(
                PhotoScanProgress(
                    photosFound = allUniqueImages.size,
                    foldersCompleted = folderIndex + 1,
                    totalFolders = totalFolders,
                    currentFolderName = folderName
                )
            )
        }
    }

    PhotoLibraryScanResult(
        imagesByFolder = imagesByFolder,
        failures = failures
    )
}

private suspend fun scanDirectoryWithProgress(
    context: Context,
    treeUri: Uri,
    parentDocumentId: String,
    recursive: Boolean,
    onImageFound: suspend (Uri) -> Unit
) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        parentDocumentId
    )

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    val cursor = context.contentResolver.query(
        childrenUri,
        projection,
        null,
        null,
        null
    ) ?: throw IOException("The folder provider returned no results.")

    cursor.use {
        val documentIdColumn = it.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        val mimeTypeColumn = it.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        while (it.moveToNext()) {
            val documentId = it.getString(documentIdColumn)
            val mimeType = it.getString(mimeTypeColumn)

            when {
                mimeType == DocumentsContract.Document.MIME_TYPE_DIR && recursive -> {
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
