/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.akslabs.circletosearch.ui.CircleToSearchScreen
import com.akslabs.circletosearch.ui.components.CopyTextOverlayManager
import com.akslabs.circletosearch.ui.components.TextNode
import com.akslabs.circletosearch.ui.theme.CircleToSearchTheme
import com.akslabs.circletosearch.utils.UIPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CameraSearchActivity : ComponentActivity() {

    companion object {
        private const val KEY_SESSION_ID = "KEY_SESSION_ID"
        private const val KEY_PENDING_CAPTURE_PATH = "KEY_PENDING_CAPTURE_PATH"
        private const val KEY_ACTIVE_PHOTO_PATH = "KEY_ACTIVE_PHOTO_PATH"
        private const val KEY_SESSION_GENERATION = "KEY_SESSION_GENERATION"
        private const val KEY_SESSION_PHASE = "KEY_SESSION_PHASE"
        private const val KEY_CURRENT_CAMERA_URI = "KEY_CURRENT_CAMERA_URI"
    }

    private data class DeferredDecodeRequest(
        val filePath: String,
        val generation: Long,
    )

    private var sessionId = UUID.randomUUID().toString()
    private lateinit var sessionPolicy: CameraPhotoSessionPolicy

    private val photoBitmap = mutableStateOf<Bitmap?>(null)
    private val copyTextManager = mutableStateOf<CopyTextOverlayManager?>(null)
    private val translatedTextSnapshot = mutableStateOf<Pair<Bitmap, List<TextNode>>?>(null)
    private val isTranslating = mutableStateOf(false)
    private val isLoadingPhoto = mutableStateOf(false)

    private var loadJob: Job? = null
    private var translationJob: Job? = null
    private var translationTextCoordinator = ScreenTranslationTextCoordinator()

    private var currentCameraUri: Uri? = null
    private var measuredViewportSize: Pair<Int, Int>? = null
    private var deferredDecode: DeferredDecodeRequest? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = currentCameraUri
        currentCameraUri = null
        revokeCameraUri(uri)

        if (success) {
            when (val action = sessionPolicy.onCaptureResult(success = true, fileExistsAndNotEmpty = true)) {
                is CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode -> {
                    CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                        sessionId,
                        sessionPolicy.getFilesToPreserve(),
                    )
                    loadAndDisplayPhoto(action.filePath, action.generation)
                }
                is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingRetainActive -> {
                    CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                        sessionId,
                        sessionPolicy.getFilesToPreserve(),
                    )
                    CameraPhotoFileManager.deleteFileAsync(action.fileToDelete)
                }
                is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession -> {
                    CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                        sessionId,
                        sessionPolicy.getFilesToPreserve(),
                    )
                    CameraPhotoFileManager.deleteFileAsync(action.fileToDelete)
                    isLoadingPhoto.value = false
                    deferredDecode = null
                    finish()
                }
            }
        } else {
            when (val action = sessionPolicy.onCaptureResult(success = false, fileExistsAndNotEmpty = false)) {
                is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingRetainActive -> {
                    CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                        sessionId,
                        sessionPolicy.getFilesToPreserve(),
                    )
                    CameraPhotoFileManager.deleteFileAsync(action.fileToDelete)
                }
                is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession -> {
                    CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                        sessionId,
                        sessionPolicy.getFilesToPreserve(),
                    )
                    CameraPhotoFileManager.deleteFileAsync(action.fileToDelete)
                    isLoadingPhoto.value = false
                    deferredDecode = null
                    finish()
                }
                is CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode -> {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val restoredPending = savedInstanceState?.getString(KEY_PENDING_CAPTURE_PATH)
        val restoredActive = savedInstanceState?.getString(KEY_ACTIVE_PHOTO_PATH)
        val restoredGeneration = savedInstanceState?.getLong(KEY_SESSION_GENERATION, 0L) ?: 0L
        val restoredPhaseName = savedInstanceState?.getString(KEY_SESSION_PHASE)
        val restoredPhase = restoredPhaseName?.let {
            try {
                CameraPhotoSessionPolicy.SessionPhase.valueOf(it)
            } catch (_: Exception) {
                null
            }
        } ?: CameraPhotoSessionPolicy.SessionPhase.IDLE

        sessionId = savedInstanceState?.getString(KEY_SESSION_ID) ?: UUID.randomUUID().toString()
        currentCameraUri = savedInstanceState?.getString(KEY_CURRENT_CAMERA_URI)?.let(Uri::parse)

        sessionPolicy = CameraPhotoSessionPolicy(
            initialPendingPath = restoredPending,
            initialActivePath = restoredActive,
            initialGeneration = restoredGeneration,
            initialPhase = restoredPhase,
        )

        CameraPhotoFileManager.updateLiveSessionPreservedPaths(
            sessionId,
            sessionPolicy.getFilesToPreserve(),
        )

        lifecycleScope.launch {
            CameraPhotoFileManager.pruneOldFiles(
                context = this@CameraSearchActivity,
                preservePaths = sessionPolicy.getFilesToPreserve(),
            )
        }

        setContent {
            CircleToSearchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                    tonalElevation = 0.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                if (size.width > 0 && size.height > 0) {
                                    onViewportMeasured(size.width, size.height)
                                }
                            }
                    ) {
                        val currentBitmap = photoBitmap.value
                        if (currentBitmap != null) {
                            androidx.compose.runtime.key(currentBitmap) {
                                CircleToSearchScreen(
                                    screenshot = currentBitmap,
                                    preparedTextNodes = translatedTextSnapshot.value
                                        ?.takeIf { it.first === currentBitmap }?.second,
                                    onClose = { finish() },
                                    copyTextManager = copyTextManager.value,
                                    onExitCopyMode = {
                                        copyTextManager.value?.disposeSilently()
                                        copyTextManager.value = null
                                    },
                                    onTranslate = {
                                        val targetLang = UIPreferences(this@CameraSearchActivity).getTargetTranslateLang()
                                        translateCurrentPhoto(targetLang)
                                    },
                                    onTextAnalysisUpdate = { source, nodes, analysisComplete ->
                                        if (source === photoBitmap.value) {
                                            translationTextCoordinator.publish(
                                                nodes = nodes.map { node ->
                                                    ScreenTranslationNode(
                                                        text = node.fullText,
                                                        left = node.bounds.left,
                                                        top = node.bounds.top,
                                                        right = node.bounds.right,
                                                        bottom = node.bounds.bottom,
                                                        sourceNode = node,
                                                    )
                                                },
                                                analysisComplete = analysisComplete,
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        if (isLoadingPhoto.value) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Loading photo...", color = Color.White)
                                }
                            }
                        }

                        if (isTranslating.value) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {},
                                contentAlignment = Alignment.Center,
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

        if (savedInstanceState == null) {
            launchCamera()
        } else {
            when (val action = sessionPolicy.getRestorationAction()) {
                is CameraPhotoSessionPolicy.RestorationAction.ResumeProcessing -> {
                    loadAndDisplayPhoto(action.filePath, action.generation)
                }
                is CameraPhotoSessionPolicy.RestorationAction.RestoreViewing -> {
                    loadAndDisplayPhoto(action.filePath, action.generation)
                }
                is CameraPhotoSessionPolicy.RestorationAction.WaitForCameraResult -> {
                    // Retain state and wait for takePictureLauncher callback
                }
                is CameraPhotoSessionPolicy.RestorationAction.RelaunchCamera -> {
                    val stalePending = sessionPolicy.pendingCaptureFilePath
                    sessionPolicy.resetPreparingForRestart()
                    CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                        sessionId,
                        sessionPolicy.getFilesToPreserve(),
                    )
                    if (stalePending != null) {
                        CameraPhotoFileManager.deleteFileAsync(stalePending)
                    }
                    launchCamera(isRestart = true)
                }
                is CameraPhotoSessionPolicy.RestorationAction.FinishSession -> {
                    isLoadingPhoto.value = false
                    deferredDecode = null
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SESSION_ID, sessionId)
        outState.putString(KEY_PENDING_CAPTURE_PATH, sessionPolicy.pendingCaptureFilePath)
        outState.putString(KEY_ACTIVE_PHOTO_PATH, sessionPolicy.activePhotoFilePath)
        outState.putLong(KEY_SESSION_GENERATION, sessionPolicy.currentGeneration)
        outState.putString(KEY_SESSION_PHASE, sessionPolicy.phase.name)
        currentCameraUri?.let { outState.putString(KEY_CURRENT_CAMERA_URI, it.toString()) }
    }

    private fun onViewportMeasured(width: Int, height: Int) {
        val oldSize = measuredViewportSize
        measuredViewportSize = Pair(width, height)

        val sizeChanged = oldSize != null && (oldSize.first != width || oldSize.second != height)
        if (sizeChanged) {
            if (isTranslating.value || translationJob != null) {
                translationJob?.cancel()
                translationJob = null
                isTranslating.value = false
            }
        }

        if (!sessionPolicy.shouldScheduleViewportDecode()) {
            return
        }

        val pending = deferredDecode
        if (pending != null) {
            deferredDecode = null
            loadAndDisplayPhoto(pending.filePath, pending.generation, width, height)
        } else if (sizeChanged) {
            val pathToLoad = sessionPolicy.getPhotoPathForViewportDecode()
            if (pathToLoad != null) {
                loadAndDisplayPhoto(pathToLoad, sessionPolicy.currentGeneration, width, height)
            }
        }
    }

    private fun launchCamera(isRestart: Boolean = false) {
        if (!sessionPolicy.canLaunchCamera(isRestart)) {
            return
        }
        sessionPolicy.markPreparing()
        CameraPhotoFileManager.updateLiveSessionPreservedPaths(
            sessionId,
            sessionPolicy.getFilesToPreserve(),
        )

        lifecycleScope.launch {
            var createdFile: File? = null
            var uri: Uri? = null
            var isDelivered = false
            try {
                val prepared = CameraPhotoFileManager.preparePendingCapture(
                    context = this@CameraSearchActivity,
                    onFileCreated = { file ->
                        createdFile = file
                    },
                )
                createdFile = prepared.file
                uri = prepared.uri
                currentCameraUri = uri

                sessionPolicy.prepareCapture(prepared.file.absolutePath)
                sessionPolicy.markCameraLaunched()
                CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                    sessionId,
                    sessionPolicy.getFilesToPreserve(),
                )

                takePictureLauncher.launch(uri)
                isDelivered = true
            } catch (e: CancellationException) {
                currentCameraUri = null
                revokeCameraUri(uri)
                throw e
            } catch (e: ActivityNotFoundException) {
                currentCameraUri = null
                revokeCameraUri(uri)
                Toast.makeText(this@CameraSearchActivity, "No camera app found", Toast.LENGTH_SHORT).show()
                val action = sessionPolicy.onLaunchFailed(createdFile?.absolutePath)
                CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                    sessionId,
                    sessionPolicy.getFilesToPreserve(),
                )
                CameraPhotoFileManager.deleteFileAsync(action.fileToDelete)
                if (action.shouldFinish) {
                    isLoadingPhoto.value = false
                    deferredDecode = null
                    finish()
                }
            } catch (e: Exception) {
                currentCameraUri = null
                revokeCameraUri(uri)
                android.util.Log.e("CameraSearchActivity", "Failed to launch camera", e)
                Toast.makeText(this@CameraSearchActivity, "Could not start camera", Toast.LENGTH_SHORT).show()
                val action = sessionPolicy.onLaunchFailed(createdFile?.absolutePath)
                CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                    sessionId,
                    sessionPolicy.getFilesToPreserve(),
                )
                CameraPhotoFileManager.deleteFileAsync(action.fileToDelete)
                if (action.shouldFinish) {
                    isLoadingPhoto.value = false
                    deferredDecode = null
                    finish()
                }
            } finally {
                if (!isDelivered) {
                    val fileToClean = createdFile
                    if (fileToClean != null) {
                        CameraPhotoFileManager.deleteFileAsync(fileToClean.absolutePath)
                    }
                }
            }
        }
    }

    private fun revokeCameraUri(uri: Uri?) {
        if (uri == null) return
        try {
            revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            android.util.Log.w("CameraSearchActivity", "Error revoking camera URI grant", e)
        }
    }

    private fun loadAndDisplayPhoto(
        filePath: String,
        generation: Long,
        viewportWidth: Int = 0,
        viewportHeight: Int = 0,
    ) {
        val (vpWidth, vpHeight) = if (viewportWidth > 0 && viewportHeight > 0) {
            Pair(viewportWidth, viewportHeight)
        } else {
            val vp = measuredViewportSize
            if (vp == null) {
                deferredDecode = DeferredDecodeRequest(filePath, generation)
                isLoadingPhoto.value = true
                return
            }
            vp
        }

        deferredDecode = null
        isLoadingPhoto.value = true
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            var decodedBitmap: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    CameraPhotoFileManager.markCaptureCompleted(filePath)
                }

                decodedBitmap = CameraPhotoProcessor.processPhoto(
                    photoFile = File(filePath),
                    viewportWidth = vpWidth,
                    viewportHeight = vpHeight,
                )

                if (!isActive || isFinishing || isDestroyed) {
                    decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                    decodedBitmap = null
                    return@launch
                }

                val currentSize = measuredViewportSize
                if (currentSize != null && (currentSize.first != vpWidth || currentSize.second != vpHeight)) {
                    decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                    decodedBitmap = null
                    loadAndDisplayPhoto(filePath, generation, currentSize.first, currentSize.second)
                    return@launch
                }

                when (val action = sessionPolicy.onDecodeSuccess(generation, filePath)) {
                    is CameraPhotoSessionPolicy.DecodeResultAction.ApplySuccess -> {
                        CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                            sessionId,
                            sessionPolicy.getFilesToPreserve(),
                        )
                        action.previousFileToPrune?.let { CameraPhotoFileManager.deleteFileAsync(it) }
                        translatedTextSnapshot.value = null
                        photoBitmap.value = decodedBitmap
                        replaceCopyTextManager(decodedBitmap)
                        decodedBitmap = null
                    }
                    is CameraPhotoSessionPolicy.DecodeResultAction.StaleWork -> {
                        decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                        decodedBitmap = null
                    }
                    else -> {
                        decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                        decodedBitmap = null
                    }
                }
            } catch (e: CancellationException) {
                decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                throw e
            } catch (e: Exception) {
                decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                android.util.Log.e("CameraSearchActivity", "Error loading photo", e)
                Toast.makeText(this@CameraSearchActivity, "Failed to load captured photo", Toast.LENGTH_SHORT).show()

                when (val action = sessionPolicy.onDecodeFailure(generation, filePath)) {
                    is CameraPhotoSessionPolicy.DecodeResultAction.FailureFinishSession -> {
                        CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                            sessionId,
                            sessionPolicy.getFilesToPreserve(),
                        )
                        CameraPhotoFileManager.deleteFileAsync(action.failedFileToDelete)
                        isLoadingPhoto.value = false
                        deferredDecode = null
                        finish()
                    }
                    is CameraPhotoSessionPolicy.DecodeResultAction.FailureRetainActive -> {
                        CameraPhotoFileManager.updateLiveSessionPreservedPaths(
                            sessionId,
                            sessionPolicy.getFilesToPreserve(),
                        )
                        CameraPhotoFileManager.deleteFileAsync(action.failedFileToDelete)
                    }
                    else -> {}
                }
            } finally {
                if (loadJob === coroutineContext[Job]) {
                    isLoadingPhoto.value = false
                    loadJob = null
                }
            }
        }
    }

    private fun translateCurrentPhoto(targetLangCode: String? = null) {
        val currentBitmap = photoBitmap.value ?: return

        if (currentBitmap.isRecycled) {
            Toast.makeText(this, "Image is no longer available", Toast.LENGTH_SHORT).show()
            return
        }

        if (isTranslating.value) return
        isTranslating.value = true

        translationJob = lifecycleScope.launch {
            var translatedBitmap: Bitmap? = null
            try {
                val ocrWaitStartedAt = SystemClock.elapsedRealtime()
                val recognizedNodes = translationTextCoordinator.awaitSnapshot()
                android.util.Log.d(
                    "CameraSearchActivity",
                    "Translation waited ${SystemClock.elapsedRealtime() - ocrWaitStartedAt}ms " +
                        "for shared OCR; nodes=${recognizedNodes.size}",
                )

                val outcome = ScreenTranslator(applicationContext).use { translator ->
                    translator.translateScreen(
                        screenshot = currentBitmap,
                        textNodes = recognizedNodes,
                        targetLangCode = targetLangCode,
                    )
                }

                when (outcome) {
                    is ScreenTranslationOutcome.Unchanged -> {
                        if (isActive && photoBitmap.value === currentBitmap && !isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this@CameraSearchActivity,
                                outcome.reason.userMessage(),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        return@launch
                    }
                    is ScreenTranslationOutcome.Translated -> {
                        translatedBitmap = outcome.bitmap
                    }
                }

                if (!isActive || photoBitmap.value !== currentBitmap || isFinishing || isDestroyed) {
                    translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                    translatedBitmap = null
                    return@launch
                }

                val completedBitmap = checkNotNull(translatedBitmap)
                translatedTextSnapshot.value = completedBitmap to outcome.textNodes
                photoBitmap.value = completedBitmap
                replaceCopyTextManager(completedBitmap)
                translatedBitmap = null

                outcome.userMessage()?.let { message ->
                    Toast.makeText(this@CameraSearchActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (error: CancellationException) {
                translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                throw error
            } catch (e: Exception) {
                translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                android.util.Log.e("CameraSearchActivity", "Translation failed", e)
                Toast.makeText(this@CameraSearchActivity, e.message ?: "Translation failed", Toast.LENGTH_LONG).show()
            } finally {
                if (translationJob === coroutineContext[Job]) {
                    isTranslating.value = false
                    translationJob = null
                }
            }
        }
    }

    private fun replaceCopyTextManager(bitmap: Bitmap?) {
        translationTextCoordinator.cancel()
        translationTextCoordinator = ScreenTranslationTextCoordinator()
        copyTextManager.value?.disposeSilently()
        copyTextManager.value = CopyTextOverlayManager(
            context = this,
            screenshotBitmap = bitmap,
        )
    }

    override fun onDestroy() {
        loadJob?.cancel()
        loadJob = null
        translationJob?.cancel()
        translationJob = null
        translationTextCoordinator.cancel()
        isTranslating.value = false
        isLoadingPhoto.value = false
        deferredDecode = null

        copyTextManager.value?.disposeSilently()
        copyTextManager.value = null

        if (isFinishing) {
            translatedTextSnapshot.value = null
            CameraPhotoFileManager.unregisterLiveSession(sessionId)
            val toDelete = sessionPolicy.onFinish()
            CameraPhotoFileManager.performFinishingCleanup(
                context = applicationContext,
                filesToDelete = toDelete,
                preservePaths = sessionPolicy.getFilesToPreserve(),
            )
        }
        translatedTextSnapshot.value = null
        photoBitmap.value = null
        super.onDestroy()
    }
}
