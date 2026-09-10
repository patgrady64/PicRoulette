package com.patgrady64.picroulette

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

private const val FAVORITES_TREE_URI_KEY = "favorites_tree_uri"
private const val FAVORITES_FOLDER_NAME = "PR_FAVS"
private const val DEFAULT_FAVORITES_RELATIVE_PATH = "Pictures/PR_FAVS"

data class FavoriteFolderMigrationResult(
    val movedCount: Int,
    val updatedMappings: MutableList<FavoriteMapping>,
    val destinationTreeUri: Uri?
)

data class FavoriteFolderMigrationProgress(
    val completed: Int,
    val total: Int,
    val status: String
)

internal data class FavoriteDestination(
    val uri: Uri,
    val requiresPublish: Boolean,
    val deleteOnFailure: Boolean = true
)

fun getFavoriteFolderTreeUri(context: Context): Uri? =
    context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
        .getString(FAVORITES_TREE_URI_KEY, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)

fun favoriteFolderDescription(context: Context): String {
    val treeUri = getFavoriteFolderTreeUri(context)
        ?: return "Pictures/$FAVORITES_FOLDER_NAME (default)"

    return DocumentFile.fromTreeUri(context, treeUri)?.name
        ?.let { selectedName ->
            if (selectedName.equals(FAVORITES_FOLDER_NAME, ignoreCase = true)) {
                "$FAVORITES_FOLDER_NAME (custom)"
            } else {
                "$FAVORITES_FOLDER_NAME inside $selectedName"
            }
        }
        ?: "Custom $FAVORITES_FOLDER_NAME folder"
}

private fun saveFavoriteFolderTreeUri(
    context: Context,
    treeUri: Uri?
): Boolean {
    val editor = context.getSharedPreferences(
        "PicRoulettePrefs",
        Context.MODE_PRIVATE
    ).edit()

    if (treeUri == null) {
        editor.remove(FAVORITES_TREE_URI_KEY)
    } else {
        editor.putString(FAVORITES_TREE_URI_KEY, treeUri.toString())
    }

    return editor.commit()
}

private fun resolveFavoritesDirectory(
    context: Context,
    selectedTreeUri: Uri
): DocumentFile? {
    val selected = DocumentFile.fromTreeUri(context, selectedTreeUri)
        ?: return null

    if (!selected.isDirectory || !selected.canWrite()) return null
    if (selected.name.equals(FAVORITES_FOLDER_NAME, ignoreCase = true)) {
        return selected
    }

    return selected.findFile(FAVORITES_FOLDER_NAME)
        ?.takeIf { it.isDirectory && it.canWrite() }
        ?: selected.createDirectory(FAVORITES_FOLDER_NAME)
}

private fun createOrReplaceFavoriteDocument(
    context: Context,
    folder: DocumentFile,
    mimeType: String,
    requestedName: String
): FavoriteDestination {
    val safeName = requestedName
        .replace(Regex("[\\u0000-\\u001F/\\\\:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "favorite.jpg" }
    val existing = folder.listFiles().firstOrNull { candidate ->
        candidate.name.equals(safeName, ignoreCase = true)
    }
    if (existing != null && existing.isFile && existing.canWrite()) {
        return FavoriteDestination(
            uri = existing.uri,
            requiresPublish = false,
            deleteOnFailure = false
        )
    }

    val createdUri = try {
        DocumentsContract.createDocument(
            context.contentResolver,
            folder.uri,
            mimeType,
            safeName
        )
    } catch (exception: Exception) {
        throw IOException(
            "The SD card refused to create $safeName: " +
                (exception.message ?: exception.javaClass.simpleName),
            exception
        )
    } ?: throw IOException(
        "The SD card refused to create $safeName in PR_FAVS."
    )

    return FavoriteDestination(
        uri = createdUri,
        requiresPublish = false,
        deleteOnFailure = true
    )
}

internal fun createFavoriteDestination(
    context: Context,
    fileName: String,
    mimeType: String = "image/jpeg"
): FavoriteDestination? {
    val customTree = getFavoriteFolderTreeUri(context)

    if (customTree != null) {
        val folder = resolveFavoritesDirectory(context, customTree)
            ?: return null
        val fileUri = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                folder.uri,
                mimeType,
                fileName
            )
        }.getOrNull() ?: return null
        return FavoriteDestination(fileUri, requiresPublish = false)
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_FAVORITES_RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: return null

    return FavoriteDestination(uri, requiresPublish = true)
}

internal fun publishFavoriteDestination(
    context: Context,
    destination: FavoriteDestination
): Boolean {
    if (!destination.requiresPublish) return true

    return context.contentResolver.update(
        destination.uri,
        ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        },
        null,
        null
    ) > 0
}

internal fun readFavoriteFilesFromConfiguredFolder(
    context: Context
): List<FavoriteFile> {
    val customTree = getFavoriteFolderTreeUri(context)
    if (customTree != null) {
        val folder = resolveFavoritesDirectory(context, customTree)
            ?: throw IOException("The custom Favorites folder is unavailable.")

        return folder.listFiles()
            .asSequence()
            .filter { it.isFile && it.type?.startsWith("image/") == true }
            .map { file ->
                FavoriteFile(
                    fileNameOnDisk = file.name ?: "favorite",
                    mediaUri = file.uri
                )
            }
            .toList()
    }

    val list = mutableListOf<FavoriteFile>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME
    )
    val cursor = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
        arrayOf("$DEFAULT_FAVORITES_RELATIVE_PATH/"),
        null
    ) ?: throw IOException("The Favorites folder could not be read.")

    cursor.use {
        val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        while (it.moveToNext()) {
            list += FavoriteFile(
                fileNameOnDisk = it.getString(nameCol),
                mediaUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    it.getLong(idCol)
                )
            )
        }
    }
    return list
}

suspend fun migrateFavoriteFolder(
    context: Context,
    selectedParentTreeUri: Uri?,
    mappings: List<FavoriteMapping>,
    onProgress: (FavoriteFolderMigrationProgress) -> Unit = {}
): Result<FavoriteFolderMigrationResult> = runCatching {
    val oldTreeUri = getFavoriteFolderTreeUri(context)
    val destinationTreeUri = selectedParentTreeUri?.also { selected ->
        resolveFavoritesDirectory(context, selected)
            ?: throw IOException("PicRoulette could not create or open PR_FAVS there.")
    }

    if (oldTreeUri == destinationTreeUri) {
        return@runCatching FavoriteFolderMigrationResult(
            movedCount = 0,
            updatedMappings = mappings.toMutableList(),
            destinationTreeUri = destinationTreeUri
        )
    }

    val sourceFiles = readFavoriteFilesFromConfiguredFolder(context)
    val created = mutableListOf<Pair<FavoriteFile, FavoriteDestination>>()
    onProgress(
        FavoriteFolderMigrationProgress(
            completed = 0,
            total = sourceFiles.size,
            status = if (sourceFiles.isEmpty()) {
                "Preparing Favorites folder…"
            } else {
                "Preparing ${sourceFiles.first().fileNameOnDisk}"
            }
        )
    )

    var mappingsCommitted = false
    var folderCommitted = false
    try {
        sourceFiles.forEachIndexed { index, source ->
            onProgress(
                FavoriteFolderMigrationProgress(
                    completed = index,
                    total = sourceFiles.size,
                    status = "Moving ${source.fileNameOnDisk}"
                )
            )
            val destination = if (destinationTreeUri == null) {
                createMediaStoreMigrationDestination(context, source.fileNameOnDisk)
            } else {
                val folder = resolveFavoritesDirectory(context, destinationTreeUri)
                    ?: throw IOException("The selected Favorites folder is unavailable.")
                createOrReplaceFavoriteDocument(
                    context = context,
                    folder = folder,
                    mimeType = "image/jpeg",
                    requestedName = source.fileNameOnDisk
                )
            }

            val copied = if (source.mediaUri == destination.uri) {
                true
            } else {
                context.contentResolver.openInputStream(source.mediaUri)?.use { input ->
                    context.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                        input.copyTo(output)
                        true
                    }
                } == true
            }

            if (!copied || !publishFavoriteDestination(context, destination)) {
                if (destination.deleteOnFailure) {
                    context.contentResolver.delete(destination.uri, null, null)
                }
                throw IOException("Could not move ${source.fileNameOnDisk}.")
            }
            created += source to destination
            onProgress(
                FavoriteFolderMigrationProgress(
                    completed = index + 1,
                    total = sourceFiles.size,
                    status = "Moved ${source.fileNameOnDisk}"
                )
            )
        }

        val uriChanges = created.associate { (source, destination) ->
            source.mediaUri.toString() to destination.uri.toString()
        }
        val updatedMappings = remapFavoriteUris(
            mappings = mappings,
            uriChanges = uriChanges
        ).mappings

        onProgress(
            FavoriteFolderMigrationProgress(
                completed = sourceFiles.size,
                total = sourceFiles.size,
                status = "Repairing favorite and album connections…"
            )
        )

        if (!saveFavoriteMappings(context, updatedMappings, synchronous = true)) {
            throw IOException("The new Favorites location could not be saved.")
        }
        mappingsCommitted = true
        if (!saveFavoriteFolderTreeUri(context, destinationTreeUri)) {
            throw IOException("The new Favorites location could not be saved.")
        }
        folderCommitted = true

        onProgress(
            FavoriteFolderMigrationProgress(
                completed = sourceFiles.size,
                total = sourceFiles.size,
                status = "Finishing move…"
            )
        )

        // Connections now point at the verified copies. A failed cleanup only
        // leaves a harmless duplicate; it can never break an album or favorite.
        created.forEach { (source, destination) ->
            if (source.mediaUri == destination.uri) return@forEach
            runCatching {
                context.contentResolver.delete(source.mediaUri, null, null)
            }
        }

        FavoriteFolderMigrationResult(
            movedCount = created.size,
            updatedMappings = updatedMappings,
            destinationTreeUri = destinationTreeUri
        )
    } catch (exception: Exception) {
        if (mappingsCommitted) {
            saveFavoriteMappings(context, mappings, synchronous = true)
        }
        if (folderCommitted || getFavoriteFolderTreeUri(context) != oldTreeUri) {
            saveFavoriteFolderTreeUri(context, oldTreeUri)
        }
        created.forEach { (_, destination) ->
            if (destination.deleteOnFailure) {
                runCatching {
                    context.contentResolver.delete(destination.uri, null, null)
                }
            }
        }
        throw exception
    }
}

private fun createMediaStoreMigrationDestination(
    context: Context,
    fileName: String
): FavoriteDestination {
    val existingCursor = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
        arrayOf("$DEFAULT_FAVORITES_RELATIVE_PATH/", fileName),
        "${MediaStore.Images.Media._ID} DESC"
    )
    existingCursor?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            )
            return FavoriteDestination(
                uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                ),
                requiresPublish = false,
                deleteOnFailure = false
            )
        }
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_FAVORITES_RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: throw IOException("Could not create $fileName in the default Favorites folder.")
    return FavoriteDestination(uri, requiresPublish = true)
}
