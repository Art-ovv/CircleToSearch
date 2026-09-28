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
    fun captureFlowTransitionsThroughPreparingAndInFlightToProcessing() {
        val policy = CameraPhotoSessionPolicy()

        // Phase 1: Initial IDLE state
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, policy.phase)
        assertNull(policy.pendingCaptureFilePath)
        assertNull(policy.activePhotoFilePath)
        assertEquals(0L, policy.currentGeneration)
        assertTrue(policy.canLaunchCamera(isRestart = false))
        assertFalse(policy.shouldScheduleViewportDecode())
        assertNull(policy.getPhotoPathForViewportDecode())
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.FinishSession, policy.getRestorationAction())
        assertTrue(policy.getFilesToPreserve().isEmpty())

        // Phase 2: PREPARING state upon capture file allocation
        val capturePath = "/cache/capture1.jpg"
        val generation = policy.prepareCapture(capturePath)
        assertEquals(1L, generation)
        assertEquals(1L, policy.currentGeneration)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.PREPARING, policy.phase)
        assertEquals(capturePath, policy.pendingCaptureFilePath)
        assertEquals(setOf(capturePath), policy.getFilesToPreserve())
        assertFalse("Normal camera launch should be prevented during PREPARING", policy.canLaunchCamera(isRestart = false))
        assertTrue("Restart should be permitted during PREPARING", policy.canLaunchCamera(isRestart = true))
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.RelaunchCamera, policy.getRestorationAction())
        assertFalse(policy.shouldScheduleViewportDecode())

        // Phase 3: CAMERA_IN_FLIGHT when external camera activity is launched
        policy.markCameraLaunched()
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT, policy.phase)
        assertFalse("Camera launch cannot proceed while camera is in flight", policy.canLaunchCamera(isRestart = false))
        assertFalse("Camera restart cannot proceed while camera is in flight", policy.canLaunchCamera(isRestart = true))
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.WaitForCameraResult, policy.getRestorationAction())
        assertFalse(policy.shouldScheduleViewportDecode())
        assertEquals(setOf(capturePath), policy.getFilesToPreserve())

        // Phase 4: PROCESSING upon successful photo capture result
        val action = policy.onCaptureResult(success = true, fileExistsAndNotEmpty = true)
        assertTrue(action is CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode)
        val proceed = action as CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode
        assertEquals(generation, proceed.generation)
        assertEquals(capturePath, proceed.filePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.PROCESSING, policy.phase)
        assertEquals(capturePath, policy.pendingCaptureFilePath)
        assertTrue("Viewport decode must be scheduled during PROCESSING", policy.shouldScheduleViewportDecode())
        assertEquals(capturePath, policy.getPhotoPathForViewportDecode())
        val restoration = policy.getRestorationAction()
        assertTrue(restoration is CameraPhotoSessionPolicy.RestorationAction.ResumeProcessing)
        val resumeAction = restoration as CameraPhotoSessionPolicy.RestorationAction.ResumeProcessing
        assertEquals(capturePath, resumeAction.filePath)
        assertEquals(generation, resumeAction.generation)
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
    fun restorationDuringCameraInFlightHandlesCaptureResult() {
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/camera_active.jpg",
            initialGeneration = 3L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )

        val restoration = policy.getRestorationAction()
        assertEquals(CameraPhotoSessionPolicy.RestorationAction.WaitForCameraResult, restoration)

        val success = policy.onCaptureResult(success = true, fileExistsAndNotEmpty = true)
        assertTrue(success is CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode)
        success as CameraPhotoSessionPolicy.CaptureResultAction.ProceedToDecode
        assertEquals(3L, success.generation)
        assertEquals("/cache/camera_active.jpg", success.filePath)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.PROCESSING, policy.phase)

        val cancelledPolicy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/camera_cancelled.jpg",
            initialGeneration = 4L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )
        val cancelled = cancelledPolicy.onCaptureResult(success = false, fileExistsAndNotEmpty = false)
        assertTrue(cancelled is CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession)
        cancelled as CameraPhotoSessionPolicy.CaptureResultAction.DiscardPendingFinishSession
        assertEquals("/cache/camera_cancelled.jpg", cancelled.fileToDelete)
        assertEquals(CameraPhotoSessionPolicy.SessionPhase.IDLE, cancelledPolicy.phase)
        assertNull(cancelledPolicy.pendingCaptureFilePath)
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
    fun finishSessionDeletesPendingFileIfCameraInFlight() {
        val policy = CameraPhotoSessionPolicy(
            initialPendingPath = "/cache/pending.jpg",
            initialActivePath = "/cache/active.jpg",
            initialGeneration = 10L,
            initialPhase = CameraPhotoSessionPolicy.SessionPhase.CAMERA_IN_FLIGHT,
        )

        val toDelete = policy.onFinish()
        assertEquals(setOf("/cache/active.jpg", "/cache/pending.jpg"), toDelete)
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
