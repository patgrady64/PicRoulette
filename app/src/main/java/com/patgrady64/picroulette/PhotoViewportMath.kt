package com.patgrady64.picroulette

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

const val MIN_PHOTO_ZOOM = 1f
const val MAX_PHOTO_ZOOM = 8f

/**
 * Geometry used by both the viewer and favorite crop saver so what the user
 * sees on screen maps to the same source-image region that is saved.
 */
data class PhotoViewportGeometry(
    val baseScale: Float,
    val fitScale: Float,
    val displayModeMultiplier: Float,
    val maxPanX: Float,
    val maxPanY: Float
)

fun clampPhotoZoom(currentZoom: Float, zoomChange: Float): Float =
    (currentZoom * zoomChange).coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_ZOOM)

fun calculatePhotoViewportGeometry(
    imageWidth: Int,
    imageHeight: Int,
    containerSize: IntSize,
    userZoom: Float,
    displayMode: PhotoDisplayMode
): PhotoViewportGeometry? {
    if (
        imageWidth <= 0 ||
        imageHeight <= 0 ||
        containerSize.width <= 0 ||
        containerSize.height <= 0
    ) {
        return null
    }

    val viewWidth = containerSize.width.toFloat()
    val viewHeight = containerSize.height.toFloat()
    val sourceWidth = imageWidth.toFloat()
    val sourceHeight = imageHeight.toFloat()

    val fitScale = min(
        viewWidth / sourceWidth,
        viewHeight / sourceHeight
    )
    val fillScale = max(
        viewWidth / sourceWidth,
        viewHeight / sourceHeight
    )
    val baseScale =
        if (displayMode == PhotoDisplayMode.FIT) fitScale else fillScale

    val safeZoom = userZoom.coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_ZOOM)
    val scaledWidth = sourceWidth * baseScale * safeZoom
    val scaledHeight = sourceHeight * baseScale * safeZoom

    return PhotoViewportGeometry(
        baseScale = baseScale,
        fitScale = fitScale,
        displayModeMultiplier =
            if (fitScale > 0f) baseScale / fitScale else 1f,
        maxPanX = ((scaledWidth - viewWidth) / 2f).coerceAtLeast(0f),
        maxPanY = ((scaledHeight - viewHeight) / 2f).coerceAtLeast(0f)
    )
}

fun clampPhotoOffset(
    proposedOffset: Offset,
    imageWidth: Int,
    imageHeight: Int,
    containerSize: IntSize,
    userZoom: Float,
    displayMode: PhotoDisplayMode
): Offset {
    val geometry = calculatePhotoViewportGeometry(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        containerSize = containerSize,
        userZoom = userZoom,
        displayMode = displayMode
    ) ?: return Offset.Zero

    return Offset(
        x = proposedOffset.x.coerceIn(-geometry.maxPanX, geometry.maxPanX),
        y = proposedOffset.y.coerceIn(-geometry.maxPanY, geometry.maxPanY)
    )
}

/**
 * Source-image crop rectangle in the image's correctly-oriented coordinate
 * space. Float coordinates are retained until decoding so rounding does not
 * accidentally cut off an edge.
 */
data class SourceCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

fun calculateSourceCropRect(
    imageWidth: Int,
    imageHeight: Int,
    containerSize: IntSize,
    userZoom: Float,
    offset: Offset,
    displayMode: PhotoDisplayMode
): SourceCropRect? {
    val geometry = calculatePhotoViewportGeometry(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        containerSize = containerSize,
        userZoom = userZoom,
        displayMode = displayMode
    ) ?: return null

    val safeZoom = userZoom.coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_ZOOM)
    val safeOffset = clampPhotoOffset(
        proposedOffset = offset,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        containerSize = containerSize,
        userZoom = safeZoom,
        displayMode = displayMode
    )

    val totalScale = (geometry.baseScale * safeZoom).coerceAtLeast(0.0001f)
    val cropWidth =
        (containerSize.width.toFloat() / totalScale)
            .coerceAtMost(imageWidth.toFloat())
    val cropHeight =
        (containerSize.height.toFloat() / totalScale)
            .coerceAtMost(imageHeight.toFloat())

    val centerX = imageWidth / 2f - safeOffset.x / totalScale
    val centerY = imageHeight / 2f - safeOffset.y / totalScale

    val left =
        (centerX - cropWidth / 2f)
            .coerceIn(0f, (imageWidth - cropWidth).coerceAtLeast(0f))
    val top =
        (centerY - cropHeight / 2f)
            .coerceIn(0f, (imageHeight - cropHeight).coerceAtLeast(0f))

    return SourceCropRect(
        left = left,
        top = top,
        right = left + cropWidth,
        bottom = top + cropHeight
    )
}
