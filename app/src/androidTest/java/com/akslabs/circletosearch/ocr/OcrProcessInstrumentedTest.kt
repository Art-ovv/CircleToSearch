package com.akslabs.circletosearch.ocr

import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic pixels only. Does not capture the display or change assistant/accessibility settings. */
@RunWith(AndroidJUnit4::class)
class OcrProcessInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun workerIsPrivateAndInDedicatedProcess() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, OcrWorkerService::class.java), 0)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:ocr", info.processName)
    }

    @Test fun rawPixelTransferPreservesScreenshotSizedInput() {
        val source = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(32, 64, 192))
            source.setPixel(7, 11, Color.RED)
            OcrSharedMemory.fromBitmap(source).use { transfer ->
                val copied = OcrSharedMemory.toBitmap(transfer.memory, transfer.width, transfer.height, transfer.rowBytes)
                try { assertTrue(source.sameAs(copied)) } finally { copied.recycle() }
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun coldWarmAndRestartedRecognitionStayOutsideMainProcess() {
        runBlocking {
            val client = OcrProcessClient(context)
            val bitmap = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("Test 123", 48f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 72f
                })
            }
            fun workerPid(): Int = context.getSystemService(ActivityManager::class.java)
                .runningAppProcesses.orEmpty().single { it.processName == "${context.packageName}:ocr" }.pid
            try {
                val coldStart = SystemClock.elapsedRealtime()
                val cold = client.recognize(OcrLanguageCatalog.BUNDLED_PACK_ID, bitmap)
                val coldMs = SystemClock.elapsedRealtime() - coldStart
                assertTrue(cold.results.isNotEmpty())
                val originalPid = workerPid()
                assertNotEquals(Process.myPid(), originalPid)
                val warmStart = SystemClock.elapsedRealtime()
                val warm = client.recognize(OcrLanguageCatalog.BUNDLED_PACK_ID, bitmap)
                val warmMs = SystemClock.elapsedRealtime() - warmStart
                assertEquals(originalPid, workerPid())
                assertEquals(cold.results.map { it.text }, warm.results.map { it.text })
                client.close()
                withTimeout(5_000L) {
                    while (context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
                            .any { it.processName == "${context.packageName}:ocr" }) {
                        delay(50L)
                    }
                }
                val restarted = client.recognize(OcrLanguageCatalog.BUNDLED_PACK_ID, bitmap)
                assertNotEquals(originalPid, workerPid())
                assertEquals(cold.results.map { it.text }, restarted.results.map { it.text })
                val mainMappings = File("/proc/self/maps").readText()
                assertFalse(mainMappings.contains("libonnxruntime"))
                assertFalse(mainMappings.contains("libopencv_java"))
                android.util.Log.i("OcrProcessTest", "Synthetic OCR: coldMs=$coldMs warmMs=$warmMs")
            } finally {
                client.close()
                bitmap.recycle()
            }
        }
    }
}
