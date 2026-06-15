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

/**
 * In-memory handoff for the captured screenshot between the capture source
 * (AccessibilityService / AssistSessionService) and the consumer (OverlayActivity,
 * CopyTextOverlayManager).
 *
 * Producers and consumers run on different threads (capture executor, service
 * coroutine, Main), so the field is @Volatile for cross-thread visibility; a
 * plain var would let a consumer observe a stale reference under the JMM.
 * Mutual exclusion between two concurrent captures is handled upstream by the
 * captureInProgress guard in CircleToSearchAccessibilityService.
 */
object BitmapRepository {
    // @Volatile gives cross-thread visibility: producers write off the capture
    // executor / a service coroutine, consumers read on Main. Plain var would
    // let a consumer observe a stale (null or previous) reference under the JMM.
    @Volatile
    private var screenshot: Bitmap? = null

    fun setScreenshot(bitmap: Bitmap?) {
        screenshot = bitmap
    }

    fun getScreenshot(): Bitmap? {
        return screenshot
    }

    fun clear() {
        screenshot = null
    }
}
