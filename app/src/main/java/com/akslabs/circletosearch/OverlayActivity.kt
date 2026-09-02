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

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.ui.CircleToSearchScreen
import com.akslabs.circletosearch.utils.UIPreferences
import com.akslabs.circletosearch.utils.StorageUtils
import com.akslabs.circletosearch.ui.components.CopyTextOverlayManager
import com.akslabs.circletosearch.ui.theme.CircleToSearchTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment

class OverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ASSIST_TOKEN = "EXTRA_ASSIST_TOKEN"
        const val EXTRA_ASSIST_INVOCATION_ID = "EXTRA_ASSIST_INVOCATION_ID"
        const val EXTRA_ASSIST_INVOCATION_GENERATION =
            "EXTRA_ASSIST_INVOCATION_GENERATION"
        const val EXTRA_CAPTURE_ID = "EXTRA_CAPTURE_ID"
    }

    private val copyTextManager = androidx.compose.runtime.mutableStateOf<CopyTextOverlayManager?>(null)
    private val searchModeOverride = androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    private val assistToken = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val isTranslating = androidx.compose.runtime.mutableStateOf(false)
    private val screenshotBitmap = androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    private var translationJob: Job? = null
    private var pendingAssistantLease: AssistantInvocationGate.Lease? = null
    private var windowMadeNonOccluding = false
    private var screenshotCaptureId = BitmapRepository.NO_CAPTURE_ID
    private var contentGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        configureWindowTransitions()
        android.util.Log.d("CircleToSearch", "OverlayActivity onCreate")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                StorageUtils.pruneTransientImageCache(this@OverlayActivity)
            } catch (error: Exception) {
                android.util.Log.w(
                    "CircleToSearch",
                    "Unable to prune transient image cache",
                    error,
                )
            }
        }
        
        // Ensure the activity can receive touches and focus properly
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        updateAssistToken(intent)
        loadScreenshot(intent)
        updateOverride(intent)
        acknowledgeAssistantInvocation(intent)

        // Initialize manager for Activity-based layout
        replaceCopyTextManager(screenshotBitmap.value)

        setContent {
            CircleToSearchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CircleToSearchScreen(
                            screenshot = screenshotBitmap.value,
                            searchModeOverride = searchModeOverride.value,
                            assistToken = assistToken.value,
                            onClose = { 
                                screenshotBitmap.value?.let { bitmap ->
                                    BitmapRepository.clearIfSame(screenshotCaptureId, bitmap)
                                }
                                assistToken.value?.let(
                                    com.akslabs.circletosearch.data.AssistDataRepository::clear,
                                )
                                finish() 
                            },
                            copyTextManager = copyTextManager.value,
                            onExitCopyMode = {
                                copyTextManager.value?.disposeSilently()
                                CircleToSearchAccessibilityService.setCopyTextManager(null)
                                copyTextManager.value = null
                            },
                            onTranslate = { 
                                val targetLang = UIPreferences(this@OverlayActivity).getTargetTranslateLang()
                                translateCurrentScreen(targetLang)
                            }
                        )
                        
                        if (isTranslating.value) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Translating screen...", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        android.util.Log.d("CircleToSearch", "OverlayActivity onNewIntent - Resetting state")

        // A singleTask retry can arrive while finish() is already tearing this
        // window down. Never make that dying instance visible/touchable again;
        // the assistant acknowledgement watchdog will launch a fresh instance.
        if (isFinishing || isDestroyed) return

        val incomingCaptureId = intent.getLongExtra(
            EXTRA_CAPTURE_ID,
            BitmapRepository.NO_CAPTURE_ID,
        )
        val currentBitmap = screenshotBitmap.value
        if (
            incomingCaptureId != BitmapRepository.NO_CAPTURE_ID &&
            incomingCaptureId == screenshotCaptureId &&
            currentBitmap?.isRecycled == false &&
            BitmapRepository.isCurrent(incomingCaptureId, currentBitmap)
        ) {
            // Launch acknowledgement retries can legitimately deliver the same
            // singleTask intent again. Keep the current OCR/selection state and
            // only restore/acknowledge the existing interactive window.
            restoreInteractiveWindow()
            setIntent(intent)
            updateAssistToken(intent)
            updateOverride(intent)
            acknowledgeAssistantInvocation(intent)
            return
        }

        // Validate an explicit capture before touching the currently visible
        // content. A delayed retry for an older capture can arrive after a newer
        // overlay is already interactive; clearing first would turn that healthy
        // overlay transparent even though the stale bitmap no longer exists.
        val incomingSnapshot = resolveScreenshot(intent)
        val incomingBitmap = incomingSnapshot?.bitmap
        if (incomingBitmap == null || incomingBitmap.isRecycled) {
            android.util.Log.w(
                "CircleToSearch",
                "Ignoring stale overlay intent for capture $incomingCaptureId",
            )
            return
        }
        restoreInteractiveWindow()
        setIntent(intent)

        translationJob?.cancel()
        translationJob = null
        isTranslating.value = false
        
        val previousBitmap = screenshotBitmap.value
        if (previousBitmap?.isRecycled == false) {
            BitmapRepository.clearIfSame(screenshotCaptureId, previousBitmap)
        }

        // IMMEDIATE NULLING to prevent flash of previous screen
        screenshotBitmap.value = null
        screenshotCaptureId = BitmapRepository.NO_CAPTURE_ID
        copyTextManager.value?.disposeSilently()
        copyTextManager.value = null
        searchModeOverride.value = null

        updateAssistToken(intent)
        contentGeneration++
        screenshotCaptureId = incomingSnapshot.captureId
        screenshotBitmap.value = incomingBitmap
        updateOverride(intent)
        acknowledgeAssistantInvocation(intent)

        // Recreate manager with new screenshot
        replaceCopyTextManager(screenshotBitmap.value)
    }

    private fun configureWindowTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun restoreInteractiveWindow() {
        windowMadeNonOccluding = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.decorView.visibility = android.view.View.VISIBLE
        val attributes = window.attributes
        if (attributes.alpha != 1f) {
            attributes.alpha = 1f
            window.attributes = attributes
        }
    }

    override fun finish() {
        makeWindowNonOccluding()
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun finishAfterTransition() {
        // Predictive/system Back normally enters Activity.finishAfterTransition,
        // so route it through the same immediate input-occlusion cleanup.
        finish()
    }

    private fun makeWindowNonOccluding() {
        if (windowMadeNonOccluding) return
        windowMadeNonOccluding = true
        // Remove this full-screen translucent window from input occlusion
        // immediately. WindowManager may otherwise keep its alpha=1 surface
        // around for several frames and drop the next corner gesture.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val attributes = window.attributes
        attributes.alpha = 0f
        window.attributes = attributes
    }

    private fun acknowledgeAssistantInvocation(intent: android.content.Intent) {
        pendingAssistantLease = null
        if (
            screenshotBitmap.value?.isRecycled != false ||
            !intent.hasExtra(EXTRA_ASSIST_INVOCATION_ID) ||
            !intent.hasExtra(EXTRA_ASSIST_INVOCATION_GENERATION)
        ) {
            return
        }

        pendingAssistantLease = AssistantInvocationGate.Lease(
            sessionId = intent.getLongExtra(EXTRA_ASSIST_INVOCATION_ID, Long.MIN_VALUE),
            generation = intent.getLongExtra(
                EXTRA_ASSIST_INVOCATION_GENERATION,
                Long.MIN_VALUE,
            ),
        )
        window.decorView.post { acknowledgePendingAssistantInvocation() }
    }

    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post { acknowledgePendingAssistantInvocation() }
    }

    private fun acknowledgePendingAssistantInvocation() {
        val lease = pendingAssistantLease ?: return
        if (
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            isFinishing ||
            isDestroyed ||
            screenshotBitmap.value?.isRecycled != false
        ) {
            return
        }
        pendingAssistantLease = null
        if (AssistantInvocationGate.complete(lease)) {
            android.util.Log.d(
                "CircleToSearch",
                "Overlay acknowledged assistant invocation ${lease.sessionId}",
            )
        }
    }

    private fun updateOverride(intent: android.content.Intent) {
        if (intent.hasExtra("EXTRA_SEARCH_MODE_OVERRIDE")) {
            searchModeOverride.value = intent.getBooleanExtra("EXTRA_SEARCH_MODE_OVERRIDE", false)
        } else {
            searchModeOverride.value = null
        }
    }

    fun translateCurrentScreen(targetLangCode: String? = null) {
        val currentBitmap = screenshotBitmap.value ?: return

        if (currentBitmap.isRecycled) {
            Toast.makeText(this, "Image is no longer available", Toast.LENGTH_SHORT).show()
            return
        }

        if (isTranslating.value) return
        isTranslating.value = true

        translationJob = lifecycleScope.launch {
            var translatedBitmap: android.graphics.Bitmap? = null
            try {
                val recognizedNodes = copyTextManager.value
                    ?.getTranslationTextSnapshot()
                    .orEmpty()
                val outcome = ScreenTranslator().use { translator ->
                    translator.translateScreen(
                        screenshot = currentBitmap,
                        textNodes = recognizedNodes,
                        targetLangCode = targetLangCode,
                    )
                }
                when (outcome) {
                    is ScreenTranslationOutcome.Unchanged -> {
                        if (
                            isActive &&
                            screenshotBitmap.value === currentBitmap &&
                            !isFinishing &&
                            !isDestroyed
                        ) {
                            Toast.makeText(
                                this@OverlayActivity,
                                when (outcome.reason) {
                                    ScreenTranslationUnchangedReason.NO_RECOGNIZED_TEXT ->
                                        "No text found to translate"
                                    ScreenTranslationUnchangedReason.NO_TRANSLATABLE_TEXT ->
                                        "The visible text is already in the target language"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        isTranslating.value = false
                        return@launch
                    }
                    is ScreenTranslationOutcome.Translated -> {
                        translatedBitmap = outcome.bitmap
                    }
                }

                if (!isActive || screenshotBitmap.value !== currentBitmap || isFinishing || isDestroyed) {
                    translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                    translatedBitmap = null
                    isTranslating.value = false
                    return@launch
                }

                val completedBitmap = checkNotNull(translatedBitmap)
                if (!BitmapRepository.compareAndSetScreenshot(currentBitmap, completedBitmap)) {
                    completedBitmap.takeUnless { it.isRecycled }?.recycle()
                    translatedBitmap = null
                    isTranslating.value = false
                    return@launch
                }

                assistToken.value?.let(
                    com.akslabs.circletosearch.data.AssistDataRepository::clear,
                )
                assistToken.value = null
                screenshotBitmap.value = completedBitmap
                replaceCopyTextManager(completedBitmap)
                translatedBitmap = null
                isTranslating.value = false
            } catch (error: CancellationException) {
                translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                throw error
            } catch (e: Exception) {
                translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                android.util.Log.e("OverlayActivity", "Translation failed", e)
                Toast.makeText(this@OverlayActivity, e.message ?: "Translation failed", Toast.LENGTH_LONG).show()
                isTranslating.value = false
            } finally {
                if (translationJob === coroutineContext[Job]) {
                    translationJob = null
                }
            }
        }
    }

    private fun loadScreenshot(intent: android.content.Intent) {
        val generation = ++contentGeneration
        val expectedCaptureId = intent.getLongExtra(
            EXTRA_CAPTURE_ID,
            BitmapRepository.NO_CAPTURE_ID,
        )
        val snapshot = resolveScreenshot(intent)
        val bitmap = snapshot?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            android.util.Log.d("CircleToSearch", "Bitmap loaded from Repository. Size: ${bitmap.width}x${bitmap.height}")
            screenshotCaptureId = snapshot.captureId
            screenshotBitmap.value = bitmap
        } else {
            screenshotCaptureId = BitmapRepository.NO_CAPTURE_ID
            screenshotBitmap.value = null
            android.util.Log.e(
                "CircleToSearch",
                "No matching bitmap in Repository for capture $expectedCaptureId",
            )
            // A newer singleTask intent can already be queued. Give it one UI
            // turn to arrive before closing an otherwise transparent Activity.
            window.decorView.postDelayed(
                {
                    if (
                        contentGeneration == generation &&
                        screenshotBitmap.value == null &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        finish()
                    }
                },
                250L,
            )
        }
    }

    private fun resolveScreenshot(
        intent: android.content.Intent,
    ): BitmapRepository.Snapshot? {
        val expectedCaptureId = intent.getLongExtra(
            EXTRA_CAPTURE_ID,
            BitmapRepository.NO_CAPTURE_ID,
        )
        return if (expectedCaptureId == BitmapRepository.NO_CAPTURE_ID) {
            // Compatibility for non-assistant launchers from older builds. All
            // current capture producers include an explicit capture identity.
            BitmapRepository.getSnapshot()
        } else {
            BitmapRepository.getSnapshot(expectedCaptureId)
        }
    }

    private fun updateAssistToken(intent: android.content.Intent) {
        val previousToken = assistToken.value
        val nextToken = intent.getStringExtra(EXTRA_ASSIST_TOKEN)
        assistToken.value = nextToken
        if (previousToken != null && previousToken != nextToken) {
            com.akslabs.circletosearch.data.AssistDataRepository.clear(previousToken)
        }
    }

    private fun replaceCopyTextManager(bitmap: android.graphics.Bitmap?) {
        copyTextManager.value?.disposeSilently()
        copyTextManager.value = CopyTextOverlayManager(
            context = this,
            screenshotBitmap = bitmap,
        )
        CircleToSearchAccessibilityService.setCopyTextManager(copyTextManager.value)
    }
    
    override fun onDestroy() {
        contentGeneration++
        pendingAssistantLease = null
        translationJob?.cancel()
        translationJob = null
        CircleToSearchAccessibilityService.setCopyTextManager(null)
        copyTextManager.value?.disposeSilently()
        copyTextManager.value = null

        if (isFinishing) {
            screenshotBitmap.value?.takeUnless { it.isRecycled }?.let { bitmap ->
                BitmapRepository.clearIfSame(screenshotCaptureId, bitmap)
            }
        }
        screenshotCaptureId = BitmapRepository.NO_CAPTURE_ID
        // Compose unbinds it naturally; the repository reference was released
        // above only for a terminal Activity, never for a configuration change.
        screenshotBitmap.value = null

        if (isFinishing) {
             assistToken.value?.let(
                 com.akslabs.circletosearch.data.AssistDataRepository::clear,
             )
        }
        super.onDestroy()
    }
}
