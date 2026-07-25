package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Gets a portable path from a Storage Access Framework URI.
 *
 * Example document ID:
 *
 * C11A-1604:pics/vacation/01.jpeg
 *
 * Result:
 *
 * pics/vacation/01.jpeg
 */
fun getOriginalRelativePath(
    uri: Uri
): String {

    val documentId =
        runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull()

    return if (!documentId.isNullOrBlank()) {
        Uri.decode(
            documentId.substringAfter(
                delimiter = ":",
                missingDelimiterValue =
                    documentId
            )
        )
            .trim()
            .trimStart('/')
    } else {
        Uri.decode(
            uri.lastPathSegment.orEmpty()
        )
            .trim()
            .trimStart('/')
    }
}

/**
 * Calculates an exact SHA-256 identity for the original image.
 *
 * This runs on Dispatchers.IO so it does not freeze the UI.
 */
suspend fun calculateOriginalSha256(
    context: Context,
    uri: Uri
): String {

    return withContext(Dispatchers.IO) {
        runCatching {
            val digest =
                MessageDigest.getInstance(
                    "SHA-256"
                )

            val inputStream =
                context.contentResolver
                    .openInputStream(uri)
                    ?: return@runCatching ""

            inputStream.buffered().use { input ->
                val buffer =
                    ByteArray(64 * 1024)

                while (true) {
                    val read =
                        input.read(buffer)

                    if (read < 0) {
                        break
                    }

                    if (read > 0) {
                        digest.update(
                            buffer,
                            0,
                            read
                        )
                    }
                }
            }

            digest.digest()
                .joinToString("") { byte ->
                    (byte.toInt() and 0xFF)
                        .toString(16)
                        .padStart(2, '0')
                }
        }.getOrDefault("")
    }
}