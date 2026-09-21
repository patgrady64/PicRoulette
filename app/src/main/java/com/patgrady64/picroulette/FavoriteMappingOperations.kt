package com.patgrady64.picroulette

data class FavoriteMappingUpdateResult(
    val mappings: MutableList<FavoriteMapping>,
    val changed: Boolean
)

/**
 * Finds the active favorite mapping for the photo currently being viewed.
 *
 * Exact URI matches are preferred. If Android has changed the URI representation
 * of an original library photo, callers may provide an identity comparison that
 * can recognize the saved original URI as the same underlying document.
 *
 * The identity fallback is intentionally applied only to originalUri. A stale or
 * recycled favorite-copy URI must never make an unrelated photo look favorited.
 */
fun findActiveFavoriteMapping(
    mappings: List<FavoriteMapping>,
    photoUri: String,
    originalIdentityMatches: (savedOriginalUri: String, currentPhotoUri: String) -> Boolean =
        { savedOriginalUri, currentPhotoUri -> savedOriginalUri == currentPhotoUri }
): FavoriteMapping? {
    val activeMappings = mappings.asSequence().filter { mapping ->
        !mapping.isDeleted && mapping.favoriteUri.isNotBlank()
    }

    activeMappings.firstOrNull { mapping ->
        mapping.originalUri == photoUri || mapping.favoriteUri == photoUri
    }?.let { return it }

    return mappings.firstOrNull { mapping ->
        !mapping.isDeleted &&
            mapping.favoriteUri.isNotBlank() &&
            mapping.originalUri.isNotBlank() &&
            runCatching {
                originalIdentityMatches(mapping.originalUri, photoUri)
            }.getOrDefault(false)
    }
}

fun replaceFavoriteMappingUri(
    mappings: List<FavoriteMapping>,
    oldFavoriteUri: String,
    newFavoriteUri: String
): FavoriteMappingUpdateResult {
    var changed = false
    val updated = mappings.map { mapping ->
        if (mapping.favoriteUri == oldFavoriteUri) {
            changed = true
            mapping.copy(favoriteUri = newFavoriteUri)
        } else {
            mapping
        }
    }.toMutableList()

    return FavoriteMappingUpdateResult(updated, changed)
}

fun remapFavoriteUris(
    mappings: List<FavoriteMapping>,
    uriChanges: Map<String, String>
): FavoriteMappingUpdateResult {
    var changed = false
    val updated = mappings.map { mapping ->
        uriChanges[mapping.favoriteUri]?.let { newUri ->
            changed = true
            mapping.copy(favoriteUri = newUri)
        } ?: mapping
    }.toMutableList()

    return FavoriteMappingUpdateResult(updated, changed)
}

fun cleanStaleFavoriteMappings(
    mappingsAtRefreshStart: List<FavoriteMapping>,
    latestMappings: List<FavoriteMapping>,
    diskFavoriteUris: Set<String>
): FavoriteMappingUpdateResult {
    val staleUris = mappingsAtRefreshStart
        .filter { mapping ->
            mapping.favoriteUri.isBlank() ||
                mapping.favoriteUri !in diskFavoriteUris
        }
        .mapTo(mutableSetOf()) { it.favoriteUri }

    val cleaned = latestMappings.filterNot { mapping ->
        mapping.favoriteUri in staleUris
    }.toMutableList()

    return FavoriteMappingUpdateResult(
        mappings = cleaned,
        changed = cleaned != latestMappings
    )
}

fun removeFavoriteMapping(
    mappings: List<FavoriteMapping>,
    mappingToRemove: FavoriteMapping
): FavoriteMappingUpdateResult {
    val updated = mappings.filterNot { it == mappingToRemove }.toMutableList()
    return FavoriteMappingUpdateResult(
        mappings = updated,
        changed = updated.size != mappings.size
    )
}

fun markOriginalsDeleted(
    mappings: List<FavoriteMapping>,
    deletedOriginalUris: Set<String>
): FavoriteMappingUpdateResult {
    var changed = false

    val updated = mappings.map { mapping ->
        if (
            mapping.originalUri in deletedOriginalUris &&
            !mapping.isDeleted
        ) {
            changed = true
            mapping.copy(isDeleted = true)
        } else {
            mapping
        }
    }.toMutableList()

    return FavoriteMappingUpdateResult(
        mappings = updated,
        changed = changed
    )
}

fun markOriginalDeleted(
    mappings: List<FavoriteMapping>,
    deletedOriginalUri: String
): FavoriteMappingUpdateResult =
    markOriginalsDeleted(
        mappings = mappings,
        deletedOriginalUris = setOf(deletedOriginalUri)
    )
