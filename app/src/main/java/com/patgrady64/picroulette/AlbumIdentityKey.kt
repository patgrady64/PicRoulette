package com.patgrady64.picroulette

/** Pure, testable identity-key construction used by Albums v2. */
internal fun albumStableKey(
    authority: String,
    documentId: String?,
    scheme: String?,
    path: String?,
    normalizedUri: String
): String = when {
    !documentId.isNullOrBlank() -> "doc:$authority:$documentId"
    scheme.equals("file", ignoreCase = true) -> "file:${path.orEmpty()}"
    else -> "uri:$normalizedUri"
}
