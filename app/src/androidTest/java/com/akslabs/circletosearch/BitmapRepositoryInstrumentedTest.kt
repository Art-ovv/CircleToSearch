package com.akslabs.circletosearch

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akslabs.circletosearch.data.BitmapRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapRepositoryInstrumentedTest {

    private val ownedBitmaps = mutableListOf<Bitmap>()

    @Before
    fun setUp() {
        BitmapRepository.clear()
    }

    @After
    fun tearDown() {
        BitmapRepository.clear()
        ownedBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        ownedBitmaps.clear()
    }

    @Test
    fun staleCaptureIdCannotReadNewerBitmap() {
        val first = bitmap()
        val firstId = BitmapRepository.setScreenshot(first)
        val second = bitmap()
        val secondId = BitmapRepository.setScreenshot(second)

        assertNull(BitmapRepository.getScreenshot(firstId))
        assertSame(second, BitmapRepository.getScreenshot(secondId))
        assertFalse(BitmapRepository.clearIfSame(firstId, first))
        assertSame(second, BitmapRepository.getScreenshot(secondId))
    }

    @Test
    fun translationReplacementPreservesCaptureIdentity() {
        val source = bitmap()
        val captureId = BitmapRepository.setScreenshot(source)
        val translated = bitmap()

        assertTrue(BitmapRepository.compareAndSetScreenshot(source, translated))
        assertEquals(captureId, BitmapRepository.getSnapshot()?.captureId)
        assertSame(translated, BitmapRepository.getScreenshot(captureId))
    }

    private fun bitmap(): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(ownedBitmaps::add)
}
