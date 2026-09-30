package com.paddle.ocr.preprocess

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Packs normalized, interleaved rows into one per-inference buffer borrowed by ONNX. */
internal fun packNchwTensor(
    height: Int,
    width: Int,
    validWidths: IntArray,
    cancellationCheck: () -> Unit = {},
    readRow: (batch: Int, row: Int, destination: FloatArray) -> Unit,
): FloatBuffer {
    require(height > 0 && width > 0 && validWidths.isNotEmpty())
    require(validWidths.all { it in 1..width })
    val channelSize = Math.multiplyExact(height, width)
    val elementCount = Math.multiplyExact(Math.multiplyExact(validWidths.size, 3), channelSize)
    require(elementCount <= (Int.MAX_VALUE - 32) / Float.SIZE_BYTES) {
        "Input tensor exceeds the direct buffer size limit"
    }
    cancellationCheck()
    // allocateDirect zeroes the padding. Keep this buffer local to the inference; no global pool
    // retains a screen-sized allocation after the OCR session has been released.
    val tensor = ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    val interleavedRow = FloatArray(width * 3)
    val channelRow = FloatArray(width)
    for (batch in validWidths.indices) {
        val validWidth = validWidths[batch]
        for (row in 0 until height) {
            if (row % 16 == 0) cancellationCheck()
            readRow(batch, row, interleavedRow)
            for (channel in 0 until 3) {
                for (column in 0 until validWidth) {
                    channelRow[column] = interleavedRow[column * 3 + channel]
                }
                tensor.position((batch * 3 + channel) * channelSize + row * width)
                tensor.put(channelRow, 0, validWidth)
            }
        }
    }
    cancellationCheck()
    // flip() would truncate the last row's padding when the last crop is narrower than width.
    tensor.rewind()
    return tensor
}
