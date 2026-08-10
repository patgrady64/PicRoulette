package com.patgrady64.picroulette

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhotoViewportMathTest {
    @Test
    fun zoom_isClampedToProductionLimits() {
        assertEquals(1f, clampPhotoZoom(1f, 0.1f), 0.0001f)
        assertEquals(8f, clampPhotoZoom(7f, 2f), 0.0001f)
        assertEquals(2f, clampPhotoZoom(1f, 2f), 0.0001f)
    }

    @Test
    fun fillMode_usesExpectedBaseMultiplierAndPanBounds() {
        val geometry = calculatePhotoViewportGeometry(
            imageWidth = 400,
            imageHeight = 200,
            containerSize = IntSize(100, 100),
            userZoom = 1f,
            displayMode = PhotoDisplayMode.FILL
        )

        assertNotNull(geometry)
        geometry!!
        assertEquals(2f, geometry.displayModeMultiplier, 0.0001f)
        assertEquals(50f, geometry.maxPanX, 0.0001f)
        assertEquals(0f, geometry.maxPanY, 0.0001f)
    }

    @Test
    fun fitMode_doesNotPanIntoEmptySpaceAtOneX() {
        val clamped = clampPhotoOffset(
            proposedOffset = Offset(500f, 500f),
            imageWidth = 400,
            imageHeight = 200,
            containerSize = IntSize(100, 100),
            userZoom = 1f,
            displayMode = PhotoDisplayMode.FIT
        )

        assertEquals(Offset.Zero, clamped)
    }

    @Test
    fun sourceCrop_tracksTheClampedViewport() {
        val crop = calculateSourceCropRect(
            imageWidth = 400,
            imageHeight = 200,
            containerSize = IntSize(100, 100),
            userZoom = 2f,
            offset = Offset(50f, 0f),
            displayMode = PhotoDisplayMode.FIT
        )

        assertNotNull(crop)
        crop!!
        assertEquals(0f, crop.left, 0.01f)
        assertEquals(0f, crop.top, 0.01f)
        assertEquals(200f, crop.right, 0.01f)
        assertEquals(200f, crop.bottom, 0.01f)
    }
}
