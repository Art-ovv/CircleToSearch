/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CameraPhotoProcessorInstrumentedTest {

    private val trackedBitmaps = mutableListOf<Bitmap>()
    private val trackedFiles = mutableListOf<File>()
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = File(context.cacheDir, "test_camera_processor").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        trackedBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        trackedBitmaps.clear()

        trackedFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        trackedFiles.clear()
        cacheDir.deleteRecursively()
    }

    @Test
    fun processPhotoDecodesSyntheticJpegWithExifOrientation() = runBlocking {
        // A 90-degree rotation moves the red left half above the blue right half.
        val pixels = IntArray(200 * 100) { index ->
            if (index % 200 < 100) Color.RED else Color.BLUE
        }
        val srcBitmap = Bitmap.createBitmap(pixels, 200, 100, Bitmap.Config.ARGB_8888)
            .also(trackedBitmaps::add)

        val photoFile = File(cacheDir, "synthetic_oriented.jpg").also(trackedFiles::add)
        FileOutputStream(photoFile).use { out ->
            srcBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        // Apply EXIF ROTATE_90 tag: oriented image becomes 100 width x 200 height (portrait)
        val exif = ExifInterface(photoFile.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()

        val viewportW = 1080
        val viewportH = 2400
        val processedBitmap = CameraPhotoProcessor.processPhoto(
            photoFile = photoFile,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
        ).also(trackedBitmaps::add)

        assertNotNull(processedBitmap)
        assertFalse("Processed bitmap must not be recycled", processedBitmap.isRecycled)
        assertEquals("Target canvas width must match viewport width", viewportW, processedBitmap.width)
        assertEquals("Target canvas height must match viewport height", viewportH, processedBitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, processedBitmap.config)

        val topPhotoPixel = processedBitmap.getPixel(viewportW / 2, 400)
        val bottomPhotoPixel = processedBitmap.getPixel(viewportW / 2, 2000)
        assertMostlyRed(topPhotoPixel)
        assertMostlyBlue(bottomPhotoPixel)
        assertEquals(Color.BLACK, processedBitmap.getPixel(viewportW / 2, 0))
        assertEquals(Color.BLACK, processedBitmap.getPixel(viewportW / 2, viewportH - 1))
    }

    @Test
    fun processPhotoDownscalesOversizedImageToBounds() = runBlocking {
        val srcBitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).also(trackedBitmaps::add)
        srcBitmap.eraseColor(Color.BLUE)

        val photoFile = File(cacheDir, "synthetic_oversized.jpg").also(trackedFiles::add)
        FileOutputStream(photoFile).use { out ->
            srcBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        val maxBound = 400
        var decodedSize: Pair<Int, Int>? = null
        val processedBitmap = CameraPhotoProcessor.processPhoto(
            photoFile = photoFile,
            viewportWidth = 1080,
            viewportHeight = 2400,
            maxDimension = maxBound,
            onDecodedSize = { width, height -> decodedSize = width to height },
        ).also(trackedBitmaps::add)

        assertNotNull(processedBitmap)
        assertFalse(processedBitmap.isRecycled)
        val (decodedWidth, decodedHeight) = requireNotNull(decodedSize)
        assertTrue("Decoded width must be bounded before drawing", decodedWidth <= maxBound)
        assertTrue("Decoded height must be bounded before drawing", decodedHeight <= maxBound)
        assertTrue("Source must actually be downscaled", decodedWidth < srcBitmap.width)
        assertTrue("Canvas width must be bounded by maxDimension", processedBitmap.width <= maxBound)
        assertTrue("Canvas height must be bounded by maxDimension", processedBitmap.height <= maxBound)
    }

    @Test
    fun processPhotoThrowsOnMissingOrEmptyOrCorruptedFiles() = runBlocking {
        val nonExistent = File(cacheDir, "non_existent.jpg")
        assertThrows(FileNotFoundException::class.java) {
            runBlocking {
                CameraPhotoProcessor.processPhoto(nonExistent, 1080, 2400)
            }
        }

        val emptyFile = File(cacheDir, "empty.jpg").also(trackedFiles::add)
        emptyFile.createNewFile()
        assertThrows(FileNotFoundException::class.java) {
            runBlocking {
                CameraPhotoProcessor.processPhoto(emptyFile, 1080, 2400)
            }
        }

        val corruptedFile = File(cacheDir, "corrupted.jpg").also(trackedFiles::add)
        corruptedFile.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        assertThrows(IOException::class.java) {
            runBlocking {
                CameraPhotoProcessor.processPhoto(corruptedFile, 1080, 2400)
            }
        }
        Unit
    }

    private fun assertMostlyRed(pixel: Int) {
        assertTrue("Expected a red pixel, got $pixel", Color.red(pixel) >= 220)
        assertTrue("Expected little green, got $pixel", Color.green(pixel) <= 40)
        assertTrue("Expected little blue, got $pixel", Color.blue(pixel) <= 40)
    }

    private fun assertMostlyBlue(pixel: Int) {
        assertTrue("Expected a blue pixel, got $pixel", Color.blue(pixel) >= 220)
        assertTrue("Expected little red, got $pixel", Color.red(pixel) <= 40)
        assertTrue("Expected little green, got $pixel", Color.green(pixel) <= 40)
    }
}
