/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CameraPhotoFileManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun pruneNeverDeletesPreservedFiles() {
        val cacheDir = tempFolder.newFolder("cache")
        val cameraDir = File(cacheDir, "camera_search").apply { mkdirs() }
        val fixedNow = 1_700_000_000_000L

        val activeFile = File(cameraDir, "active.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(fixedNow - 100_000_000L)
        }
        val pendingFile = File(cameraDir, "pending.jpg").apply {
            writeBytes(byteArrayOf(4, 5, 6))
            setLastModified(fixedNow - 100_000_000L)
        }
        val staleFile = File(cameraDir, "stale.jpg").apply {
            writeBytes(byteArrayOf(7, 8, 9))
            setLastModified(fixedNow - 100_000_000L)
        }

        CameraPhotoFileManager.pruneDirectory(
            dir = cameraDir,
            preservePaths = setOf(activeFile.absolutePath, pendingFile.absolutePath),
            maxFiles = 0,
            maxAgeMs = 1000L,
            now = fixedNow,
            minInFlightAgeMs = 0L,
        )

        assertTrue(activeFile.exists())
        assertTrue(pendingFile.exists())
        assertFalse(staleFile.exists())
    }

    @Test
    fun pruneRemovesOldestCandidatesWhenExceedingMaxFiles() {
        val cacheDir = tempFolder.newFolder("cache2")
        val cameraDir = File(cacheDir, "camera_search").apply { mkdirs() }
        val fixedNow = 1_700_000_000_000L

        val file1 = File(cameraDir, "file1.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(fixedNow - 30_000L) // oldest
        }
        val file2 = File(cameraDir, "file2.jpg").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(fixedNow - 20_000L)
        }
        val file3 = File(cameraDir, "file3.jpg").apply {
            writeBytes(byteArrayOf(3))
            setLastModified(fixedNow - 10_000L) // newest
        }
        CameraPhotoFileManager.markCaptureCompleted(file1)
        CameraPhotoFileManager.markCaptureCompleted(file2)
        CameraPhotoFileManager.markCaptureCompleted(file3)

        CameraPhotoFileManager.pruneDirectory(
            dir = cameraDir,
            preservePaths = emptySet(),
            maxFiles = 2,
            maxAgeMs = 3600_000L,
            now = fixedNow,
            minInFlightAgeMs = 0L,
        )

        assertFalse(file1.exists())
        assertTrue(file2.exists())
        assertTrue(file3.exists())
    }

    @Test
    fun pruneCountsPreservedFilesTowardsMaxFilesLimit() {
        val cacheDir = tempFolder.newFolder("cache3")
        val cameraDir = File(cacheDir, "camera_search").apply { mkdirs() }
        val fixedNow = 1_700_000_000_000L

        val preserved = File(cameraDir, "preserved.jpg").apply {
            writeBytes(byteArrayOf(0))
            setLastModified(fixedNow - 5_000L)
        }
        val candidateOld = File(cameraDir, "candidate_old.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(fixedNow - 40_000L)
        }
        val candidateNew = File(cameraDir, "candidate_new.jpg").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(fixedNow - 20_000L)
        }
        CameraPhotoFileManager.markCaptureCompleted(candidateOld)
        CameraPhotoFileManager.markCaptureCompleted(candidateNew)

        // maxFiles = 2. Since 1 is preserved, only 1 unpreserved candidate is allowed.
        CameraPhotoFileManager.pruneDirectory(
            dir = cameraDir,
            preservePaths = setOf(preserved.absolutePath),
            maxFiles = 2,
            maxAgeMs = 3600_000L,
            now = fixedNow,
            minInFlightAgeMs = 0L,
        )

        assertTrue(preserved.exists())
        assertFalse(candidateOld.exists())
        assertTrue(candidateNew.exists())
    }

    @Test
    fun pruneProtectsLiveSessionsRegisteredInFileManager() {
        val cacheDir = tempFolder.newFolder("cache4")
        val cameraDir = File(cacheDir, "camera_search").apply { mkdirs() }
        val fixedNow = 1_700_000_000_000L

        val liveSessionFile = File(cameraDir, "live_session.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(fixedNow - 100_000L)
        }
        val abandonedFile = File(cameraDir, "abandoned.jpg").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(fixedNow - 100_000L)
        }

        val sessionId = "test-session-1"
        CameraPhotoFileManager.registerLiveSession(sessionId, setOf(liveSessionFile.absolutePath))

        try {
            CameraPhotoFileManager.pruneDirectory(
                dir = cameraDir,
                preservePaths = emptySet(),
                maxFiles = 0,
                maxAgeMs = 1000L,
                now = fixedNow,
                minInFlightAgeMs = 0L,
            )

            assertTrue(liveSessionFile.exists())
            assertFalse(abandonedFile.exists())
        } finally {
            CameraPhotoFileManager.unregisterLiveSession(sessionId)
        }
    }

    @Test
    fun pruneProtectsRecentInFlightFilesFromCountPruning() {
        val cacheDir = tempFolder.newFolder("cache5")
        val cameraDir = File(cacheDir, "camera_search").apply { mkdirs() }
        val fixedNow = 1_700_000_000_000L

        // File modified 30 seconds ago (in flight / recently written)
        val inFlightFile = File(cameraDir, "in_flight.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(fixedNow - 30_000L)
        }
        val oldAbandonedFile = File(cameraDir, "old_abandoned.jpg").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(fixedNow - 2_000_000L) // 33 minutes old
        }
        CameraPhotoFileManager.markCaptureCompleted(oldAbandonedFile)

        // maxFiles = 0, but maxAge = 24h, minInFlightAgeMs = 15 minutes (900_000 ms)
        CameraPhotoFileManager.pruneDirectory(
            dir = cameraDir,
            preservePaths = emptySet(),
            maxFiles = 0,
            maxAgeMs = 24 * 3600_000L,
            now = fixedNow,
            minInFlightAgeMs = 900_000L,
        )

        // inFlightFile should be protected from count pruning because age < 15 min
        assertTrue(inFlightFile.exists())
        // oldAbandonedFile should be deleted because age >= 15 min and maxFiles = 0
        assertFalse(oldAbandonedFile.exists())
    }

    @Test
    fun countPruningProtectsPendingFilesAndCleansCompletedFilesWithSidecar() {
        val cacheDir = tempFolder.newFolder("cache_sidecar")
        val cameraDir = File(cacheDir, "camera_search").apply { mkdirs() }
        val fixedNow = 1_700_000_000_000L

        // Pending capture: unmarked file (no .complete sidecar marker)
        val pendingFile = File(cameraDir, "capture_pending.jpg").apply {
            writeBytes(byteArrayOf(1, 2))
            setLastModified(fixedNow - 30_000L)
        }

        // Two completed captures (with sidecar marker)
        val oldCompletedFile = File(cameraDir, "capture_old.jpg").apply {
            writeBytes(byteArrayOf(3, 4))
            setLastModified(fixedNow - 20_000L)
        }
        CameraPhotoFileManager.markCaptureCompleted(oldCompletedFile)
        val oldMarker = CameraPhotoFileManager.getCompletionMarkerFile(oldCompletedFile)
        assertTrue(oldMarker.exists())

        val newCompletedFile = File(cameraDir, "capture_new.jpg").apply {
            writeBytes(byteArrayOf(5, 6))
            setLastModified(fixedNow - 10_000L)
        }
        CameraPhotoFileManager.markCaptureCompleted(newCompletedFile)
        val newMarker = CameraPhotoFileManager.getCompletionMarkerFile(newCompletedFile)
        assertTrue(newMarker.exists())

        // maxFiles = 1 -> only 1 completed candidate allowed.
        // Pending capture is skipped from count pruning.
        // oldCompletedFile should be deleted along with its oldMarker.
        // newCompletedFile and pendingFile should survive.
        CameraPhotoFileManager.pruneDirectory(
            dir = cameraDir,
            preservePaths = emptySet(),
            maxFiles = 1,
            maxAgeMs = 3600_000L,
            now = fixedNow,
            minInFlightAgeMs = 0L,
        )

        assertTrue("Pending capture should be protected from count pruning", pendingFile.exists())
        assertFalse("Old completed capture should be count-pruned", oldCompletedFile.exists())
        assertFalse("Old completion sidecar marker should be cleaned alongside photo", oldMarker.exists())
        assertTrue("New completed capture should be retained", newCompletedFile.exists())
        assertTrue("New completion sidecar marker should be retained", newMarker.exists())
    }
}
