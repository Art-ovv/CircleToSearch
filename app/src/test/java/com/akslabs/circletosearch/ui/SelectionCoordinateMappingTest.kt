package com.akslabs.circletosearch.ui

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionCoordinateMappingTest {
    @Test
    fun mapsViewSelectionIntoBitmapCoordinates() {
        val result = mapViewRectToBitmap(
            viewRect = rect(100, 200, 300, 600),
            viewportWidth = 540,
            viewportHeight = 1200,
            bitmapWidth = 1080,
            bitmapHeight = 2400,
        )

        assertRect(result, 200, 400, 600, 1200)
    }

    @Test
    fun clampsAndRoundsOutwardSoEdgePixelsAreNotLost() {
        val result = mapViewRectToBitmap(
            viewRect = rect(1, -2, 2, 5),
            viewportWidth = 3,
            viewportHeight = 3,
            bitmapWidth = 10,
            bitmapHeight = 10,
        )

        assertRect(result, 3, 0, 7, 10)
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private fun assertRect(rect: Rect, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(left, rect.left)
        assertEquals(top, rect.top)
        assertEquals(right, rect.right)
        assertEquals(bottom, rect.bottom)
    }
}
