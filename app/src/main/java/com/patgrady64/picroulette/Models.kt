package com.patgrady64.picroulette

import android.net.Uri

data class FavoriteFile(val fileNameOnDisk: String, val mediaUri: Uri)
data class FolderConfig(val uri: Uri, val includeSubfolders: Boolean)
data class FavoriteMapping(
    val originalUri: String,
    val favoriteUri: String,
    val originalFileName: String,
    val dateAdded: Long,
    val isDeleted: Boolean = false
)