package com.akslabs.circletosearch.ocr

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TessDataPreparerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun publishesCompleteModelsAndRemovesTemporaryFiles() {
        val root = temporaryFolder.newFolder("files")
        val assets = mapOf("eng" to byteArrayOf(1, 2, 3), "rus" to byteArrayOf(4, 5, 6))
        val preparer = preparer(root, assets)

        assertEquals(root.absolutePath, preparer.prepare())
        assets.forEach { (language, bytes) ->
            assertArrayEquals(bytes, root.resolve("tessdata/$language.traineddata").readBytes())
        }
        assertFalse(root.resolve("tessdata/.eng.traineddata.tmp").exists())
        assertFalse(root.resolve("tessdata/.rus.traineddata.tmp").exists())
    }

    @Test
    fun zeroLengthDestinationAndStaleTemporaryFileAreRecovered() {
        val root = temporaryFolder.newFolder("recovery")
        val tessDir = root.resolve("tessdata").apply { mkdirs() }
        tessDir.resolve("eng.traineddata").writeBytes(byteArrayOf())
        tessDir.resolve(".eng.traineddata.tmp").writeBytes(byteArrayOf(9))

        preparer(root, mapOf("eng" to byteArrayOf(7, 8))).prepare()

        assertArrayEquals(byteArrayOf(7, 8), tessDir.resolve("eng.traineddata").readBytes())
        assertFalse(tessDir.resolve(".eng.traineddata.tmp").exists())
    }

    @Test
    fun nonzeroLegacyModelWithoutVersionMarkerIsReplaced() {
        val root = temporaryFolder.newFolder("legacy")
        val tessDir = root.resolve("tessdata").apply { mkdirs() }
        tessDir.resolve("eng.traineddata").writeBytes(byteArrayOf(99))

        preparer(root, mapOf("eng" to byteArrayOf(1, 2, 3, 4))).prepare()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), tessDir.resolve("eng.traineddata").readBytes())
        assertEquals("1", tessDir.resolve(".bundled-models.version").readText())
    }

    @Test
    fun failedPreparationIsRetried() {
        val root = temporaryFolder.newFolder("retry")
        val russianAttempts = AtomicInteger()
        val preparer = TessDataPreparer(root, listOf("eng", "rus")) { path ->
            if (path.endsWith("rus.traineddata") && russianAttempts.getAndIncrement() == 0) {
                throw IOException("first attempt fails")
            }
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        assertThrows(IOException::class.java) { preparer.prepare() }
        preparer.prepare()

        assertEquals(2, russianAttempts.get())
        assertTrue(root.resolve("tessdata/rus.traineddata").length() > 0L)
    }

    @Test
    fun midstreamFailureLeavesNoPartialDestinationAndRetrySucceeds() {
        val root = temporaryFolder.newFolder("midstream")
        val attempts = AtomicInteger()
        val expected = byteArrayOf(1, 2, 3, 4)
        val preparer = TessDataPreparer(root, listOf("eng")) {
            if (attempts.getAndIncrement() == 0) throwingStream() else ByteArrayInputStream(expected)
        }

        assertThrows(IOException::class.java) { preparer.prepare() }
        assertFalse(root.resolve("tessdata/eng.traineddata").exists())
        assertFalse(root.resolve("tessdata/.eng.traineddata.tmp").exists())

        preparer.prepare()
        assertArrayEquals(expected, root.resolve("tessdata/eng.traineddata").readBytes())
    }

    @Test
    fun concurrentCallersCopyEachAssetOnce() {
        val root = temporaryFolder.newFolder("concurrent")
        val opens = AtomicInteger()
        val preparer = TessDataPreparer(root, listOf("eng", "rus")) {
            opens.incrementAndGet()
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            executor.invokeAll((0 until 32).map { Callable { preparer.prepare() } })
                .forEach { it.get() }
            assertEquals(2, opens.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun preparer(root: java.io.File, assets: Map<String, ByteArray>) =
        TessDataPreparer(root, assets.keys.toList()) { path ->
            ByteArrayInputStream(checkNotNull(assets[path.substringAfterLast('/').substringBefore('.')]))
        }

    private fun throwingStream() = object : InputStream() {
        private var index = 0

        override fun read(): Int = when (index++) {
            0 -> 1
            1 -> 2
            else -> throw IOException("copy failed midway")
        }
    }
}
