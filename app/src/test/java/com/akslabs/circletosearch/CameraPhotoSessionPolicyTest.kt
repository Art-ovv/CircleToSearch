/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhotoSessionPolicyTest {

    @Test
    fun initialSessionStateIsEmptyAndIdle() {
        val policy = CameraPhotoSessionPolicy()
        assertNull(policy.pendingCaptureFilePath)
        assertNull(policy.activePhotoFilePath)
        assertEquals(0L, policy.currentGeneration)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, policy.phase)
        assertTrue(policy.getFilesToPreserve().isEmpty())
    }

    @Test
    fun prepareCaptureSetsPendingPathAndIncrementsGeneration() {
        val policy = CameraPhotoSessionPolicy()
        val generation = policy.prepareCapture("/cache/capture1.jpg")

        assertEquals(1L, generation)
        assertEquals(1L, policy.currentGeneration)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.PREPARING, policy.phase)
        assertEquals("/cache/capture1.jpg", policy.pendingCaptureFilePath)
        assertEquals(setOf("/cache/capture1.jpg"), policy.getFilesToPreserve())
    }

    @Test
    fun markCameraLaunchedTransitionsToCameraInFlight() {
        val policy = CameraPhotoSessionPolicy()
        policy.prepareCapture("/cache/capture1.jpg")
        policy.markCameraLaunched()

        assertEquals(CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT, policy.phase)
    }

    @Test
    fun captureSuccessTransitionsToProcessing() {
        val policy = CameraPhotoSessionPolicy()
        val generation = policy.prepareCapture("/cache/capture1.jpg")
        policy.markCameraLaunched()

        val action = policy.onCaptureResult(success = true, fileExistsAndNotEmpty = true)
        assertTrue(action is CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode)
        val proceed = action as CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode
        assertEquals(generation, proceed.generation)
        assertEquals("/cache/capture1.jpg", proceed.filePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.PROCESSING, policy.phase)
        assertEquals("/cache/capture1.jpg", policy.pendingCaptureFilePath)
    }

    @Test
    fun regressionRestorationDuringProcessingResumesDecode() {
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/captured_pending.jpg",
            initialActivePath = null,
            initialGeneration = 7L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PROCESSING,
        )

        val restoration = policy.getRestorationAction()
        assertTrue(restoration is CameraPhotoSessionPolicy.RestorationAction.ResumeProcessing)
        val resumeAction = restoration as CameraPhotoSessionPolicy.RestorationAction.ResumeProcessing
        assertEquals("/cache/captured_pending.jpg", resumeAction.filePath)
        assertEquals(7L, resumeAction.generation)
    }

    @Test
    fun restorationDuringCameraInFlightWaitsForCamera() {
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/camera_active.jpg",
            initialGeneration = 3L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )

        val restoration = policy.getRestorationAction()
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.WaitForCameraResult, restoration)
    }

    @Test
    fun restorationDuringViewingRestoresActivePhoto() {
        val policy = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/viewing.jpg",
            initialGeneration = 4L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.VIEWING,
        )

        val restoration = policy.getRestorationAction()
        assertTrue(restoration is CameraPhotoSessionPolicy.RestorationAction.RestoreViewing)
        val restoreAction = restoration as CameraPhotoSessionPolicy.RestorationAction.RestoreViewing
        assertEquals("/cache/viewing.jpg", restoreAction.filePath)
        assertEquals(4L, restoreAction.generation)
    }

    @Test
    fun restorationDuringPreparingRelaunchesCamera() {
        val policy = CameraPhotoSessionPolicy(
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PREPARING,
        )

        val restoration = policy.getRestorationAction()
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.RelaunchCamera, restoration)
    }

    @Test
    fun emptyRestoredStateFinishesSession() {
        val policy = CameraPhotoSessionPolicy(
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.IDLE,
        )

        val restoration = policy.getRestorationAction()
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.FinishSession, restoration)
    }

    @Test
    fun captureCancellationWithoutActivePhotoFinishesSession() {
        val policy = CameraPhotoSessionPolicy()
        policy.prepareCapture("/cache/capture1.jpg")

        val action = policy.onCaptureResult(success = false, fileExistsAndNotEmpty = false)
        assertTrue(action is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession)
        val finishAction = action as CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession
        assertEquals("/cache/capture1.jpg", finishAction.fileToDelete)
        assertNull(policy.pendingCaptureFilePath)
        assertNull(policy.activePhotoFilePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, policy.phase)
    }

    @Test
    fun captureCancellationWithActivePhotoRetainsActive() {
        val policy = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/active.jpg",
            initialGeneration = 5L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.VIEWING,
        )
        policy.prepareCapture("/cache/capture2.jpg")

        val action = policy.onCaptureResult(success = false, fileExistsAndNotEmpty = false)
        assertTrue(action is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingRetainActive)
        val retainAction = action as CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingRetainActive
        assertEquals("/cache/capture2.jpg", retainAction.fileToDelete)
        assertNull(policy.pendingCaptureFilePath)
        assertEquals("/cache/active.jpg", policy.activePhotoFilePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.VIEWING, policy.phase)
    }

    @Test
    fun launchFailureHandlesTerminalAndNonTerminalPaths() {
        val freshPolicy = CameraPhotoSessionPolicy()
        freshPolicy.prepareCapture("/cache/fail1.jpg")
        val fail1 = freshPolicy.onLaunchFailed("/cache/fail1.jpg")
        assertTrue(fail1.shouldFinish)
        assertEquals("/cache/fail1.jpg", fail1.fileToDelete)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, freshPolicy.phase)

        val viewingPolicy = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/good.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.VIEWING,
        )
        viewingPolicy.prepareCapture("/cache/fail2.jpg")
        val fail2 = viewingPolicy.onLaunchFailed("/cache/fail2.jpg")
        assertFalse(fail2.shouldFinish)
        assertEquals("/cache/fail2.jpg", fail2.fileToDelete)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.VIEWING, viewingPolicy.phase)
    }

    @Test
    fun decodeSuccessPromotesToActiveAndPrunesPreviousFile() {
        val policy = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/old_photo.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.VIEWING,
        )
        val generation = policy.prepareCapture("/cache/new_photo.jpg")

        val decodeAction = policy.onDecodeSuccess(generation, "/cache/new_photo.jpg")
        assertTrue(decodeAction is CameraPhotoSessionPolicy.DecodeResultAction.ApplySuccess)
        val applySuccess = decodeAction as CameraPhotoSessionPolicy.DecodeResultAction.ApplySuccess
        assertEquals("/cache/old_photo.jpg", applySuccess.previousFileToPrune)
        assertEquals("/cache/new_photo.jpg", policy.activePhotoFilePath)
        assertNull(policy.pendingCaptureFilePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.VIEWING, policy.phase)
    }

    @Test
    fun decodeSuccessRejectsStaleGeneration() {
        val policy = CameraPhotoSessionPolicy()
        val generation1 = policy.prepareCapture("/cache/photo1.jpg")
        policy.prepareCapture("/cache/photo2.jpg")

        val decodeAction = policy.onDecodeSuccess(generation1, "/cache/photo1.jpg")
        assertEquals(CameraPhotoSessionPolicy.DecodeResultAction.StaleWork, decodeAction)
        assertNull(policy.activePhotoFilePath)
        assertEquals("/cache/photo2.jpg", policy.pendingCaptureFilePath)
    }

    @Test
    fun decodeFailureHandlesTerminalAndNonTerminalPaths() {
        val policyWithoutActive = CameraPhotoSessionPolicy()
        val gen1 = policyWithoutActive.prepareCapture("/cache/bad1.jpg")
        val failAction1 = policyWithoutActive.onDecodeFailure(gen1, "/cache/bad1.jpg")
        assertTrue(failAction1 is CameraPhotoSessionPolicy.DecodeResultAction.FailureFinishSession)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, policyWithoutActive.phase)

        val policyWithActive = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/good.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.VIEWING,
        )
        val gen2 = policyWithActive.prepareCapture("/cache/bad2.jpg")
        val failAction2 = policyWithActive.onDecodeFailure(gen2, "/cache/bad2.jpg")
        assertTrue(failAction2 is CameraPhotoSessionPolicy.DecodeResultAction.FailureRetainActive)
        assertEquals("/cache/good.jpg", policyWithActive.activePhotoFilePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.VIEWING, policyWithActive.phase)
    }

    @Test
    fun finishSessionPreservesPendingFileIfCameraInFlight() {
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/pending.jpg",
            initialActivePath = "/cache/active.jpg",
            initialGeneration = 10L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )

        // Pending file must not be returned for immediate deletion if camera may still be writing
        val toDelete = policy.onFinish()
        assertEquals(setOf("/cache/active.jpg"), toDelete)
        assertNull(policy.pendingCaptureFilePath)
        assertNull(policy.activePhotoFilePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, policy.phase)
    }

    @Test
    fun finishSessionDeletesPendingIfProcessing() {
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/pending.jpg",
            initialActivePath = "/cache/active.jpg",
            initialGeneration = 10L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PROCESSING,
        )

        val toDelete = policy.onFinish()
        assertEquals(setOf("/cache/active.jpg", "/cache/pending.jpg"), toDelete)
        assertNull(policy.pendingCaptureFilePath)
        assertNull(policy.activePhotoFilePath)
    }

    @Test
    fun preparationRestartActualGuardTest() {
        val policy = CameraPhotoSessionPolicy()
        assertTrue(policy.canLaunchCamera(isRestart = false))
        assertTrue(policy.canLaunchCamera(isRestart = true))

        policy.markPreparing()
        // In PREPARING phase: isRestart=false is guarded/blocked, isRestart=true is permitted
        assertFalse(policy.canLaunchCamera(isRestart = false))
        assertTrue(policy.canLaunchCamera(isRestart = true))

        // resetPreparingForRestart resets phase to IDLE when there is no active photo
        assertTrue(policy.resetPreparingForRestart())
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, policy.phase)
        assertNull(policy.pendingCaptureFilePath)

        // With active photo present:
        val activePolicy = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/viewing.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PREPARING,
        )
        assertFalse(activePolicy.canLaunchCamera(isRestart = false))
        assertTrue(activePolicy.canLaunchCamera(isRestart = true))
        assertTrue(activePolicy.resetPreparingForRestart())
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.VIEWING, activePolicy.phase)

        // When in CAMERA_IN_FLIGHT or PROCESSING, cannot launch even with restart
        val inFlightPolicy = CameraPhotoSessionPolicy(
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )
        assertFalse(inFlightPolicy.canLaunchCamera(isRestart = false))
        assertFalse(inFlightPolicy.canLaunchCamera(isRestart = true))
        assertFalse(inFlightPolicy.resetPreparingForRestart())

        val processingPolicy = CameraPhotoSessionPolicy(
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PROCESSING,
        )
        assertFalse(processingPolicy.canLaunchCamera(isRestart = false))
        assertFalse(processingPolicy.canLaunchCamera(isRestart = true))
        assertFalse(processingPolicy.resetPreparingForRestart())
    }

    @Test
    fun processRestorationCaptureCallbackTest() {
        // Activity recreated after process death while camera was in flight
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/restored_capture.jpg",
            initialActivePath = null,
            initialGeneration = 42L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )

        // Restoration action confirms waiting for camera callback
        val restoration = policy.getRestorationAction()
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.WaitForCameraResult, restoration)

        // Synchronous camera activity result callback arrives with success
        val action = policy.onCaptureResult(success = true, fileExistsAndNotEmpty = true)

        // Synchronous state transition immediately to PROCESSING
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.PROCESSING, policy.phase)
        assertTrue(action is CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode)
        val proceed = action as CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode
        assertEquals(42L, proceed.generation)
        assertEquals("/cache/restored_capture.jpg", proceed.filePath)
        assertEquals("/cache/restored_capture.jpg", policy.pendingCaptureFilePath)
        assertEquals(42L, policy.currentGeneration)

        // Test failure case after process restoration
        val failPolicy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/restored_fail.jpg",
            initialActivePath = null,
            initialGeneration = 42L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )
        val failAction = failPolicy.onCaptureResult(success = false, fileExistsAndNotEmpty = false)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, failPolicy.phase)
        assertTrue(failAction is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession)
        val discard = failAction as CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession
        assertEquals("/cache/restored_fail.jpg", discard.fileToDelete)
        assertNull(failPolicy.pendingCaptureFilePath)
    }

    @Test
    fun viewportDecodeScheduledOnlyInProcessingOrViewing() {
        val idlePolicy = CameraPhotoSessionPolicy(initialPhase = CameraPhotoSessionPolicy.SessionPhase.IDLE)
        assertFalse(idlePolicy.shouldScheduleViewportDecode())
        assertNull(idlePolicy.getPhotoPathForViewportDecode())

        val preparingPolicy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/preparing.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PREPARING,
        )
        assertFalse(preparingPolicy.shouldScheduleViewportDecode())
        assertNull(preparingPolicy.getPhotoPathForViewportDecode())

        val inFlightPolicy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/in_flight.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )
        assertFalse(inFlightPolicy.shouldScheduleViewportDecode())
        assertNull(inFlightPolicy.getPhotoPathForViewportDecode())

        val processingPolicy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/processing.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.PROCESSING,
        )
        assertTrue(processingPolicy.shouldScheduleViewportDecode())
        assertEquals("/cache/processing.jpg", processingPolicy.getPhotoPathForViewportDecode())

        val viewingPolicy = CameraPhotoSessionPolicy(
            initialActivePath = "/cache/viewing.jpg",
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.VIEWING,
        )
        assertTrue(viewingPolicy.shouldScheduleViewportDecode())
        assertEquals("/cache/viewing.jpg", viewingPolicy.getPhotoPathForViewportDecode())
    }
}
