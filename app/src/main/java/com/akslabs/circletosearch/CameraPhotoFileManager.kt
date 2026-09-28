/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CameraPhotoFileManager {
    private const val TAG = "CameraPhotoFileManager"
    private const val CAMERA_SUBDIR = "camera_search"
    const val DEFAULT_MAX_TEMP_FILES = 3
    const val DEFAULT_MAX_FILE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    const val DEFAULT_MIN_IN_FLIGHT_AGE_MS = 15 * 60 * 1000L // 15 minutes

    const val SUFFIX_PHOTO = ".jpg"
    const val SUFFIX_COMPLETED_MARKER = ".complete"
    const val PREFIX_CAPTURE = "capture_"

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveSessions = ConcurrentHashMap<String, Set<String>>()

    data class PreparedPendingCapture(
        val file: File,
        val uri: Uri,
    )

    fun updateLiveSessionPreservedPaths(sessionId: String, preservedPaths: Set<String>) {
        liveSessions[sessionId] = preservedPaths
    }

    fun registerLiveSession(sessionId: String, preservedPaths: Set<String>) {
        updateLiveSessionPreservedPaths(sessionId, preservedPaths)
    }

    fun unregisterLiveSession(sessionId: String) {
        liveSessions.remove(sessionId)
    }

    fun getAllPreservedPaths(): Set<String> {
        val result = mutableSetOf<String>()
        liveSessions.values.forEach { paths ->
            result.addAll(paths)
        }
        return result
    }

    fun getCompletionMarkerFile(photoFile: File): File =
        File(photoFile.parentFile, "${photoFile.name}$SUFFIX_COMPLETED_MARKER")

    fun isManagedPhotoFile(file: File): Boolean =
        file.isFile && file.name.endsWith(SUFFIX_PHOTO)

    fun isCaptureCompleted(file: File): Boolean =
        getCompletionMarkerFile(file).exists()

    fun isPendingCapture(file: File): Boolean =
        isManagedPhotoFile(file) && !isCaptureCompleted(file)

    fun markCaptureCompleted(file: File) {
        try {
            val marker = getCompletionMarkerFile(file)
            if (!marker.exists()) {
                marker.createNewFile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to mark capture completed", e)
        }
    }

    fun markCaptureCompleted(path: String?) {
        if (path == null) return
        markCaptureCompleted(File(path))
    }

    fun getCameraDirectory(context: Context): File {
        val dir = File(context.cacheDir, CAMERA_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun preparePendingCapture(
        context: Context,
        onFileCreated: ((File) -> Unit)? = null,
    ): PreparedPendingCapture = withContext(Dispatchers.IO) {
        val dir = getCameraDirectory(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create camera capture directory")
        }
        val filename = "${PREFIX_CAPTURE}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}$SUFFIX_PHOTO"
        val file = File(dir, filename)
        if (!file.createNewFile()) {
            throw IOException("Could not pre-create capture file")
        }
        onFileCreated?.invoke(file)
        val uri = getUriForFile(context, file)
        PreparedPendingCapture(file, uri)
    }

    fun getUriForFile(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun deleteFileAsync(path: String?) {
        if (path == null) return
        cleanupScope.launch {
            deleteFileSync(path)
        }
    }

    suspend fun deleteFileQuietly(path: String?) = withContext(Dispatchers.IO) {
        deleteFileSync(path)
    }

    fun deleteFileSync(path: String?) {
        if (path == null) return
        try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
            val marker = getCompletionMarkerFile(file)
            if (marker.exists()) {
                marker.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to delete temporary file quietly", e)
        }
    }

    fun performFinishingCleanup(
        context: Context,
        filesToDelete: Set<String>,
        preservePaths: Set<String>,
    ) {
        val appContext = context.applicationContext
        cleanupScope.launch {
            filesToDelete.forEach { deleteFileSync(it) }
            pruneOldFiles(appContext, preservePaths)
        }
    }

    suspend fun pruneOldFiles(
        context: Context,
        preservePaths: Set<String>,
        maxFiles: Int = DEFAULT_MAX_TEMP_FILES,
        maxAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS,
        now: Long = System.currentTimeMillis(),
        minInFlightAgeMs: Long = DEFAULT_MIN_IN_FLIGHT_AGE_MS,
    ) = withContext(Dispatchers.IO) {
        val dir = getCameraDirectory(context)
        pruneDirectory(dir, preservePaths, maxFiles, maxAgeMs, now, minInFlightAgeMs)
    }

    fun pruneDirectory(
        dir: File,
        preservePaths: Set<String>,
        maxFiles: Int = DEFAULT_MAX_TEMP_FILES,
        maxAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS,
        now: Long = System.currentTimeMillis(),
        minInFlightAgeMs: Long = DEFAULT_MIN_IN_FLIGHT_AGE_MS,
    ) {
        try {
            val files = dir.listFiles() ?: return
            val allPreserved = preservePaths + getAllPreservedPaths()

            val managedFiles = files.filter { isManagedPhotoFile(it) }

            val preservedFiles = mutableListOf<File>()
            val candidates = mutableListOf<File>()

            for (file in managedFiles) {
                if (allPreserved.contains(file.absolutePath)) {
                    preservedFiles.add(file)
                } else {
                    candidates.add(file)
                }
            }

            // Step 1: Bounded best-effort stale cleanup: delete files older than maxAgeMs.
            // Active sessions and pending captures are protected unless abandoned beyond maxAgeMs.
            val iterator = candidates.iterator()
            while (iterator.hasNext()) {
                val file = iterator.next()
                if (now - file.lastModified() > maxAgeMs) {
                    file.delete()
                    getCompletionMarkerFile(file).delete()
                    iterator.remove()
                }
            }

            // Clean any orphaned marker files whose photo file no longer exists
            for (file in files) {
                if (file.isFile && file.name.endsWith(SUFFIX_COMPLETED_MARKER)) {
                    val baseName = file.name.removeSuffix(SUFFIX_COMPLETED_MARKER)
                    val targetPhoto = File(dir, baseName)
                    if (!targetPhoto.exists()) {
                        file.delete()
                    }
                }
            }

            // Step 2: Count-pruning for completed captures.
            // Pending camera captures (unmarked captures) are skipped from count-pruning
            // so active writers are never deleted while running.
            val countPruneCandidates = candidates.filter { isCaptureCompleted(it) }.toMutableList()
            val allowedCandidates = maxOf(0, maxFiles - preservedFiles.size)
            if (countPruneCandidates.size > allowedCandidates) {
                countPruneCandidates.sortBy { it.lastModified() }
                val toDeleteCount = countPruneCandidates.size - allowedCandidates
                var deleted = 0
                for (file in countPruneCandidates) {
                    if (deleted >= toDeleteCount) break
                    val age = now - file.lastModified()
                    if (age >= minOf(minInFlightAgeMs, maxAgeMs)) {
                        file.delete()
                        getCompletionMarkerFile(file).delete()
                        deleted++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pruning old camera files", e)
        }
    }
}
