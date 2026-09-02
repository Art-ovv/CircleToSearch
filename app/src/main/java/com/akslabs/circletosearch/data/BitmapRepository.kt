/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.akslabs.circletosearch.data

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory handoff for the captured screenshot between the capture source
 * (AccessibilityService / AssistSessionService) and the consumer (OverlayActivity,
 * CopyTextOverlayManager).
 *
 * Producers and consumers run on different threads (capture executor, service
 * coroutine, Main). Atomic compare-and-set lets a long-running transformation
 * publish only when its source is still the current screenshot.
 */
object BitmapRepository {
    const val NO_CAPTURE_ID = Long.MIN_VALUE

    @ConsistentCopyVisibility
    data class Snapshot internal constructor(
        val captureId: Long,
        val bitmap: Bitmap,
    )

    private val nextCaptureId = AtomicLong(0L)
    private val screenshot = AtomicReference<Snapshot?>(null)

    /**
     * Publishes a new capture and returns the identity that must travel with
     * the Activity intent. Pairing the identity and bitmap in one atomic value
     * prevents a delayed intent from accidentally displaying a newer capture.
     */
    fun setScreenshot(bitmap: Bitmap?): Long {
        if (bitmap == null) {
            screenshot.set(null)
            return NO_CAPTURE_ID
        }
        val captureId = nextCaptureId.updateAndGet { previous ->
            if (previous == Long.MAX_VALUE) 1L else previous + 1L
        }
        screenshot.set(Snapshot(captureId = captureId, bitmap = bitmap))
        return captureId
    }

    fun getSnapshot(): Snapshot? = screenshot.get()

    fun getSnapshot(expectedCaptureId: Long): Snapshot? =
        screenshot.get()?.takeIf { it.captureId == expectedCaptureId }

    fun getScreenshot(): Bitmap? = screenshot.get()?.bitmap

    fun getScreenshot(expectedCaptureId: Long): Bitmap? =
        getSnapshot(expectedCaptureId)?.bitmap

    fun isCurrent(captureId: Long, bitmap: Bitmap): Boolean {
        val current = screenshot.get()
        return current?.captureId == captureId && current.bitmap === bitmap
    }

    fun clear() {
        screenshot.set(null)
    }

    fun clearIfSame(bitmap: Bitmap): Boolean {
        while (true) {
            val current = screenshot.get() ?: return false
            if (current.bitmap !== bitmap) return false
            if (screenshot.compareAndSet(current, null)) return true
        }
    }

    fun clearIfSame(captureId: Long, bitmap: Bitmap): Boolean {
        while (true) {
            val current = screenshot.get() ?: return false
            if (current.captureId != captureId || current.bitmap !== bitmap) return false
            if (screenshot.compareAndSet(current, null)) return true
        }
    }

    fun compareAndSetScreenshot(expected: Bitmap, replacement: Bitmap): Boolean {
        while (true) {
            val current = screenshot.get() ?: return false
            if (current.bitmap !== expected) return false
            val updated = current.copy(bitmap = replacement)
            if (screenshot.compareAndSet(current, updated)) return true
        }
    }
}
