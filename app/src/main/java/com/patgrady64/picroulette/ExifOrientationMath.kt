package com.patgrady64.picroulette

import kotlin.math.ceil
import kotlin.math.floor

data class PixelDimensions(
    val width: Int,
    val height: Int
)

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

fun orientedDimensions(
    rawWidth: Int,
    rawHeight: Int,
    exifOrientation: Int
): PixelDimensions =
    if (exifOrientation in setOf(5, 6, 7, 8)) {
        PixelDimensions(rawHeight, rawWidth)
    } else {
        PixelDimensions(rawWidth, rawHeight)
    }

/**
 * Converts a crop in visually-oriented coordinates back into coordinates of
 * the encoded image. EXIF orientation values are the standard 1..8 values.
 */
fun mapOrientedCropToRaw(
    crop: SourceCropRect,
    rawWidth: Int,
    rawHeight: Int,
    exifOrientation: Int
): PixelRect {
    fun inversePoint(x: Float, y: Float): Pair<Float, Float> =
        when (exifOrientation) {
            2 -> (rawWidth - x) to y
            3 -> (rawWidth - x) to (rawHeight - y)
            4 -> x to (rawHeight - y)
            5 -> y to x
            6 -> y to (rawHeight - x)
            7 -> (rawWidth - y) to (rawHeight - x)
            8 -> (rawWidth - y) to x
            else -> x to y
        }

    val points = listOf(
        inversePoint(crop.left, crop.top),
        inversePoint(crop.right, crop.top),
        inversePoint(crop.left, crop.bottom),
        inversePoint(crop.right, crop.bottom)
    )

    val minX = points.minOf { it.first }
    val maxX = points.maxOf { it.first }
    val minY = points.minOf { it.second }
    val maxY = points.maxOf { it.second }

    val left = floor(minX).toInt().coerceIn(0, rawWidth - 1)
    val top = floor(minY).toInt().coerceIn(0, rawHeight - 1)
    val right = ceil(maxX).toInt().coerceIn(left + 1, rawWidth)
    val bottom = ceil(maxY).toInt().coerceIn(top + 1, rawHeight)

    return PixelRect(left, top, right, bottom)
}
