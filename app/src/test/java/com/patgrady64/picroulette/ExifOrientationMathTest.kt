package com.patgrady64.picroulette

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationMathTest {
    @Test
    fun rotate90_swapsOrientedDimensions() {
        assertEquals(
            PixelDimensions(300, 400),
            orientedDimensions(400, 300, 6)
        )
    }

    @Test
    fun rotate90_mapsVisibleCropBackToEncodedPixels() {
        val raw = mapOrientedCropToRaw(
            crop = SourceCropRect(
                left = 0f,
                top = 0f,
                right = 100f,
                bottom = 200f
            ),
            rawWidth = 400,
            rawHeight = 300,
            exifOrientation = 6
        )

        assertEquals(PixelRect(0, 200, 200, 300), raw)
    }

    @Test
    fun normalOrientation_keepsCropCoordinates() {
        val raw = mapOrientedCropToRaw(
            crop = SourceCropRect(10f, 20f, 110f, 120f),
            rawWidth = 400,
            rawHeight = 300,
            exifOrientation = 1
        )

        assertEquals(PixelRect(10, 20, 110, 120), raw)
    }
}
