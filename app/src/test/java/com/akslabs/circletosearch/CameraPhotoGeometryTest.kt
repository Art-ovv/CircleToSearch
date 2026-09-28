/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraPhotoGeometryTest {

    @Test
    fun preservesViewportAspectRatio() {
        val viewportW = 1080
        val viewportH = 2400
        val photoW = 3000
        val photoH = 4000

        val geom = CameraPhotoGeometry.compute(
            photoWidth = photoW,
            photoHeight = photoH,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
        )

        val viewportRatio = viewportW.toDouble() / viewportH.toDouble()
        val canvasRatio = geom.targetCanvasWidth.toDouble() / geom.targetCanvasHeight.toDouble()
        assertEquals(viewportRatio, canvasRatio, 0.001)
    }

    @Test
    fun preservesPhotoAspectRatioWithoutStretching() {
        val viewportW = 1080
        val viewportH = 2400
        val photoW = 4000
        val photoH = 3000 // Landscape photo

        val geom = CameraPhotoGeometry.compute(
            photoWidth = photoW,
            photoHeight = photoH,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
        )

        val photoRatio = photoW.toDouble() / photoH.toDouble()
        val drawnRatio = geom.drawWidth.toDouble() / geom.drawHeight.toDouble()
        assertEquals(photoRatio, drawnRatio, 0.01)

        // Photo fits completely inside canvas
        assertTrue(geom.drawLeft >= 0)
        assertTrue(geom.drawTop >= 0)
        assertTrue(geom.drawLeft + geom.drawWidth <= geom.targetCanvasWidth)
        assertTrue(geom.drawTop + geom.drawHeight <= geom.targetCanvasHeight)

        // Landscape on portrait viewport letterboxes top and bottom
        assertEquals(0, geom.drawLeft)
        assertEquals(geom.targetCanvasWidth, geom.drawWidth)
        assertTrue(geom.drawTop > 0)
        val expectedHeight = (1080 * 3000.0 / 4000.0).toInt()
        assertEquals(expectedHeight, geom.drawHeight)
        val expectedTop = (2400 - expectedHeight) / 2
        assertEquals(expectedTop, geom.drawTop)
    }

    @Test
    fun pillarboxesPortraitPhotoOnLandscapeViewport() {
        val viewportW = 2400
        val viewportH = 1080
        val photoW = 3000
        val photoH = 4000 // Portrait photo

        val geom = CameraPhotoGeometry.compute(
            photoWidth = photoW,
            photoHeight = photoH,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
        )

        // Photo fits vertically, pillarboxed left and right
        assertEquals(0, geom.drawTop)
        assertEquals(geom.targetCanvasHeight, geom.drawHeight)
        assertTrue(geom.drawLeft > 0)

        val expectedWidth = (1080 * 3000.0 / 4000.0).toInt()
        assertEquals(expectedWidth, geom.drawWidth)
        val expectedLeft = (2400 - expectedWidth) / 2
        assertEquals(expectedLeft, geom.drawLeft)
    }

    @Test
    fun boundsLargeViewportDimensions() {
        val viewportW = 2000
        val viewportH = 5000 // Very large viewport
        val maxDim = 2500

        val geom = CameraPhotoGeometry.compute(
            photoWidth = 1000,
            photoHeight = 1000,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            maxTargetDimension = maxDim,
        )

        assertTrue(geom.targetCanvasWidth <= maxDim)
        assertTrue(geom.targetCanvasHeight <= maxDim)
        assertEquals(maxDim, geom.targetCanvasHeight)
        val expectedW = (2000 * (2500.0 / 5000.0)).toInt()
        assertEquals(expectedW, geom.targetCanvasWidth)
    }

    @Test
    fun handlesZeroOrNegativeViewportWithFallback() {
        val geom = CameraPhotoGeometry.compute(
            photoWidth = 800,
            photoHeight = 600,
            viewportWidth = 0,
            viewportHeight = -1,
        )

        assertEquals(CameraPhotoGeometry.FALLBACK_VIEWPORT_WIDTH, geom.targetCanvasWidth)
        assertEquals(CameraPhotoGeometry.FALLBACK_VIEWPORT_HEIGHT, geom.targetCanvasHeight)
        assertTrue(geom.drawWidth > 0)
        assertTrue(geom.drawHeight > 0)
    }

    @Test
    fun handlesSquarePhotoOnPortraitViewport() {
        val viewportW = 1080
        val viewportH = 1920
        val photoW = 2000
        val photoH = 2000

        val geom = CameraPhotoGeometry.compute(
            photoWidth = photoW,
            photoHeight = photoH,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
        )

        assertEquals(1080, geom.drawWidth)
        assertEquals(1080, geom.drawHeight)
        assertEquals(0, geom.drawLeft)
        assertEquals((1920 - 1080) / 2, geom.drawTop)
    }
}
