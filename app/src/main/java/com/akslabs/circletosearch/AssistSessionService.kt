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

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.app.assist.AssistStructure
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.widget.Toast
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.data.AssistDataRepository

private const val ASSIST_DELIVERY_TIMEOUT_MS = 10_000L
private const val CAPTURE_FALLBACK_TIMEOUT_MS = 2_500L

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
        private val receivedAssistIndices = mutableSetOf<Int>()
        private var legacyInvocationId = 0L
        private var activeInvocationId: Long? = null
        private var activeAssistToken: String? = null
        private var destroyed = false
        private var shown = false
        private var assistExpected = false
        private var assistComplete = true
        private var overlayLaunched = false
        private var captureTimeoutInvocationId: Long? = null
        private var stagedPreShowBitmap: Bitmap? = null
        private var pendingBitmap: Bitmap? = null

        override fun onPrepareShow(args: Bundle?, showFlags: Int) {
            super.onPrepareShow(args, showFlags)
            setUiEnabled(false)

            val invocationId = if (
                Build.VERSION.SDK_INT >= 34 &&
                args?.containsKey(KEY_SHOW_SESSION_ID) == true
            ) {
                args.getInt(KEY_SHOW_SESSION_ID).toLong()
            } else {
                ++legacyInvocationId
            }

            if (!captureCoordinator.begin(invocationId)) return

            recyclePendingBitmap()
            mainHandler.removeCallbacksAndMessages(null)
            activeInvocationId = invocationId
            shown = false
            assistExpected = showFlags and SHOW_WITH_ASSIST != 0
            assistComplete = !assistExpected
            overlayLaunched = false
            captureTimeoutInvocationId = null
            receivedAssistIndices.clear()
            BitmapRepository.clear()
            activeAssistToken = java.util.UUID.randomUUID().toString()
            AssistDataRepository.begin(checkNotNull(activeAssistToken))

            stagedPreShowBitmap?.let { bitmap ->
                stagedPreShowBitmap = null
                acceptBitmap(invocationId, CaptureSource.SYSTEM_SCREENSHOT, bitmap)
            }
        }

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            android.util.Log.d("AssistSessionService", "onShow called with flags: $showFlags")
            shown = true
            vibrateInvocation()

            val invocationId = activeInvocationId ?: return
            pendingBitmap?.let { bitmap ->
                pendingBitmap = null
                launchAcceptedBitmap(invocationId, bitmap)
            }

            if (captureCoordinator.shouldStartAccessibility(invocationId)) {
                android.util.Log.d("AssistSessionService", "Requesting accessibility capture for $invocationId")
                val startResult = CircleToSearchAccessibilityService.triggerCapture { result ->
                    when (result) {
                        is AssistantCaptureResult.Success -> mainHandler.post {
                            acceptBitmap(
                                invocationId = invocationId,
                                source = CaptureSource.ACCESSIBILITY,
                                bitmap = result.bitmap,
                            )
                        }
                        is AssistantCaptureResult.Failure -> mainHandler.post {
                            android.util.Log.w(
                                "AssistSessionService",
                                "Accessibility capture failed for $invocationId: ${result.errorCode}",
                            )
                            scheduleCaptureWatchdog(invocationId)
                        }
                    }
                }

                if (startResult != CaptureStartResult.STARTED) {
                    android.util.Log.w(
                        "AssistSessionService",
                        "Accessibility capture did not start for $invocationId: $startResult",
                    )
                    scheduleCaptureWatchdog(invocationId)
                }
            }
            scheduleCaptureWatchdog(invocationId)
        }

        override fun onHandleAssist(state: AssistState) {
            if (destroyed) return
            android.util.Log.d(
                "AssistSessionService",
                "onHandleAssist called: index=${state.index}, count=${state.count}, focused=${state.isFocused}",
            )

            if (state.index >= 0) receivedAssistIndices += state.index
            if (state.isFocused) {
                assistComplete = true
                activeAssistToken?.let { token ->
                    processAssistStructure(token, state.assistStructure)
                }
            }
            activeInvocationId?.let(::maybeHideSession)
        }

        private fun processAssistStructure(token: String, structure: AssistStructure?) {
            if (structure == null) {
                android.util.Log.w("AssistSessionService", "AssistStructure is null")
                return
            }

            val allNodes = mutableListOf<com.akslabs.circletosearch.ui.components.TextNode>()
            
            android.util.Log.d("AssistSessionService", "Capturing AssistStructure - Window count: ${structure.windowNodeCount}")
            
            // Process windows from TOP to BOTTOM (Reverse order)
            // This allows us to track occlusion from overlays like BottomSheets.
            for (i in (structure.windowNodeCount - 1) downTo 0) {
                val windowNode = structure.getWindowNodeAt(i)
                if (windowNode.displayId != android.view.Display.DEFAULT_DISPLAY) {
                    android.util.Log.d("AssistSessionService", "Skipping non-default display window")
                    continue
                }
                val windowTitle = windowNode.title?.toString() ?: "No Title"
                
                android.util.Log.d("AssistSessionService", "Processing Window [$i]: \"$windowTitle\"")
                
                val windowOffsetX = windowNode.left
                val windowOffsetY = windowNode.top
                
                // WindowNode does not expose reliable opacity/z-order information.
                // Retain visible text and spatially deduplicate it after traversal.
                collectTextNodes(windowNode.rootViewNode, windowOffsetX, windowOffsetY, allNodes)
            }

            val visualNodes = deduplicateAssistNodes(allNodes)
            if (visualNodes.isEmpty()) {
                android.util.Log.w("AssistSessionService", "No text nodes found in assist data")
            } else {
                android.util.Log.d("AssistSessionService", "Extracted ${visualNodes.size} visual text nodes")
            }
            
            val coordinateBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.getSystemService(android.view.WindowManager::class.java)
                    ?.maximumWindowMetrics
                    ?.bounds
            } else {
                null
            }
            val metrics = context.resources.displayMetrics
            if (!AssistDataRepository.publish(
                    token = token,
                    nodes = visualNodes,
                    coordinateWidth = coordinateBounds?.width() ?: metrics.widthPixels,
                    coordinateHeight = coordinateBounds?.height() ?: metrics.heightPixels,
                )
            ) {
                android.util.Log.w("AssistSessionService", "Discarded stale assist data for $token")
            }
        }

        private fun deduplicateAssistNodes(
            nodes: List<com.akslabs.circletosearch.ui.components.TextNode>,
        ): List<com.akslabs.circletosearch.ui.components.TextNode> {
            val accepted = mutableListOf<com.akslabs.circletosearch.ui.components.TextNode>()
            nodes.sortedBy { node ->
                (node.bounds.right - node.bounds.left).toLong() *
                    (node.bounds.bottom - node.bounds.top).toLong()
            }.forEach { candidate ->
                val normalizedText = candidate.fullText.trim().replace(Regex("\\s+"), " ")
                val duplicate = accepted.any { existing ->
                    if (existing.fullText.trim().replace(Regex("\\s+"), " ") != normalizedText) {
                        return@any false
                    }
                    val overlapWidth = (
                        minOf(existing.bounds.right, candidate.bounds.right) -
                            maxOf(existing.bounds.left, candidate.bounds.left)
                        ).coerceAtLeast(0)
                    val overlapHeight = (
                        minOf(existing.bounds.bottom, candidate.bounds.bottom) -
                            maxOf(existing.bounds.top, candidate.bounds.top)
                        ).coerceAtLeast(0)
                    val overlapArea = overlapWidth.toLong() * overlapHeight.toLong()
                    val existingArea = (existing.bounds.right - existing.bounds.left).toLong() *
                        (existing.bounds.bottom - existing.bounds.top).toLong()
                    val candidateArea = (candidate.bounds.right - candidate.bounds.left).toLong() *
                        (candidate.bounds.bottom - candidate.bounds.top).toLong()
                    overlapArea >= minOf(existingArea, candidateArea) * 0.8
                }
                if (!duplicate) accepted += candidate
            }
            return accepted
        }

        private fun collectTextNodes(
            node: AssistStructure.ViewNode,
            parentX: Int,
            parentY: Int,
            list: MutableList<com.akslabs.circletosearch.ui.components.TextNode>,
        ) {
            if (node.transformation != null) return

            val nodeX = parentX + node.left
            val nodeY = parentY + node.top
            val nodeRect = android.graphics.Rect(nodeX, nodeY, nodeX + node.width, nodeY + node.height)

            // Visual text is authoritative. Content descriptions and hints describe
            // semantics (often icons), not pixels, so they must not replace OCR text.
            val text = node.text?.toString()

            if (!text.isNullOrBlank() && 
                node.visibility == android.view.View.VISIBLE &&
                node.width > 0 && node.height > 10) {
                
                // Only add if it's within reasonable screen bounds (optional but safer)
                // Filter out words that are clearly blank
                val wordStrings = text.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (wordStrings.isNotEmpty()) {
                    // AssistStructure does not expose reliable per-word geometry for all
                    // widgets. Keep one accurate selectable block instead of inventing
                    // character boxes that break with wrapping, bidi, and proportional text.
                    val words = listOf(
                        com.akslabs.circletosearch.ui.components.Word(
                            text = text,
                            index = 0,
                            startIndex = 0,
                            endIndex = text.length,
                            bounds = android.graphics.RectF(nodeRect),
                        )
                    )

                    list.add(
                        com.akslabs.circletosearch.ui.components.TextNode(
                            id = java.util.UUID.randomUUID().toString(),
                            fullText = text,
                            bounds = nodeRect,
                            words = words
                        )
                    )
                }
            }

            // Important: For children, subtract current node's scroll position from translated coordinates
            val nextParentX = nodeX - node.scrollX
            val nextParentY = nodeY - node.scrollY

            for (i in 0 until node.childCount) {
                collectTextNodes(node.getChildAt(i), nextParentX, nextParentY, list)
            }
        }

        override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
            super.onHandleScreenshot(screenshot)
            android.util.Log.d("AssistSessionService", "onHandleScreenshot received, bitmap null? ${screenshot == null}")

            if (destroyed) {
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
                activeInvocationId?.let(::scheduleCaptureWatchdog)
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
                !captureCoordinator.tryComplete(invocationId, source)
            ) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return
            }

            android.util.Log.d("AssistSessionService", "$source won capture for $invocationId")
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

            BitmapRepository.setScreenshot(bitmap)
            if (launchOverlayDirectly()) {
                overlayLaunched = true
                maybeHideSession(invocationId)
            } else {
                BitmapRepository.clearIfSame(bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()
                captureCoordinator.cancel(invocationId)
                shown = false
                hide()
            }
        }

        private fun launchOverlayDirectly(): Boolean {
            android.util.Log.d("AssistSessionService", "Launching OverlayActivity")
            val intent = Intent(context, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("triggered_by", "assistant")
                activeAssistToken?.let { putExtra(OverlayActivity.EXTRA_ASSIST_TOKEN, it) }
            }

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startAssistantActivity(intent, ActivityOptions.makeBasic().toBundle())
                } else {
                    startAssistantActivity(intent)
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("AssistSessionService", "Failed to launch OverlayActivity", e)
                Toast.makeText(context, "Couldn't open Circle to Search", Toast.LENGTH_SHORT).show()
                false
            }
        }

        private fun maybeHideSession(invocationId: Long) {
            if (invocationId != activeInvocationId || !overlayLaunched) return
            if (!assistExpected || assistComplete) {
                hide()
                return
            }

            mainHandler.postDelayed({
                if (activeInvocationId == invocationId && overlayLaunched) hide()
            }, ASSIST_DELIVERY_TIMEOUT_MS)
        }

        private fun scheduleCaptureWatchdog(invocationId: Long) {
            if (
                destroyed ||
                invocationId != activeInvocationId ||
                captureTimeoutInvocationId == invocationId
            ) return

            captureTimeoutInvocationId = invocationId
            mainHandler.postDelayed({
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
                    hide()
                }
            }, CAPTURE_FALLBACK_TIMEOUT_MS)
        }

        private fun vibrateInvocation() {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            }
        }

        private fun recyclePendingBitmap() {
            pendingBitmap?.takeUnless { it.isRecycled }?.recycle()
            pendingBitmap = null
        }

        override fun onHide() {
            shown = false
            super.onHide()
        }

        override fun onDestroy() {
            destroyed = true
            activeInvocationId?.let(captureCoordinator::cancel)
            activeInvocationId = null
            mainHandler.removeCallbacksAndMessages(null)
            recyclePendingBitmap()
            stagedPreShowBitmap?.takeUnless { it.isRecycled }?.recycle()
            stagedPreShowBitmap = null
            super.onDestroy()
        }

    }
}
