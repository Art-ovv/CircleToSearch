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
    private val screenshot = AtomicReference<Bitmap?>(null)

    fun setScreenshot(bitmap: Bitmap?) {
        screenshot.set(bitmap)
    }

    fun getScreenshot(): Bitmap? {
        return screenshot.get()
    }

    fun clear() {
        screenshot.set(null)
    }

    fun clearIfSame(bitmap: Bitmap): Boolean {
        return screenshot.compareAndSet(bitmap, null)
    }

    fun compareAndSetScreenshot(expected: Bitmap, replacement: Bitmap): Boolean {
        return screenshot.compareAndSet(expected, replacement)
    }
}
