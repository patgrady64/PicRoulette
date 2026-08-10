package com.patgrady64.picroulette

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private const val MAX_FAVORITE_EDGE_PX = 3072

private data class FavoriteSourceInfo(
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Int,
    val orientedWidth: Int,
    val orientedHeight: Int
)

/**
 * Saves only the source region the user can currently see instead of decoding
 * the entire full-resolution photo. This avoids very large camera images
 * consuming hundreds of megabytes of bitmap memory.
 */
suspend fun saveToFavoritesFolder(
    context: Context,
    sourceUri: Uri,
    fileName: String,
    scale: Float,
    offset: Offset,
    containerSize: IntSize,
    displayMode: PhotoDisplayMode = PhotoDisplayMode.FIT
): Uri? = withContext(Dispatchers.IO) {
    var bitmapToSave: Bitmap? = null
    var insertedUri: Uri? = null

    try {
        if (containerSize.width <= 0 || containerSize.height <= 0) {
            return@withContext null
        }

        val sourceInfo = readFavoriteSourceInfo(context, sourceUri)
            ?: return@withContext null

        val crop = calculateSourceCropRect(
            imageWidth = sourceInfo.orientedWidth,
            imageHeight = sourceInfo.orientedHeight,
            containerSize = containerSize,
            userZoom = scale,
            offset = offset,
            displayMode = displayMode
        ) ?: return@withContext null

        bitmapToSave = decodeVisibleRegion(
            context = context,
            sourceUri = sourceUri,
            sourceInfo = sourceInfo,
            crop = crop
        ) ?: return@withContext null

        val originalStem = splitFileName(fileName).stem
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .take(40)
            .ifBlank { "favorite" }

        val timestamp = SimpleDateFormat(
            "yyyyMMddHHmmssSSS",
            Locale.US
        ).format(Date())

        val finalName = "${originalStem}_$timestamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PR_FAVS")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        insertedUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return@withContext null

        val written = context.contentResolver
            .openOutputStream(insertedUri)
            ?.use { output ->
                bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, output)
            } == true

        if (!written) {
            context.contentResolver.delete(insertedUri, null, null)
            insertedUri = null
            return@withContext null
        }

        val publishedRows = context.contentResolver.update(
            insertedUri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null
        )

        if (publishedRows <= 0) {
            context.contentResolver.delete(insertedUri, null, null)
            insertedUri = null
            return@withContext null
        }

        insertedUri
    } catch (exception: Exception) {
        insertedUri?.let { failedUri ->
            runCatching {
                context.contentResolver.delete(failedUri, null, null)
            }
        }

        Log.e(
            "PR_FAV",
            "Could not save favorite from $sourceUri",
            exception
        )
        null
    } finally {
        bitmapToSave?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

private fun readFavoriteSourceInfo(
    context: Context,
    sourceUri: Uri
): FavoriteSourceInfo? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    context.contentResolver.openInputStream(sourceUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val orientation = runCatching {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    val oriented = orientedDimensions(
        rawWidth = bounds.outWidth,
        rawHeight = bounds.outHeight,
        exifOrientation = orientation
    )

    return FavoriteSourceInfo(
        rawWidth = bounds.outWidth,
        rawHeight = bounds.outHeight,
        orientation = orientation,
        orientedWidth = oriented.width,
        orientedHeight = oriented.height
    )
}

private fun decodeVisibleRegion(
    context: Context,
    sourceUri: Uri,
    sourceInfo: FavoriteSourceInfo,
    crop: SourceCropRect
): Bitmap? {
    val rawCrop = mapOrientedCropToRaw(
        crop = crop,
        rawWidth = sourceInfo.rawWidth,
        rawHeight = sourceInfo.rawHeight,
        exifOrientation = sourceInfo.orientation
    )

    val sampledRegion = runCatching {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(input, false)
                ?: return@use null
            try {
                decoder.decodeRegion(
                    Rect(
                        rawCrop.left,
                        rawCrop.top,
                        rawCrop.right,
                        rawCrop.bottom
                    ),
                    BitmapFactory.Options().apply {
                        inSampleSize = calculateSampleSize(
                            rawCrop.width,
                            rawCrop.height,
                            MAX_FAVORITE_EDGE_PX
                        )
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } finally {
                @Suppress("DEPRECATION")
                decoder.recycle()
            }
        }
    }.getOrNull()

    if (sampledRegion != null) {
        return applyExifOrientation(sampledRegion, sourceInfo.orientation)
    }

    // Some providers/formats do not support region decoding. Fall back to a
    // bounded full-image decode rather than an unbounded full-resolution one.
    return decodeBoundedFallback(
        context = context,
        sourceUri = sourceUri,
        sourceInfo = sourceInfo,
        crop = crop
    )
}

private fun decodeBoundedFallback(
    context: Context,
    sourceUri: Uri,
    sourceInfo: FavoriteSourceInfo,
    crop: SourceCropRect
): Bitmap? {
    val sampleSize = calculateSampleSize(
        sourceInfo.rawWidth,
        sourceInfo.rawHeight,
        MAX_FAVORITE_EDGE_PX
    )

    val rawBitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } ?: return null

    val orientedBitmap = applyExifOrientation(rawBitmap, sourceInfo.orientation)
        ?: return null

    val scaleX = orientedBitmap.width.toFloat() / sourceInfo.orientedWidth
    val scaleY = orientedBitmap.height.toFloat() / sourceInfo.orientedHeight

    val left = (crop.left * scaleX).toInt()
        .coerceIn(0, orientedBitmap.width - 1)
    val top = (crop.top * scaleY).toInt()
        .coerceIn(0, orientedBitmap.height - 1)
    val right = (crop.right * scaleX).toInt()
        .coerceIn(left + 1, orientedBitmap.width)
    val bottom = (crop.bottom * scaleY).toInt()
        .coerceIn(top + 1, orientedBitmap.height)

    if (
        left == 0 && top == 0 &&
        right == orientedBitmap.width &&
        bottom == orientedBitmap.height
    ) {
        return orientedBitmap
    }

    val cropped = Bitmap.createBitmap(
        orientedBitmap,
        left,
        top,
        right - left,
        bottom - top
    )

    if (cropped !== orientedBitmap && !orientedBitmap.isRecycled) {
        orientedBitmap.recycle()
    }

    return cropped
}

private fun calculateSampleSize(
    width: Int,
    height: Int,
    maxEdge: Int
): Int {
    var sampleSize = 1
    while (max(width / sampleSize, height / sampleSize) > maxEdge) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun applyExifOrientation(
    bitmap: Bitmap,
    orientation: Int
): Bitmap? {
    val matrix = Matrix()

    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            matrix.setScale(-1f, 1f)

        ExifInterface.ORIENTATION_ROTATE_180 ->
            matrix.setRotate(180f)

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 ->
            matrix.setRotate(90f)

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 ->
            matrix.setRotate(-90f)

        else -> return bitmap
    }

    val transformed = runCatching {
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }.getOrNull()

    if (transformed == null) {
        if (!bitmap.isRecycled) bitmap.recycle()
        return null
    }

    if (transformed !== bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
    }

    return transformed
}
