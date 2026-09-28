/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import android.graphics.Rect
import com.akslabs.circletosearch.ui.mapViewRectToBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun preservesPhotoAspectRatioAndLetterboxesLandscapeOnPortrait() {
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

        // Photo fits completely inside canvas without cropping
        assertTrue(geom.drawLeft >= 0)
        assertTrue(geom.drawTop >= 0)
        assertTrue(geom.drawLeft + geom.drawWidth <= geom.targetCanvasWidth)
        assertTrue(geom.drawTop + geom.drawHeight <= geom.targetCanvasHeight)

        // Landscape photo on portrait viewport spans full canvas width and letterboxes top and bottom symmetrically
        assertEquals(0, geom.drawLeft)
        assertEquals(geom.targetCanvasWidth, geom.drawWidth)
        assertTrue(geom.drawTop > 0)
        assertEquals((geom.targetCanvasHeight - geom.drawHeight) / 2, geom.drawTop)
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

        val photoRatio = photoW.toDouble() / photoH.toDouble()
        val drawnRatio = geom.drawWidth.toDouble() / geom.drawHeight.toDouble()
        assertEquals(photoRatio, drawnRatio, 0.01)

        // Photo fits completely inside canvas without cropping
        assertTrue(geom.drawLeft >= 0)
        assertTrue(geom.drawTop >= 0)
        assertTrue(geom.drawLeft + geom.drawWidth <= geom.targetCanvasWidth)
        assertTrue(geom.drawTop + geom.drawHeight <= geom.targetCanvasHeight)

        // Portrait photo on landscape viewport spans full canvas height and pillarboxes left and right symmetrically
        assertEquals(0, geom.drawTop)
        assertEquals(geom.targetCanvasHeight, geom.drawHeight)
        assertTrue(geom.drawLeft > 0)
        assertEquals((geom.targetCanvasWidth - geom.drawWidth) / 2, geom.drawLeft)
    }

    @Test
    fun boundsLargeViewportDimensions() {
        val viewportW = 2000
        val viewportH = 5000
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
        val viewportRatio = viewportW.toDouble() / viewportH.toDouble()
        val canvasRatio = geom.targetCanvasWidth.toDouble() / geom.targetCanvasHeight.toDouble()
        assertEquals(viewportRatio, canvasRatio, 0.001)
    }

    @Test
    fun viewportResizeAdaptsGeometryWithoutCropping() {
        // CameraSearchActivity waits for positive measured size and adapts when window rotates/resizes.
        val photoW = 3600
        val photoH = 2400 // 3:2 landscape photo

        val portraitW = 1080
        val portraitH = 2400
        val portraitGeom = CameraPhotoGeometry.compute(
            photoWidth = photoW,
            photoHeight = photoH,
            viewportWidth = portraitW,
            viewportHeight = portraitH,
        )

        val landscapeW = 2400
        val landscapeH = 1080
        val landscapeGeom = CameraPhotoGeometry.compute(
            photoWidth = photoW,
            photoHeight = photoH,
            viewportWidth = landscapeW,
            viewportHeight = landscapeH,
        )

        val photoRatio = photoW.toDouble() / photoH.toDouble()

        // In portrait: fits width, letterboxed vertically
        assertEquals(0, portraitGeom.drawLeft)
        assertEquals(portraitGeom.targetCanvasWidth, portraitGeom.drawWidth)
        assertTrue(portraitGeom.drawTop > 0)
        assertEquals(photoRatio, portraitGeom.drawWidth.toDouble() / portraitGeom.drawHeight.toDouble(), 0.01)
        assertTrue(portraitGeom.drawTop + portraitGeom.drawHeight <= portraitGeom.targetCanvasHeight)

        // In landscape: 3:2 photo in ~2.2:1 viewport fits height, pillarboxed horizontally
        assertEquals(0, landscapeGeom.drawTop)
        assertEquals(landscapeGeom.targetCanvasHeight, landscapeGeom.drawHeight)
        assertTrue(landscapeGeom.drawLeft > 0)
        assertEquals(photoRatio, landscapeGeom.drawWidth.toDouble() / landscapeGeom.drawHeight.toDouble(), 0.01)
        assertTrue(landscapeGeom.drawLeft + landscapeGeom.drawWidth <= landscapeGeom.targetCanvasWidth)
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

        assertEquals(geom.drawWidth, geom.drawHeight)
        assertEquals(0, geom.drawLeft)
        assertEquals(geom.targetCanvasWidth, geom.drawWidth)
        assertEquals((geom.targetCanvasHeight - geom.drawHeight) / 2, geom.drawTop)
    }

    @Test
    fun mapsViewSelectionIntoLetterboxedPhotoCanvas() {
        val geom = CameraPhotoGeometry.compute(
            photoWidth = 800,
            photoHeight = 400,
            viewportWidth = 600,
            viewportHeight = 1000,
        )

        assertEquals(600, geom.targetCanvasWidth)
        assertEquals(1000, geom.targetCanvasHeight)
        assertEquals(0, geom.drawLeft)
        assertEquals(350, geom.drawTop)
        assertEquals(600, geom.drawWidth)
        assertEquals(300, geom.drawHeight)

        val mappedPhotoSelection = mapViewRectToBitmap(
            viewRect = rect(75, 200, 225, 300),
            viewportWidth = 300,
            viewportHeight = 500,
            bitmapWidth = geom.targetCanvasWidth,
            bitmapHeight = geom.targetCanvasHeight,
        )
        assertEquals(150, mappedPhotoSelection.left)
        assertEquals(400, mappedPhotoSelection.top)
        assertEquals(450, mappedPhotoSelection.right)
        assertEquals(600, mappedPhotoSelection.bottom)
        assertTrue(mappedPhotoSelection.left >= geom.drawLeft)
        assertTrue(mappedPhotoSelection.top >= geom.drawTop)
        assertTrue(mappedPhotoSelection.right <= geom.drawLeft + geom.drawWidth)
        assertTrue(mappedPhotoSelection.bottom <= geom.drawTop + geom.drawHeight)

        val mappedLetterboxSelection = mapViewRectToBitmap(
            viewRect = rect(75, 25, 225, 75),
            viewportWidth = 300,
            viewportHeight = 500,
            bitmapWidth = geom.targetCanvasWidth,
            bitmapHeight = geom.targetCanvasHeight,
        )
        assertEquals(150, mappedLetterboxSelection.bottom)
        assertTrue(mappedLetterboxSelection.bottom < geom.drawTop)
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
