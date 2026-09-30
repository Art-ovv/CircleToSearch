package com.paddle.ocr.preprocess

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteOrder

class NchwTensorBufferTest {
    @Test
    fun packsDetectorRowsIntoChannelPlanesWithoutChangingNormalizedValues() {
        val rows = arrayOf(
            floatArrayOf(-1f, 0.5f, 2f, 3f, -4f, 5f),
            floatArrayOf(6f, 7f, -8f, 9f, 10f, 11f),
        )
        val tensor = packNchwTensor(2, 2, intArrayOf(2)) { _, row, destination ->
            rows[row].copyInto(destination)
        }
        assertTrue(tensor.isDirect)
        assertFalse(tensor.hasArray())
        assertEquals(ByteOrder.nativeOrder(), tensor.order())
        assertEquals(0, tensor.position())
        assertEquals(12, tensor.remaining())
        val actual = FloatArray(tensor.remaining())
        tensor.get(actual)
        assertArrayEquals(
            floatArrayOf(-1f, 3f, 6f, 9f, 0.5f, -4f, 7f, 10f, 2f, 5f, -8f, 11f),
            actual,
            0f,
        )
    }

    @Test
    fun padsEachRecognitionRowAndIncludesPaddingAfterLastNarrowCrop() {
        val rows = arrayOf(
            arrayOf(
                floatArrayOf(1f, 10f, 100f, 2f, 20f, 200f, 3f, 30f, 300f),
                floatArrayOf(4f, 40f, 400f, 5f, 50f, 500f, 6f, 60f, 600f),
            ),
            arrayOf(floatArrayOf(-1f, -10f, -100f), floatArrayOf(-2f, -20f, -200f)),
        )
        val tensor = packNchwTensor(2, 3, intArrayOf(3, 1)) { batch, row, destination ->
            // Leave the remainder untouched, as Mat.get does for a narrower crop.
            rows[batch][row].copyInto(destination)
        }
        assertEquals(36, tensor.limit())
        assertEquals(36, tensor.remaining())
        val actual = FloatArray(tensor.remaining())
        tensor.get(actual)
        assertArrayEquals(
            floatArrayOf(
                1f, 2f, 3f, 4f, 5f, 6f,
                10f, 20f, 30f, 40f, 50f, 60f,
                100f, 200f, 300f, 400f, 500f, 600f,
                -1f, 0f, 0f, -2f, 0f, 0f,
                -10f, 0f, 0f, -20f, 0f, 0f,
                -100f, 0f, 0f, -200f, 0f, 0f,
            ),
            actual,
            0f,
        )
    }

    @Test
    fun matchesPreviousArrayPackingForMixedWidthBatch() {
        val height = 33
        val width = 47
        val widths = intArrayOf(width, 11, 1)
        val channelSize = height * width
        val reference = FloatArray(widths.size * 3 * channelSize)
        val tensor = packNchwTensor(height, width, widths) { batch, row, destination ->
            for (column in 0 until widths[batch]) {
                for (channel in 0 until 3) {
                    val value = (batch * 1000 + row * 17 + column * 3 + channel - 300) / 127.5f
                    destination[column * 3 + channel] = value
                    reference[(batch * 3 + channel) * channelSize + row * width + column] = value
                }
            }
        }
        val actual = FloatArray(tensor.remaining())
        tensor.get(actual)
        assertArrayEquals(reference, actual, 0f)
    }

    @Test
    fun packingPreservesFloatBits() {
        val bits = intArrayOf(0x80000000.toInt(), 0x7fc01234, 0x3f800001)
        val tensor = packNchwTensor(1, 1, intArrayOf(1)) { _, _, destination ->
            bits.forEachIndexed { index, value -> destination[index] = Float.fromBits(value) }
        }
        bits.forEach { assertEquals(it, tensor.get().toRawBits()) }
    }

    @Test
    fun cancellationBeforePackingDoesNotReadAnyRows() {
        var rowsRead = 0
        assertThrows(CancellationException::class.java) {
            packNchwTensor(2, 2, intArrayOf(2), { throw CancellationException() }) { _, _, _ ->
                rowsRead++
            }
        }
        assertEquals(0, rowsRead)
    }

    @Test
    fun cancellationDuringPackingStopsBeforeNextChunk() {
        var rowsRead = 0
        assertThrows(CancellationException::class.java) {
            packNchwTensor(48, 2, intArrayOf(2), {
                if (rowsRead >= 16) throw CancellationException()
            }) { _, _, destination ->
                rowsRead++
                destination.fill(1f)
            }
        }
        assertEquals(16, rowsRead)
    }

    @Test
    fun failedRowReadPropagatesWithoutAffectingNextTensor() {
        assertThrows(IllegalStateException::class.java) {
            packNchwTensor(1, 2, intArrayOf(2)) { _, _, _ -> error("Synthetic read failure") }
        }
        val tensor = packNchwTensor(1, 2, intArrayOf(1)) { _, _, destination ->
            destination.fill(7f)
        }
        val actual = FloatArray(tensor.remaining())
        tensor.get(actual)
        assertArrayEquals(floatArrayOf(7f, 0f, 7f, 0f, 7f, 0f), actual, 0f)
    }

    @Test
    fun rejectsEmptyInvalidOrOversizedShapesBeforeReadingRows() {
        val unusedReader: (Int, Int, FloatArray) -> Unit = { _, _, _ ->
            throw AssertionError("Invalid shape must fail before reading rows")
        }
        assertThrows(IllegalArgumentException::class.java) {
            packNchwTensor(1, 1, intArrayOf(), readRow = unusedReader)
        }
        assertThrows(IllegalArgumentException::class.java) {
            packNchwTensor(0, 1, intArrayOf(1), readRow = unusedReader)
        }
        assertThrows(IllegalArgumentException::class.java) {
            packNchwTensor(1, 1, intArrayOf(2), readRow = unusedReader)
        }
        assertThrows(IllegalArgumentException::class.java) {
            packNchwTensor(1, 200_000_000, intArrayOf(1), readRow = unusedReader)
        }
        assertThrows(ArithmeticException::class.java) {
            packNchwTensor(Int.MAX_VALUE, 2, intArrayOf(1), readRow = unusedReader)
        }
    }
}
