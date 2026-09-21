package com.patgrady64.picroulette

import android.net.Uri

data class FavoriteFile(
    val fileNameOnDisk: String,
    val mediaUri: Uri
)

data class FolderConfig(
    val uri: Uri,
    val includeSubfolders: Boolean
)

data class FavoriteMapping(
    val originalUri: String,
    val favoriteUri: String,
    val originalFileName: String,

    /*
     * Durable PicRoulette identities. These are intentionally independent
     * of Android content URIs, which can change after moves/rescans.
     */
    val photoId: String = "",
    val favoriteId: String = "",
    val favoriteFileName: String = "",

    /*
     * Portable identity information.
     *
     * These have defaults so mappings created by older
     * PicRoulette versions still work.
     */
    val originalRelativePath: String = "",
    val originalSha256: String = "",

    val dateAdded: Long,
    val isDeleted: Boolean = false
)