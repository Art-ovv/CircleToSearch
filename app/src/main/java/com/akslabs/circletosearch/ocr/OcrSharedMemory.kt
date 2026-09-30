package com.akslabs.circletosearch.ocr

import android.graphics.Bitmap
import android.os.SharedMemory
import android.system.OsConstants

internal object OcrSharedMemory {
    const val MAX_PIXEL_BYTES = 128 * 1024 * 1024

    fun validatePixels(width: Int, height: Int, rowBytes: Int, size: Int) {
        require(width > 0 && height > 0 && rowBytes.toLong() >= width.toLong() * 4)
        require(size in 1..MAX_PIXEL_BYTES && rowBytes.toLong() * height == size.toLong())
    }

    /** No disk files or image encoding. Caller owns the source, receiver owns its pixel copy. */
    fun fromBitmap(source: Bitmap): PixelTransfer {
        val bitmap = if (source.config == Bitmap.Config.ARGB_8888) source else
            checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        try {
            validatePixels(bitmap.width, bitmap.height, bitmap.rowBytes, bitmap.byteCount)
            val memory = SharedMemory.create("ocr-input", bitmap.byteCount)
            try {
                val buffer = memory.mapReadWrite()
                try { bitmap.copyPixelsToBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
                check(memory.setProtect(OsConstants.PROT_READ))
                return PixelTransfer(memory, bitmap.width, bitmap.height, bitmap.rowBytes)
            } catch (error: Throwable) {
                memory.close()
                throw error
            }
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    fun toBitmap(memory: SharedMemory, width: Int, height: Int, rowBytes: Int): Bitmap {
        validatePixels(width, height, rowBytes, memory.size)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val source = memory.mapReadOnly()
            try {
                if (bitmap.rowBytes == rowBytes) {
                    bitmap.copyPixelsFromBuffer(source)
                } else {
                    // Bitmap row alignment can differ between the two allocations.
                    val target = java.nio.ByteBuffer.allocateDirect(bitmap.byteCount)
                    for (row in 0 until height) {
                        source.limit(row * rowBytes + width * 4).position(row * rowBytes)
                        target.position(row * bitmap.rowBytes)
                        target.put(source)
                    }
                    target.rewind()
                    bitmap.copyPixelsFromBuffer(target)
                }
            } finally { SharedMemory.unmap(source) }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    fun fromBytes(bytes: ByteArray): SharedMemory {
        require(bytes.size in 1..OcrWireCodec.MAX_BYTES)
        val memory = SharedMemory.create("ocr-result", bytes.size)
        try {
            val buffer = memory.mapReadWrite()
            try { buffer.put(bytes) } finally { SharedMemory.unmap(buffer) }
            check(memory.setProtect(OsConstants.PROT_READ))
            return memory
        } catch (error: Throwable) {
            memory.close()
            throw error
        }
    }

    fun toBytes(memory: SharedMemory): ByteArray {
        require(memory.size in 1..OcrWireCodec.MAX_BYTES)
        val buffer = memory.mapReadOnly()
        return try { ByteArray(memory.size).also { buffer.get(it) } }
        finally { SharedMemory.unmap(buffer) }
    }
}

internal data class PixelTransfer(
    val memory: SharedMemory,
    val width: Int,
    val height: Int,
    val rowBytes: Int,
) : AutoCloseable {
    override fun close() = memory.close()
}
