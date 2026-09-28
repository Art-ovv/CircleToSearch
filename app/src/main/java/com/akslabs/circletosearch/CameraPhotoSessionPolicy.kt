/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import java.util.concurrent.atomic.AtomicLong

class CameraPhotoSessionPolicy(
    initialPendingPath: String? = null,
    initialActivePath: String? = null,
    initialGeneration: Long = 0L,
    initialPhase: SessionPhase = SessionPhase.IDLE,
) {
    enum class SessionPhase {
        IDLE,
        PREPARING,
        CAMERA_IN_FLIGHT,
        PROCESSING,
        VIEWING,
    }

    private val generationCounter = AtomicLong(initialGeneration)

    var phase: SessionPhase = initialPhase
        private set

    var pendingCaptureFilePath: String? = initialPendingPath
        private set

    var activePhotoFilePath: String? = initialActivePath
        private set

    val currentGeneration: Long
        get() = generationCounter.get()

    sealed interface RestorationAction {
        data class ResumeProcessing(val filePath: String, val generation: Long) : RestorationAction
        data object WaitForCameraResult : RestorationAction
        data class RestoreViewing(val filePath: String, val generation: Long) : RestorationAction
        data object RelaunchCamera : RestorationAction
        data object FinishSession : RestorationAction
    }

    sealed interface CaptureResultAction {
        data class ProceedToDecode(val generation: Long, val filePath: String) : CaptureResultAction
        data class DiscardPendingRetainActive(val fileToDelete: String?) : CaptureResultAction
        data class DiscardPendingFinishSession(val fileToDelete: String?) : CaptureResultAction
    }

    sealed interface DecodeResultAction {
        data class ApplySuccess(val previousFileToPrune: String?) : DecodeResultAction
        data object StaleWork : DecodeResultAction
        data class FailureRetainActive(val failedFileToDelete: String?) : DecodeResultAction
        data class FailureFinishSession(val failedFileToDelete: String?) : DecodeResultAction
    }

    data class LaunchFailureAction(
        val fileToDelete: String?,
        val shouldFinish: Boolean,
    )

    fun getRestorationAction(): RestorationAction = when (phase) {
        SessionPhase.PROCESSING -> {
            val pending = pendingCaptureFilePath
            if (pending != null) {
                RestorationAction.ResumeProcessing(pending, currentGeneration)
            } else {
                val active = activePhotoFilePath
                if (active != null) RestorationAction.RestoreViewing(active, currentGeneration)
                else RestorationAction.FinishSession
            }
        }
        SessionPhase.CAMERA_IN_FLIGHT -> {
            if (pendingCaptureFilePath != null) {
                RestorationAction.WaitForCameraResult
            } else {
                RestorationAction.FinishSession
            }
        }
        SessionPhase.VIEWING -> {
            val active = activePhotoFilePath
            if (active != null) {
                RestorationAction.RestoreViewing(active, currentGeneration)
            } else {
                RestorationAction.FinishSession
            }
        }
        SessionPhase.PREPARING -> {
            RestorationAction.RelaunchCamera
        }
        SessionPhase.IDLE -> {
            val active = activePhotoFilePath
            if (active != null) {
                RestorationAction.RestoreViewing(active, currentGeneration)
            } else {
                RestorationAction.FinishSession
            }
        }
    }

    fun shouldScheduleViewportDecode(): Boolean = when (phase) {
        SessionPhase.PROCESSING, SessionPhase.VIEWING -> true
        SessionPhase.IDLE, SessionPhase.PREPARING, SessionPhase.CAMERA_IN_FLIGHT -> false
    }

    fun getPhotoPathForViewportDecode(): String? = when (phase) {
        SessionPhase.VIEWING -> activePhotoFilePath
        SessionPhase.PROCESSING -> pendingCaptureFilePath ?: activePhotoFilePath
        SessionPhase.IDLE, SessionPhase.PREPARING, SessionPhase.CAMERA_IN_FLIGHT -> null
    }

    fun canLaunchCamera(isRestart: Boolean = false): Boolean = when (phase) {
        SessionPhase.CAMERA_IN_FLIGHT -> false
        SessionPhase.PREPARING -> isRestart
        SessionPhase.PROCESSING -> false
        SessionPhase.IDLE, SessionPhase.VIEWING -> true
    }

    fun resetPreparingForRestart(): Boolean {
        if (phase == SessionPhase.PREPARING) {
            pendingCaptureFilePath = null
            phase = if (activePhotoFilePath != null) SessionPhase.VIEWING else SessionPhase.IDLE
            return true
        }
        return false
    }

    fun markPreparing() {
        phase = SessionPhase.PREPARING
    }

    fun prepareCapture(targetFilePath: String): Long {
        pendingCaptureFilePath = targetFilePath
        phase = SessionPhase.PREPARING
        return generationCounter.incrementAndGet()
    }

    fun markCameraLaunched() {
        phase = SessionPhase.CAMERA_IN_FLIGHT
    }

    fun onLaunchFailed(failedFile: String?): LaunchFailureAction {
        pendingCaptureFilePath = null
        val shouldFinish = activePhotoFilePath == null
        phase = if (shouldFinish) SessionPhase.IDLE else SessionPhase.VIEWING
        return LaunchFailureAction(
            fileToDelete = failedFile,
            shouldFinish = shouldFinish,
        )
    }

    fun onCaptureResult(success: Boolean, fileExistsAndNotEmpty: Boolean = true): CaptureResultAction {
        val pending = pendingCaptureFilePath
        return if (success && fileExistsAndNotEmpty && pending != null) {
            phase = SessionPhase.PROCESSING
            CaptureResultAction.ProceedToDecode(
                generation = generationCounter.get(),
                filePath = pending,
            )
        } else {
            pendingCaptureFilePath = null
            if (activePhotoFilePath != null) {
                phase = SessionPhase.VIEWING
                CaptureResultAction.DiscardPendingRetainActive(fileToDelete = pending)
            } else {
                phase = SessionPhase.IDLE
                CaptureResultAction.DiscardPendingFinishSession(fileToDelete = pending)
            }
        }
    }

    fun onDecodeSuccess(generation: Long, completedFilePath: String): DecodeResultAction {
        if (generation != generationCounter.get()) {
            return DecodeResultAction.StaleWork
        }
        val previousFile = activePhotoFilePath
        activePhotoFilePath = completedFilePath
        if (pendingCaptureFilePath == completedFilePath) {
            pendingCaptureFilePath = null
        }
        phase = SessionPhase.VIEWING
        val toPrune = if (previousFile != null && previousFile != completedFilePath) previousFile else null
        return DecodeResultAction.ApplySuccess(previousFileToPrune = toPrune)
    }

    fun onDecodeFailure(generation: Long, failedFilePath: String): DecodeResultAction {
        if (generation != generationCounter.get()) {
            return DecodeResultAction.StaleWork
        }
        if (pendingCaptureFilePath == failedFilePath) {
            pendingCaptureFilePath = null
        }
        return if (activePhotoFilePath != null && activePhotoFilePath != failedFilePath) {
            phase = SessionPhase.VIEWING
            DecodeResultAction.FailureRetainActive(failedFileToDelete = failedFilePath)
        } else {
            if (activePhotoFilePath == failedFilePath) {
                activePhotoFilePath = null
            }
            phase = SessionPhase.IDLE
            DecodeResultAction.FailureFinishSession(failedFileToDelete = failedFilePath)
        }
    }

    fun onFinish(): Set<String> {
        val filesToDelete = mutableSetOf<String>()
        activePhotoFilePath?.let(filesToDelete::add)
        // If the external camera may still be writing, preserve the pending file
        if (phase != SessionPhase.CAMERA_IN_FLIGHT) {
            pendingCaptureFilePath?.let(filesToDelete::add)
        }
        pendingCaptureFilePath = null
        activePhotoFilePath = null
        phase = SessionPhase.IDLE
        generationCounter.incrementAndGet()
        return filesToDelete
    }

    fun getFilesToPreserve(): Set<String> = buildSet {
        pendingCaptureFilePath?.let(::add)
        activePhotoFilePath?.let(::add)
    }
}
