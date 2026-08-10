package com.patgrady64.picroulette

data class FavoriteMappingUpdateResult(
    val mappings: MutableList<FavoriteMapping>,
    val changed: Boolean
)

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
