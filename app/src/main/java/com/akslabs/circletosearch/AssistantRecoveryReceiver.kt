/*
 * Copyright (C) 2025 AKS-Labs (original author)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.akslabs.circletosearch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.akslabs.circletosearch.data.BitmapRepository
import java.util.concurrent.atomic.AtomicBoolean

private const val RECOVERY_RETRY_MS = 350L
private const val RECOVERY_DEADLINE_MS = 6_000L
private const val OVERLAY_ACK_TIMEOUT_MS = 700L
private const val MAX_OVERLAY_RELAUNCH_ATTEMPTS = 3

/**
 * Main-process bridge for the isolated voice service. The process-local gate
 * makes a delayed watchdog a no-op after a normal session and lets a late real
 * session take ownership while recovery capture is still in progress.
 */
class AssistantRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECOVER_ASSISTANT) return
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, Long.MIN_VALUE)
        if (sessionId == Long.MIN_VALUE) return

        val lease = AssistantInvocationGate.beginExternalRecovery(sessionId) ?: return
        val pendingResult = goAsync()
        val applicationContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_DEADLINE_MS
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "unknown failure"

        fun finishReceiver(abandonLease: Boolean) {
            if (abandonLease) AssistantInvocationGate.abandonRecovery(lease)
            if (finished.compareAndSet(false, true)) pendingResult.finish()
        }

        fun launchOverlay(captureId: Long): Boolean {
            if (CircleToSearchAccessibilityService.relaunchAssistantOverlay(
                lease = lease,
                assistToken = null,
                captureId = captureId,
            )) {
                return true
            }
            return try {
                applicationContext.startActivity(
                    Intent(applicationContext, OverlayActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        putExtra("triggered_by", "assistant_recovery")
                        putExtra(OverlayActivity.EXTRA_CAPTURE_ID, captureId)
                        putExtra(OverlayActivity.EXTRA_ASSIST_INVOCATION_ID, lease.sessionId)
                        putExtra(
                            OverlayActivity.EXTRA_ASSIST_INVOCATION_GENERATION,
                            lease.generation,
                        )
                    },
                )
                true
            } catch (error: Exception) {
                android.util.Log.e(
                    "AssistantRecovery",
                    "Unable to open recovered invocation ${lease.sessionId}",
                    error,
                )
                false
            }
        }

        fun scheduleOverlayAcknowledgement(
            captureId: Long,
            bitmap: android.graphics.Bitmap,
            attempt: Int,
        ) {
            handler.postDelayed(
                {
                    if (finished.get()) return@postDelayed
                    if (!AssistantInvocationGate.isRecoveryCommitted(lease)) {
                        // OverlayActivity acknowledged this lease, or a newer
                        // invocation superseded it. Either outcome is terminal.
                        finishReceiver(abandonLease = false)
                        return@postDelayed
                    }
                    if (!BitmapRepository.isCurrent(captureId, bitmap)) {
                        finishReceiver(abandonLease = true)
                        return@postDelayed
                    }
                    if (attempt >= MAX_OVERLAY_RELAUNCH_ATTEMPTS) {
                        android.util.Log.e(
                            "AssistantRecovery",
                            "Recovered overlay was never acknowledged for ${lease.sessionId}",
                        )
                        BitmapRepository.clearIfSame(captureId, bitmap)
                        finishReceiver(abandonLease = true)
                        return@postDelayed
                    }

                    android.util.Log.w(
                        "AssistantRecovery",
                        "Recovered overlay was not acknowledged for ${lease.sessionId}; relaunching",
                    )
                    launchOverlay(captureId)
                    scheduleOverlayAcknowledgement(captureId, bitmap, attempt + 1)
                },
                OVERLAY_ACK_TIMEOUT_MS,
            )
        }

        fun publishAndLaunch(bitmap: android.graphics.Bitmap) {
            if (!AssistantInvocationGate.commitRecovery(lease)) {
                bitmap.takeUnless { it.isRecycled }?.recycle()
                finishReceiver(abandonLease = false)
                return
            }

            val captureId = BitmapRepository.setScreenshot(bitmap)
            val launched = launchOverlay(captureId)

            if (!launched) {
                BitmapRepository.clearIfSame(captureId, bitmap)
                bitmap.takeUnless { it.isRecycled }?.recycle()
                finishReceiver(abandonLease = true)
                return
            }
            // Context.startActivity() returning normally is only an attempted
            // launch. Keep the goAsync receiver alive until OverlayActivity
            // actually reaches RESUMED and completes the lease, or until the
            // bounded retry window expires.
            scheduleOverlayAcknowledgement(captureId, bitmap, attempt = 0)
        }

        lateinit var attempt: Runnable
        fun retryOrFinish() {
            // A late real VoiceInteractionSession may take ownership while an
            // Accessibility failure callback is in flight. That is a success
            // for recovery coordination, not a timeout, and the receiver must
            // never abandon the new SESSION owner.
            if (!AssistantInvocationGate.isRecoveryCapturing(lease)) {
                finishReceiver(abandonLease = false)
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                android.util.Log.e(
                    "AssistantRecovery",
                    "Recovery timed out for invocation ${lease.sessionId}",
                )
                finishReceiver(abandonLease = true)
            } else {
                handler.postDelayed(attempt, RECOVERY_RETRY_MS)
            }
        }

        attempt = Runnable {
            if (finished.get()) return@Runnable
            if (!AssistantInvocationGate.isRecoveryCapturing(lease)) {
                finishReceiver(abandonLease = false)
                return@Runnable
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                finishReceiver(abandonLease = true)
                return@Runnable
            }

            when (
                CircleToSearchAccessibilityService.triggerCapture(
                    assistantOwnerId = lease.sessionId,
                ) { result ->
                    handler.post {
                        if (finished.get()) {
                            if (result is AssistantCaptureResult.Success) {
                                result.bitmap.takeUnless { it.isRecycled }?.recycle()
                            }
                            return@post
                        }
                        when (result) {
                            is AssistantCaptureResult.Success -> {
                                // A capture that completed beyond the recovery
                                // deadline must not start a new Activity/ACK cycle:
                                // doing so could keep this goAsync receiver alive
                                // past the system's execution window.
                                if (SystemClock.elapsedRealtime() >= deadline) {
                                    result.bitmap
                                        .takeUnless { it.isRecycled }
                                        ?.recycle()
                                    finishReceiver(abandonLease = true)
                                } else {
                                    publishAndLaunch(result.bitmap)
                                }
                            }
                            is AssistantCaptureResult.Failure -> retryOrFinish()
                            AssistantCaptureResult.Cancelled -> {
                                if (AssistantInvocationGate.isRecoveryCapturing(lease)) {
                                    retryOrFinish()
                                } else {
                                    finishReceiver(abandonLease = false)
                                }
                            }
                        }
                    }
                }
            ) {
                CaptureStartResult.STARTED -> Unit
                CaptureStartResult.BUSY,
                CaptureStartResult.UNAVAILABLE -> retryOrFinish()
                CaptureStartResult.UNSUPPORTED -> finishReceiver(abandonLease = true)
            }
        }

        android.util.Log.w(
            "AssistantRecovery",
            "Recovering invocation $sessionId: $reason",
        )
        handler.post(attempt)
    }

    companion object {
        const val ACTION_RECOVER_ASSISTANT =
            "com.akslabs.circletosearch.action.RECOVER_ASSISTANT"
        const val EXTRA_SESSION_ID = "assistant_session_id"
        const val EXTRA_REASON = "assistant_recovery_reason"
    }
}
