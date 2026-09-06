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

package com.akslabs.circletosearch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Build
import android.widget.Toast
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.data.AssistDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private const val CAPTURE_FALLBACK_TIMEOUT_MS = 5_000L
private const val SYSTEM_SCREENSHOT_GRACE_MS = 250L
private const val ACCESSIBILITY_RETRY_DELAY_MS = 400L
private const val OVERLAY_LAUNCH_ACK_TIMEOUT_MS = 900L
private const val ASSIST_DELIVERY_GRACE_MS = 600L
private const val ASSIST_ANALYSIS_TIMEOUT_MS = 5_000L

internal fun shouldFinishUnshownAssistantSession(
    expectedInvocationId: Long,
    activeInvocationId: Long?,
    shown: Boolean,
    finishRequested: Boolean,
    destroyed: Boolean,
): Boolean {
    return !destroyed &&
        !finishRequested &&
        activeInvocationId == expectedInvocationId &&
        !shown
}

class AssistSessionService : VoiceInteractionSessionService() {

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("AssistSessionService", "Service onCreate")
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        android.util.Log.d("AssistSessionService", "onNewSession created")
        return CircleToSearchSession(this)
    }

    inner class CircleToSearchSession(context: Context) : VoiceInteractionSession(context) {

        private val captureCoordinator = CaptureSessionCoordinator()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val assistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val assistAnalysis = AssistAnalysisRunner(assistScope)
        private val receivedAssistIndices = mutableSetOf<Int>()
        private var legacyInvocationId = 0L
        private var activeInvocationId: Long? = null
        private var activeVoiceLease: AssistantInvocationGate.Lease? = null
        private var activeAssistToken: String? = null
        private var destroyed = false
        private var shown = false
        private var assistExpected = false
        private var assistComplete = true
        private var assistAnalysisGeneration = 0L
        private var assistAnalysisDeadline = 0L
        private var overlayLaunched = false
        private var captureTimeoutInvocationId: Long? = null
        private var captureWatchdogGeneration = 0L
        private var prepareWatchdogGeneration = 0L
        private var captureDeadlineElapsedRealtime = 0L
        private var discardScreenshotsUntilNextPrepare = false
        private var stagedPreShowBitmap: Bitmap? = null
        private var pendingBitmap: Bitmap? = null
        private var finishRequested = false

        override fun onPrepareShow(args: Bundle?, showFlags: Int) {
            super.onPrepareShow(args, showFlags)
            setUiEnabled(false)

            // finish() is terminal for a VoiceInteractionSession instance. If
            // the framework delivers a queued prepare during teardown, leave it
            // for the new session instead of reviving this finishing object.
            if (destroyed) {
                android.util.Log.w(
                    "AssistSessionService",
                    "Ignoring prepare callback on a destroyed session",
                )
                return
            }
            if (finishRequested) {
                android.util.Log.w(
                    "AssistSessionService",
                    "Rejecting prepare callback on a finishing session",
                )
                // finish() is token-scoped and idempotent. Reasserting it makes
                // a queued framework callback terminal instead of leaving the
                // new show request attached to this dying session object.
                finish()
                return
            }

            val invocationId = if (
                Build.VERSION.SDK_INT >= 34 &&
                args?.containsKey(KEY_SHOW_SESSION_ID) == true
            ) {
                args.getInt(KEY_SHOW_SESSION_ID).toLong()
            } else {
                ++legacyInvocationId
            }

            if (!captureCoordinator.begin(invocationId)) return

            assistAnalysis.cancel()

            recyclePendingBitmap()
            activeInvocationId = invocationId
            activeVoiceLease = null
            discardScreenshotsUntilNextPrepare = false
            shown = false
            assistExpected = showFlags and SHOW_WITH_ASSIST != 0
            assistComplete = !assistExpected
            overlayLaunched = false
            captureTimeoutInvocationId = null
            captureWatchdogGeneration++
            captureDeadlineElapsedRealtime =
                android.os.SystemClock.elapsedRealtime() + CAPTURE_FALLBACK_TIMEOUT_MS
            receivedAssistIndices.clear()
            activeAssistToken = java.util.UUID.randomUUID().toString()
            AssistDataRepository.begin(checkNotNull(activeAssistToken))
            schedulePrepareShowWatchdog(invocationId)

            stagedPreShowBitmap?.let { bitmap ->
                stagedPreShowBitmap = null
                acceptBitmap(invocationId, CaptureSource.SYSTEM_SCREENSHOT, bitmap)
            }
        }

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            android.util.Log.d("AssistSessionService", "onShow called with flags: $showFlags")

            if (destroyed || finishRequested) {
                android.util.Log.w(
                    "AssistSessionService",
                    "Rejecting show callback on a terminal session",
                )
                if (!destroyed) finish()
                return
            }

            val invocationId = activeInvocationId ?: run {
                android.util.Log.e("AssistSessionService", "Show callback has no active invocation")
                return
            }
            prepareWatchdogGeneration++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val lease = AssistantInvocationGate.claimSession(invocationId)
                if (lease == null) {
                    android.util.Log.w(
                        "AssistSessionService",
                        "Ignoring completed recovery session $invocationId",
                    )
                    discardRejectedInvocation(invocationId)
                    return
                }
                activeVoiceLease = lease
                CircleToSearchAccessibilityService.cancelAssistantCapture(invocationId)
            }
            captureDeadlineElapsedRealtime =
                android.os.SystemClock.elapsedRealtime() + CAPTURE_FALLBACK_TIMEOUT_MS
            shown = true
            pendingBitmap?.let { bitmap ->
                pendingBitmap = null
                launchAcceptedBitmap(invocationId, bitmap)
            }

            // Pixel normally supplies SHOW_WITH_SCREENSHOT within a few frames.
            // Starting AccessibilityService.takeScreenshot at the same time races
            // the system capture and leaves the next invocation stuck on BUSY.
            val expectsSystemScreenshot = showFlags and SHOW_WITH_SCREENSHOT != 0
            scheduleAccessibilityFallback(
                invocationId = invocationId,
                delayMillis = if (expectsSystemScreenshot) SYSTEM_SCREENSHOT_GRACE_MS else 0L,
            )
            scheduleCaptureWatchdog(invocationId, restart = true)
        }

        private fun scheduleAccessibilityFallback(
            invocationId: Long,
            delayMillis: Long,
        ) {
            if (
                destroyed ||
                activeInvocationId != invocationId ||
                captureCoordinator.hasWinner(invocationId) ||
                android.os.SystemClock.elapsedRealtime() >= captureDeadlineElapsedRealtime
            ) {
                return
            }

            mainHandler.postDelayed(
                { startAccessibilityFallback(invocationId) },
                delayMillis.coerceAtLeast(0L),
            )
        }

        private fun startAccessibilityFallback(invocationId: Long) {
            if (
                destroyed ||
                activeInvocationId != invocationId ||
                captureCoordinator.hasWinner(invocationId) ||
                android.os.SystemClock.elapsedRealtime() >= captureDeadlineElapsedRealtime ||
                !captureCoordinator.shouldStartAccessibility(invocationId)
            ) {
                return
            }

            android.util.Log.d(
                "AssistSessionService",
                "Requesting accessibility fallback for $invocationId",
            )
            val startResult = CircleToSearchAccessibilityService.triggerCapture(
                assistantOwnerId = invocationId,
            ) { result ->
                when (result) {
                    is AssistantCaptureResult.Success -> {
                        val posted = mainHandler.post {
                            acceptBitmap(
                                invocationId = invocationId,
                                source = CaptureSource.ACCESSIBILITY,
                                bitmap = result.bitmap,
                            )
                        }
                        if (!posted && !result.bitmap.isRecycled) {
                            result.bitmap.recycle()
                        }
                    }
                    is AssistantCaptureResult.Failure -> mainHandler.post {
                        android.util.Log.w(
                            "AssistSessionService",
                            "Accessibility fallback failed for $invocationId: ${result.errorCode}",
                        )
                        if (captureCoordinator.releaseAccessibilityAttempt(invocationId)) {
                            scheduleAccessibilityFallback(
                                invocationId,
                                ACCESSIBILITY_RETRY_DELAY_MS,
                            )
                        }
                    }
                    AssistantCaptureResult.Cancelled -> mainHandler.post {
                        if (captureCoordinator.releaseAccessibilityAttempt(invocationId)) {
                            scheduleAccessibilityFallback(
                                invocationId,
                                ACCESSIBILITY_RETRY_DELAY_MS,
                            )
                        }
                    }
                }
            }

            when (startResult) {
                CaptureStartResult.STARTED -> Unit
                CaptureStartResult.BUSY,
                CaptureStartResult.UNAVAILABLE -> {
                    captureCoordinator.releaseAccessibilityAttempt(invocationId)
                    scheduleAccessibilityFallback(
                        invocationId,
                        ACCESSIBILITY_RETRY_DELAY_MS,
                    )
                }
                CaptureStartResult.UNSUPPORTED -> {
                    captureCoordinator.releaseAccessibilityAttempt(invocationId)
                }
            }
        }

        override fun onHandleAssist(state: AssistState) {
            if (destroyed || finishRequested) return
            if (state.index >= 0) receivedAssistIndices += state.index
            if (!state.isFocused) return
            val token = activeAssistToken ?: return
            val invocationId = activeInvocationId ?: return
            val structure = state.assistStructure
            val coordinateBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.getSystemService(android.view.WindowManager::class.java)
                    ?.maximumWindowMetrics?.bounds
            } else {
                null
            }
            val metrics = context.resources.displayMetrics
            val width = coordinateBounds?.width() ?: metrics.widthPixels
            val height = coordinateBounds?.height() ?: metrics.heightPixels
            assistComplete = false
            assistAnalysisGeneration++
            assistAnalysisDeadline = android.os.SystemClock.uptimeMillis() + ASSIST_ANALYSIS_TIMEOUT_MS
            assistAnalysis.start(
                readNodes = { readAssistTextNodes(structure) },
                onComplete = { result ->
                    if (
                        !destroyed && !finishRequested &&
                        activeInvocationId == invocationId && activeAssistToken == token
                    ) {
                        result.fold(
                            onSuccess = { nodes ->
                                AssistDataRepository.publish(token, nodes, width, height)
                            },
                            onFailure = {
                                // Do not log framework exception messages that may include user text.
                                android.util.Log.w("AssistSessionService", "Assist text analysis failed")
                            },
                        )
                        assistComplete = true
                        if (overlayLaunched) finishAfterAssistDelivery(invocationId)
                    }
                },
            )
            if (overlayLaunched) finishAfterAssistDelivery(invocationId)
        }

        override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
            super.onHandleScreenshot(screenshot)
            android.util.Log.d("AssistSessionService", "onHandleScreenshot received, bitmap null? ${screenshot == null}")

            if (destroyed || discardScreenshotsUntilNextPrepare) {
                screenshot?.takeUnless { it.isRecycled }?.recycle()
                return
            }

            if (screenshot != null) {
                val invocationId = activeInvocationId
                if (invocationId == null) {
                    stagedPreShowBitmap?.takeUnless { it.isRecycled }?.recycle()
                    stagedPreShowBitmap = screenshot
                } else {
                    acceptBitmap(invocationId, CaptureSource.SYSTEM_SCREENSHOT, screenshot)
                }
            } else {
                activeInvocationId?.let { invocationId ->
                    scheduleAccessibilityFallback(invocationId, 0L)
                    scheduleCaptureWatchdog(invocationId)
                }
            }
        }

        private fun acceptBitmap(
            invocationId: Long,
            source: CaptureSource,
            bitmap: Bitmap,
        ) {
            if (
                destroyed ||
                activeInvocationId != invocationId ||
                !canAcceptVoiceBitmap() ||
                !captureCoordinator.tryComplete(invocationId, source)
            ) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return
            }

            android.util.Log.d("AssistSessionService", "$source won capture for $invocationId")
            if (source == CaptureSource.SYSTEM_SCREENSHOT && shown) {
                CircleToSearchAccessibilityService.cancelAssistantCapture(invocationId)
            }
            if (!shown) {
                pendingBitmap = bitmap
                return
            }

            launchAcceptedBitmap(invocationId, bitmap)
        }

        private fun launchAcceptedBitmap(invocationId: Long, bitmap: Bitmap) {
            if (destroyed || invocationId != activeInvocationId || overlayLaunched) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return
            }
            if (!ownsVoiceInvocation()) {
                if (!bitmap.isRecycled) bitmap.recycle()
                captureCoordinator.cancel(invocationId)
                shown = false
                finishSession(invocationId)
                return
            }

            val captureId = BitmapRepository.setScreenshot(bitmap)
            if (launchOverlayDirectly(captureId)) {
                overlayLaunched = true
                activeVoiceLease?.let { lease ->
                    scheduleOverlayLaunchAcknowledgement(
                        invocationId = invocationId,
                        lease = lease,
                        captureId = captureId,
                        bitmap = bitmap,
                        assistToken = activeAssistToken,
                        attempt = 0,
                        delayMillis = OVERLAY_LAUNCH_ACK_TIMEOUT_MS,
                    )
                }
                finishAfterAssistDelivery(invocationId)
            } else if (activeVoiceLease != null) {
                overlayLaunched = true
                scheduleOverlayLaunchAcknowledgement(
                    invocationId = invocationId,
                    lease = checkNotNull(activeVoiceLease),
                    captureId = captureId,
                    bitmap = bitmap,
                    assistToken = activeAssistToken,
                    attempt = 0,
                    delayMillis = 0L,
                )
                finishAfterAssistDelivery(invocationId)
            } else {
                BitmapRepository.clearIfSame(captureId, bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()
                captureCoordinator.cancel(invocationId)
                shown = false
                finishSession(invocationId)
            }
        }

        private fun ownsVoiceInvocation(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            val lease = activeVoiceLease ?: return false
            return AssistantInvocationGate.isSessionOwner(lease)
        }

        private fun finishAfterAssistDelivery(invocationId: Long) {
            if (!assistExpected || assistComplete) {
                finishSession(invocationId)
                return
            }

            if (assistAnalysis.isRunning) {
                val generation = assistAnalysisGeneration
                // Bound processing separately from waiting for the framework callback.
                val remaining = (assistAnalysisDeadline - android.os.SystemClock.uptimeMillis())
                    .coerceAtLeast(0L)
                mainHandler.postDelayed({
                    if (!destroyed && activeInvocationId == invocationId &&
                        assistAnalysisGeneration == generation && overlayLaunched
                    ) finishSession(invocationId)
                }, remaining)
                return
            }

            // Screenshot delivery often wins the race by a few frames. Keep the
            // session alive briefly so its semantic AssistStructure can enrich
            // OCR, but retain the bounded finish that prevents a stale assistant
            // binder from breaking the next corner gesture.
            mainHandler.postDelayed({
                if (
                    !destroyed &&
                    activeInvocationId == invocationId &&
                    overlayLaunched && !assistAnalysis.isRunning
                ) {
                    finishSession(invocationId)
                }
            }, ASSIST_DELIVERY_GRACE_MS)
        }

        private fun canAcceptVoiceBitmap(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            val lease = activeVoiceLease
            return if (lease == null) !shown else AssistantInvocationGate.isSessionOwner(lease)
        }

        private fun launchOverlayDirectly(captureId: Long): Boolean {
            android.util.Log.d("AssistSessionService", "Launching OverlayActivity")
            val intent = Intent(context, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("triggered_by", "assistant")
                putExtra(OverlayActivity.EXTRA_CAPTURE_ID, captureId)
                activeAssistToken?.let { putExtra(OverlayActivity.EXTRA_ASSIST_TOKEN, it) }
                activeVoiceLease?.let { lease ->
                    putExtra(OverlayActivity.EXTRA_ASSIST_INVOCATION_ID, lease.sessionId)
                    putExtra(OverlayActivity.EXTRA_ASSIST_INVOCATION_GENERATION, lease.generation)
                }
            }

            activeVoiceLease?.let { lease ->
                if (
                    CircleToSearchAccessibilityService.relaunchAssistantOverlay(
                        lease = lease,
                        assistToken = activeAssistToken,
                        captureId = captureId,
                    )
                ) {
                    return true
                }
            }

            return try {
                // Keep the visual overlay in a regular application task. An
                // assistant task remains attached to the session token and can
                // briefly occlude the next corner gesture after it is closed.
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                android.util.Log.e("AssistSessionService", "Failed to launch OverlayActivity", e)
                Toast.makeText(context, "Couldn't open Circle to Search", Toast.LENGTH_SHORT).show()
                false
            }
        }

        private fun scheduleOverlayLaunchAcknowledgement(
            invocationId: Long,
            lease: AssistantInvocationGate.Lease,
            captureId: Long,
            bitmap: Bitmap,
            assistToken: String?,
            attempt: Int,
            delayMillis: Long,
        ) {
            mainHandler.postDelayed(
                {
                    if (
                        !AssistantInvocationGate.isSessionOwner(lease) ||
                        !BitmapRepository.isCurrent(captureId, bitmap)
                    ) {
                        return@postDelayed
                    }

                    if (attempt >= 3) {
                        android.util.Log.e(
                            "AssistSessionService",
                            "Overlay launch was never acknowledged for $invocationId",
                        )
                        AssistantInvocationGate.abandon(lease)
                        BitmapRepository.clearIfSame(captureId, bitmap)
                        assistToken?.let(AssistDataRepository::clear)
                        return@postDelayed
                    }

                    android.util.Log.w(
                        "AssistSessionService",
                        "Overlay launch was not acknowledged for $invocationId; relaunching",
                    )
                    requestAssistantOverlayRelaunch(
                        lease = lease,
                        assistToken = assistToken,
                        captureId = captureId,
                        preferDirectLaunch = attempt % 2 == 1,
                    )
                    scheduleOverlayLaunchAcknowledgement(
                        invocationId = invocationId,
                        lease = lease,
                        captureId = captureId,
                        bitmap = bitmap,
                        assistToken = assistToken,
                        attempt = attempt + 1,
                        delayMillis = OVERLAY_LAUNCH_ACK_TIMEOUT_MS,
                    )
                },
                delayMillis,
            )
        }

        private fun requestAssistantOverlayRelaunch(
            lease: AssistantInvocationGate.Lease,
            assistToken: String?,
            captureId: Long,
            preferDirectLaunch: Boolean,
        ): Boolean {
            if (!preferDirectLaunch &&
                CircleToSearchAccessibilityService.relaunchAssistantOverlay(
                    lease = lease,
                    assistToken = assistToken,
                    captureId = captureId,
                )
            ) {
                return true
            }

            val directLaunchSucceeded = try {
                context.startActivity(
                    Intent(context, OverlayActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        putExtra("triggered_by", "assistant_recovery")
                        putExtra(OverlayActivity.EXTRA_CAPTURE_ID, captureId)
                        putExtra(OverlayActivity.EXTRA_ASSIST_INVOCATION_ID, lease.sessionId)
                        putExtra(
                            OverlayActivity.EXTRA_ASSIST_INVOCATION_GENERATION,
                            lease.generation,
                        )
                        assistToken?.let { putExtra(OverlayActivity.EXTRA_ASSIST_TOKEN, it) }
                    },
                )
                true
            } catch (error: Exception) {
                android.util.Log.e(
                    "AssistSessionService",
                    "Fallback overlay launch failed for ${lease.sessionId}",
                    error,
                )
                false
            }
            if (directLaunchSucceeded || !preferDirectLaunch) return directLaunchSucceeded

            return CircleToSearchAccessibilityService.relaunchAssistantOverlay(
                lease = lease,
                assistToken = assistToken,
                captureId = captureId,
            )
        }

        private fun discardRejectedInvocation(invocationId: Long) {
            captureCoordinator.cancel(invocationId)
            recyclePendingBitmap()
            activeAssistToken?.let(AssistDataRepository::clear)
            activeAssistToken = null
            shown = false
            finishSession(invocationId)
            activeInvocationId = null
            activeVoiceLease = null
            captureTimeoutInvocationId = null
            captureWatchdogGeneration++
            discardScreenshotsUntilNextPrepare = true
        }

        /**
         * `hide()` keeps Android's active VoiceInteractionSession binder alive
         * and reuses it for future gestures. If that retained session becomes
         * stale, SystemUI still vibrates but never opens the app. `finish()`
         * clears the active framework session so every gesture gets a fresh
         * token, which is also what re-selecting the default assistant does.
         */
        private fun finishSession(expectedInvocationId: Long? = activeInvocationId) {
            if (
                destroyed ||
                finishRequested ||
                (expectedInvocationId != null && activeInvocationId != expectedInvocationId)
            ) {
                return
            }
            finishRequested = true
            assistAnalysis.cancel()
            assistComplete = true
            shown = false
            prepareWatchdogGeneration++
            // VoiceInteractionSession callbacks and every caller of this helper
            // run on the main looper. Finishing synchronously is important: a
            // queued finish from invocation N could otherwise run after Android
            // has delivered onPrepareShow for invocation N+1 and destroy the
            // fresh session before its overlay is launched.
            finish()
        }

        private fun schedulePrepareShowWatchdog(invocationId: Long) {
            val watchdogGeneration = ++prepareWatchdogGeneration
            mainHandler.postDelayed({
                if (prepareWatchdogGeneration != watchdogGeneration) return@postDelayed
                if (!shouldFinishUnshownAssistantSession(
                        expectedInvocationId = invocationId,
                        activeInvocationId = activeInvocationId,
                        shown = shown,
                        finishRequested = finishRequested,
                        destroyed = destroyed,
                    )
                ) {
                    return@postDelayed
                }

                // A screenshot can arrive before onShow(), so this timeout must
                // be independent of whether captureCoordinator already has a
                // winner. Without a terminal finish, Android can retain a
                // PREPARING session that blocks every later assist gesture.
                android.util.Log.e(
                    "AssistSessionService",
                    "Show callback timed out for invocation $invocationId",
                )
                captureCoordinator.cancel(invocationId)
                recyclePendingBitmap()
                activeAssistToken?.let(AssistDataRepository::clear)
                activeAssistToken = null
                finishSession(invocationId)
            }, CAPTURE_FALLBACK_TIMEOUT_MS)
        }

        private fun scheduleCaptureWatchdog(
            invocationId: Long,
            restart: Boolean = false,
        ) {
            if (
                destroyed ||
                invocationId != activeInvocationId ||
                (!restart && captureTimeoutInvocationId == invocationId)
            ) return

            val watchdogGeneration = ++captureWatchdogGeneration
            captureTimeoutInvocationId = invocationId
            mainHandler.postDelayed({
                if (captureWatchdogGeneration != watchdogGeneration) return@postDelayed
                if (captureTimeoutInvocationId == invocationId) {
                    captureTimeoutInvocationId = null
                }
                if (
                    !destroyed &&
                    activeInvocationId == invocationId &&
                    !captureCoordinator.hasWinner(invocationId)
                ) {
                    captureCoordinator.cancel(invocationId)
                    shown = false
                    Toast.makeText(
                        context,
                        "Couldn't capture the screen. Try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    finishSession(invocationId)
                }
            }, CAPTURE_FALLBACK_TIMEOUT_MS)
        }

        private fun recyclePendingBitmap() {
            pendingBitmap?.takeUnless { it.isRecycled }?.recycle()
            pendingBitmap = null
        }

        override fun onHide() {
            shown = false
            super.onHide()
            // A hide can also be initiated by SystemUI before capture finishes.
            // Make it terminal so Android cannot retain a wedged invisible
            // assistant session for the next invocation.
            finishSession()
        }

        override fun onDestroy() {
            destroyed = true
            assistAnalysis.cancel()
            assistScope.cancel()
            val leaseToRelease = activeVoiceLease?.takeUnless { overlayLaunched }
            activeInvocationId?.let(captureCoordinator::cancel)
            activeInvocationId = null
            activeVoiceLease = null
            captureWatchdogGeneration++
            prepareWatchdogGeneration++
            recyclePendingBitmap()
            stagedPreShowBitmap?.takeUnless { it.isRecycled }?.recycle()
            stagedPreShowBitmap = null
            leaseToRelease?.let(AssistantInvocationGate::abandon)
            super.onDestroy()
        }

    }
}
