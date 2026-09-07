/*
 * Copyright (C) 2025 AKS-Labs
 */

package com.akslabs.circletosearch.utils

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object StorageUtils {
    private const val TAG = "StorageUtils"
    private const val SHARE_CACHE_DIRECTORY = "shared_images"
    private const val TARGET_SHARE_CACHE_BYTES = 64L * 1024L * 1024L
    private const val TARGET_SHARE_FILE_COUNT = 8
    private const val URI_GRANT_GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L
    private const val MAX_SHARE_FILE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FAILED_DELETE_RETRY_MS = 15L * 60L * 1000L
    private val shareCacheLock = Any()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scheduledCleanupJob: Job? = null
    private var scheduledCleanupAtMillis = Long.MAX_VALUE

    /**
     * Writes one image into the app-owned transient share cache.
     *
     * The whole operation is serialized so a concurrent cleanup can never
     * remove a file while it is still being encoded.
     */
    internal fun writeTransientShareImage(
        context: Context,
        prefix: String,
        writer: (File) -> Unit,
    ): File = synchronized(shareCacheLock) {
        val now = System.currentTimeMillis()
        pruneTransientImageCacheLocked(
            context = context,
            preservedFile = null,
            now = now,
            allowFailureRetry = true,
        )

        val directory = File(context.cacheDir, SHARE_CACHE_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create transient share cache"
        }

        val safePrefix = prefix
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(32)
            .ifBlank { "share" }
            .let { if (it.length >= 3) it else "share_$it" }
        val output = File.createTempFile("${safePrefix}_", ".png", directory)

        try {
            writer(output)
            check(output.length() > 0L) { "Transient share image is empty" }
            output.setLastModified(now)
            pruneTransientImageCacheLocked(
                context = context,
                preservedFile = output,
                now = now,
                allowFailureRetry = true,
            )
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    /**
     * Removes only app-generated share images. WebView caches, OCR models,
     * ML Kit models and other reusable data are intentionally untouched.
     */
    fun pruneTransientImageCache(context: Context) {
        pruneTransientImageCache(
            context = context,
            allowFailureRetry = true,
        )
    }

    private fun pruneTransientImageCache(
        context: Context,
        allowFailureRetry: Boolean,
    ) {
        synchronized(shareCacheLock) {
            pruneTransientImageCacheLocked(
                context = context,
                preservedFile = null,
                now = System.currentTimeMillis(),
                allowFailureRetry = allowFailureRetry,
            )
        }
    }

    private fun pruneTransientImageCacheLocked(
        context: Context,
        preservedFile: File?,
        now: Long,
        allowFailureRetry: Boolean,
    ) {
        val cacheDirectory = context.cacheDir
        val managedDirectory = File(cacheDirectory, SHARE_CACHE_DIRECTORY)
        val managedFiles = managedDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile }
        val legacyFiles = cacheDirectory.listFiles()
            .orEmpty()
            .filter(::isLegacyShareImage)
        val candidates = (managedFiles + legacyFiles).distinctBy { it.absolutePath }

        candidates.forEach { file ->
            if (file != preservedFile && ageOf(file, now) >= MAX_SHARE_FILE_AGE_MS) {
                deleteQuietly(file)
            }
        }

        val remaining = candidates.filter { it.exists() }
        var remainingBytes = remaining.sumOf { it.length() }
        var remainingCount = remaining.size

        for (file in remaining.sortedBy { it.lastModified() }) {
            if (
                remainingBytes <= TARGET_SHARE_CACHE_BYTES &&
                remainingCount <= TARGET_SHARE_FILE_COUNT
            ) {
                break
            }
            if (
                file == preservedFile ||
                ageOf(file, now) < URI_GRANT_GRACE_PERIOD_MS
            ) {
                continue
            }

            val length = file.length()
            if (deleteQuietly(file)) {
                remainingBytes = (remainingBytes - length).coerceAtLeast(0L)
                remainingCount--
            }
        }

        if (managedDirectory.isDirectory && managedDirectory.list().isNullOrEmpty()) {
            managedDirectory.delete()
        }

        scheduleNextCleanupLocked(
            context = context.applicationContext,
            files = candidates.filter { it.exists() },
            now = now,
            allowFailureRetry = allowFailureRetry,
        )
    }

    private fun scheduleNextCleanupLocked(
        context: Context,
        files: List<File>,
        now: Long,
        allowFailureRetry: Boolean,
    ) {
        if (files.isEmpty()) {
            scheduledCleanupJob?.cancel()
            scheduledCleanupJob = null
            scheduledCleanupAtMillis = Long.MAX_VALUE
            return
        }

        val isOverTarget =
            files.size > TARGET_SHARE_FILE_COUNT ||
                files.sumOf { it.length() } > TARGET_SHARE_CACHE_BYTES
        val retentionPeriod = if (isOverTarget) {
            URI_GRANT_GRACE_PERIOD_MS
        } else {
            MAX_SHARE_FILE_AGE_MS
        }
        val cleanupTimes = files.map { file ->
            file.lastModified() + retentionPeriod
        }
        val desiredCleanupAt = cleanupTimes.min()
        val cleanupAt = when {
            desiredCleanupAt > now -> desiredCleanupAt
            allowFailureRetry -> now + FAILED_DELETE_RETRY_MS
            else -> cleanupTimes.filter { it > now }.minOrNull() ?: run {
                scheduledCleanupJob?.cancel()
                scheduledCleanupJob = null
                scheduledCleanupAtMillis = Long.MAX_VALUE
                return
            }
        }

        if (
            scheduledCleanupJob?.isActive == true &&
            scheduledCleanupAtMillis <= cleanupAt
        ) {
            return
        }

        scheduledCleanupJob?.cancel()
        scheduledCleanupAtMillis = cleanupAt
        scheduledCleanupJob = maintenanceScope.launch {
            delay((cleanupAt - System.currentTimeMillis()).coerceAtLeast(1_000L))

            val shouldPrune = synchronized(shareCacheLock) {
                if (scheduledCleanupAtMillis != cleanupAt) {
                    false
                } else {
                    scheduledCleanupJob = null
                    scheduledCleanupAtMillis = Long.MAX_VALUE
                    true
                }
            }
            if (shouldPrune) {
                try {
                    pruneTransientImageCache(
                        context = context,
                        allowFailureRetry = false,
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to run scheduled share-cache cleanup", error)
                }
            }
        }
    }

    private fun isLegacyShareImage(file: File): Boolean {
        if (!file.isFile || !file.name.endsWith(".png", ignoreCase = true)) return false
        // Older Lens searches reused this file outside the managed share directory.
        return file.name == "screenshot.png" ||
            file.name.startsWith("selection_") ||
            file.name.startsWith("share_pin_")
    }

    private fun ageOf(file: File, now: Long): Long {
        return (now - file.lastModified()).coerceAtLeast(0L)
    }

    private fun deleteQuietly(file: File): Boolean {
        return try {
            !file.exists() || file.delete().also { deleted ->
                if (!deleted) Log.w(TAG, "Unable to delete stale share image: ${file.name}")
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to delete stale share image: ${file.name}", error)
            false
        }
    }
}
