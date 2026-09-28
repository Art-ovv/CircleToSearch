/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometric calculations for fitting an arbitrary photo onto a canvas whose aspect ratio
 * matches the device viewport.
 *
 * CircleToSearchScreen displays bitmaps using ContentScale.FillBounds across the full screen.
 * To preserve the entire photo without any stretching or cropping, the photo is aspect-fitted
 * onto a bounded viewport-proportioned canvas with neutral letterboxing. Because the canvas
 * shares the exact aspect ratio of the viewport, the view-to-bitmap coordinate transform is
 * uniform (scaleX == scaleY), ensuring all touch gestures, OCR boxes, and crop selections
 * align perfectly with the visible photo.
 */
data class CameraPhotoGeometry(
    val targetCanvasWidth: Int,
    val targetCanvasHeight: Int,
    val drawLeft: Int,
    val drawTop: Int,
    val drawWidth: Int,
    val drawHeight: Int,
    val scale: Float,
) {
    companion object {
        const val DEFAULT_MAX_DIMENSION = 2560
        const val FALLBACK_VIEWPORT_WIDTH = 1080
        const val FALLBACK_VIEWPORT_HEIGHT = 2400

        fun compute(
            photoWidth: Int,
            photoHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int,
            maxTargetDimension: Int = DEFAULT_MAX_DIMENSION,
        ): CameraPhotoGeometry {
            val safePhotoW = photoWidth.coerceAtLeast(1)
            val safePhotoH = photoHeight.coerceAtLeast(1)
            val safeViewportW = if (viewportWidth > 0) viewportWidth else FALLBACK_VIEWPORT_WIDTH
            val safeViewportH = if (viewportHeight > 0) viewportHeight else FALLBACK_VIEWPORT_HEIGHT
            val safeMaxDim = maxTargetDimension.coerceAtLeast(100)

            // Step 1: Compute target canvas dimensions matching the viewport aspect ratio,
            // bounded by safeMaxDim.
            val maxViewportDim = max(safeViewportW, safeViewportH)
            val canvasScale = if (maxViewportDim > safeMaxDim) {
                safeMaxDim.toDouble() / maxViewportDim.toDouble()
            } else {
                1.0
            }
            val canvasW = (safeViewportW * canvasScale).roundToInt().coerceAtLeast(1)
            val canvasH = (safeViewportH * canvasScale).roundToInt().coerceAtLeast(1)

            // Step 2: Aspect-fit the photo into the target canvas.
            val fitScale = min(
                canvasW.toDouble() / safePhotoW.toDouble(),
                canvasH.toDouble() / safePhotoH.toDouble(),
            )
            val fittedW = (safePhotoW * fitScale).roundToInt().coerceIn(1, canvasW)
            val fittedH = (safePhotoH * fitScale).roundToInt().coerceIn(1, canvasH)
            val left = (canvasW - fittedW) / 2
            val top = (canvasH - fittedH) / 2

            return CameraPhotoGeometry(
                targetCanvasWidth = canvasW,
                targetCanvasHeight = canvasH,
                drawLeft = left,
                drawTop = top,
                drawWidth = fittedW,
                drawHeight = fittedH,
                scale = fitScale.toFloat(),
            )
        }
    }
}
