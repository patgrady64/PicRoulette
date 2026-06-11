package com.patgrady64.picroulette

import android.net.Uri

// --- DATA MODELS ---
data class FavoriteFile(val fileNameOnDisk: String, val mediaUri: Uri)
data class FolderConfig(val uri: Uri, val includeSubfolders: Boolean = true)