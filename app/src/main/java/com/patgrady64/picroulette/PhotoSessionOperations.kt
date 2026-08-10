package com.patgrady64.picroulette

data class PhotoSessionReconciliation(
    val sessionUris: List<String>,
    val currentIndex: Int,
    val removedCount: Int
)

/**
 * Removes photos that are no longer present in the latest trustworthy source
 * list while keeping the current photo selected whenever it still exists.
 */
fun reconcilePhotoSession(
    sessionUris: List<String>,
    availableUris: Set<String>,
    oldCurrentIndex: Int
): PhotoSessionReconciliation {
    if (sessionUris.isEmpty()) {
        return PhotoSessionReconciliation(
            sessionUris = emptyList(),
            currentIndex = 0,
            removedCount = 0
        )
    }

    val safeOldIndex = oldCurrentIndex.coerceIn(0, sessionUris.lastIndex)
    val previouslyCurrentUri = sessionUris[safeOldIndex]
    val filtered = sessionUris.filter { it in availableUris }

    val newIndex = when {
        filtered.isEmpty() -> 0
        previouslyCurrentUri in filtered -> filtered.indexOf(previouslyCurrentUri)
        else -> safeOldIndex.coerceAtMost(filtered.lastIndex)
    }

    return PhotoSessionReconciliation(
        sessionUris = filtered,
        currentIndex = newIndex,
        removedCount = sessionUris.size - filtered.size
    )
}
