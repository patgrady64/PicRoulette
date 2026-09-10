package com.patgrady64.picroulette

internal fun preferredAlbumPhotoUri(
    originalKey: String,
    originalUri: String,
    favoriteUrisByOriginalKey: Map<String, String>,
    canOpen: (String) -> Boolean
): String? {
    val favoriteUri = favoriteUrisByOriginalKey[originalKey]
    if (!favoriteUri.isNullOrBlank() && canOpen(favoriteUri)) {
        return favoriteUri
    }
    return originalUri.takeIf(canOpen)
}
