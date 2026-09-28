/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object CameraPhotoProcessor {

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun processPhoto(
        photoFile: File,
        viewportWidth: Int,
        viewportHeight: Int,
        maxDimension: Int = CameraPhotoGeometry.DEFAULT_MAX_DIMENSION,
    ): Bitmap {
        var undeliveredTarget: Bitmap? = null
        return try {
            val result = withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()

                val existsAndNotEmpty = photoFile.exists() && photoFile.length() > 0L
                if (!existsAndNotEmpty) {
                    throw FileNotFoundException("Photo file does not exist or is empty")
                }

                val source = ImageDecoder.createSource(photoFile)
                var decoded: Bitmap? = null

                try {
                    val safeViewportW = if (viewportWidth > 0) viewportWidth else CameraPhotoGeometry.FALLBACK_VIEWPORT_WIDTH
                    val safeViewportH = if (viewportHeight > 0) viewportHeight else CameraPhotoGeometry.FALLBACK_VIEWPORT_HEIGHT
                    val maxBound = maxOf(maxOf(safeViewportW, safeViewportH), 2048).coerceAtMost(maxDimension)

                    decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val origW = info.size.width
                        val origH = info.size.height
                        if (origW > maxBound || origH > maxBound) {
                            val sample = maxOf(origW.toFloat() / maxBound, origH.toFloat() / maxBound)
                            val targetW = (origW / sample).toInt().coerceAtLeast(1)
                            val targetH = (origH / sample).toInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetW, targetH)
                        }
                    }

                    currentCoroutineContext().ensureActive()

                    val geometry = CameraPhotoGeometry.compute(
                        photoWidth = decoded.width,
                        photoHeight = decoded.height,
                        viewportWidth = safeViewportW,
                        viewportHeight = safeViewportH,
                        maxTargetDimension = maxBound,
                    )

                    val targetBitmap = Bitmap.createBitmap(
                        geometry.targetCanvasWidth,
                        geometry.targetCanvasHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    undeliveredTarget = targetBitmap

                    val canvas = Canvas(targetBitmap)
                    canvas.drawColor(Color.BLACK)

                    val destRect = Rect(
                        geometry.drawLeft,
                        geometry.drawTop,
                        geometry.drawLeft + geometry.drawWidth,
                        geometry.drawTop + geometry.drawHeight,
                    )
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    canvas.drawBitmap(decoded, null, destRect, paint)

                    currentCoroutineContext().ensureActive()
                    targetBitmap
                } catch (e: Exception) {
                    if (e !is IOException && e !is ImageDecoder.DecodeException) {
                        throw e
                    }
                    throw IOException("Failed to decode photo", e)
                } finally {
                    decoded?.takeUnless { it.isRecycled }?.recycle()
                }
            }
            undeliveredTarget = null
            result
        } finally {
            undeliveredTarget?.takeUnless { it.isRecycled }?.recycle()
        }
    }
}
