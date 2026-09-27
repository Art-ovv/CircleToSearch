package com.akslabs.circletosearch.ocr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadState()
    object Cancelling : DownloadState()
    data class Failed(val errorMessage: String) : DownloadState()
}

/**
 * Interface for persisting active pack selection.
 */
interface OcrPreferences {
    fun getActivePackId(): String?
    fun setActivePackId(packId: String)
}

class SharedPreferencesOcrPreferences(
    private val prefs: SharedPreferences,
) : OcrPreferences {
    companion object {
        private const val KEY_ACTIVE_PACK_ID = "active_ocr_pack_id"
    }

    override fun getActivePackId(): String? = prefs.getString(KEY_ACTIVE_PACK_ID, null)

    override fun setActivePackId(packId: String) {
        prefs.edit().putString(KEY_ACTIVE_PACK_ID, packId).apply()
    }
}

class InMemoryOcrPreferences(
    var activeId: String? = null,
) : OcrPreferences {
    override fun getActivePackId(): String? = activeId
    override fun setActivePackId(packId: String) {
        activeId = packId
    }
}

/**
 * Engine coordination boundary for serializing pack selection and deletion with inference
 * under the engine's lock without lock inversion.
 */
interface OcrEngineBoundary {
    suspend fun <T> executeMutation(block: suspend () -> T): T
    suspend fun detachAndRelease()
}

object DefaultOcrEngineBoundary : OcrEngineBoundary {
    override suspend fun <T> executeMutation(block: suspend () -> T): T {
        return PaddleOcrEngine.executeMutation(block)
    }

    override suspend fun detachAndRelease() {
        PaddleOcrEngine.detachAndReleaseEngine()
    }
}

fun interface OcrDownloader {
    suspend fun download(
        pack: OcrLanguagePack,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    )
}

class DefaultOcrDownloader(
    private val storage: OcrLanguageStorage,
    private val streamSupplier: HttpStreamSupplier = DefaultHttpStreamSupplier,
) : OcrDownloader {
    override suspend fun download(
        pack: OcrLanguagePack,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        OcrLanguageDownloader.downloadPack(storage, pack, streamSupplier, onProgress)
    }
}

/**
 * Sanitizes download error messages for UI presentation, stripping raw CDN URLs and internal details.
 */
fun sanitizeDownloadErrorMessage(throwable: Throwable?): String {
    val message = throwable?.message.orEmpty()
    return when {
        message.contains("space", ignoreCase = true) || message.contains("ENOSPC", ignoreCase = true) ->
            "Download failed: not enough storage space. Free up space and retry."
        message.contains("SHA-256", ignoreCase = true) || message.contains("digest", ignoreCase = true) ->
            "Download failed: verification failed. Please retry."
        message.contains("exceeded expected size", ignoreCase = true) || message.contains("size mismatch", ignoreCase = true) ->
            "Download failed: incomplete or corrupted download. Check connection and retry."
        message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) ->
            "Download timed out. Check your connection and retry."
        else ->
            "Download failed. Check your internet connection and retry."
    }
}

/**
 * State manager and coordinator for OCR language packs.
 *
 * LOCK ORDERING:
 * 1. [operationMutex] (manager-level: protects state, initialization, and lifecycle transitions)
 * 2. [OcrEngineBoundary.executeMutation] / engineMutex (engine-level: protects ONNX sessions and serializes inference)
 *
 * [operationMutex] may acquire engineMutex via [engineBoundary.executeMutation].
 * The engine MUST NEVER acquire [operationMutex] while holding engineMutex.
 * The runtime engine reads [_activePack] via [getActivePackInMemory] without acquiring locks.
 */
class OcrLanguageManager(
    val storage: OcrLanguageStorage,
    val preferences: OcrPreferences,
    val engineBoundary: OcrEngineBoundary = DefaultOcrEngineBoundary,
    val downloader: OcrDownloader = DefaultOcrDownloader(storage),
) {
    private val operationMutex = Mutex()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    private val _activePack = MutableStateFlow<OcrLanguagePack>(OcrLanguageCatalog.bundledPack)
    val activePack: StateFlow<OcrLanguagePack> = _activePack.asStateFlow()

    private val _activePackId = MutableStateFlow(OcrLanguageCatalog.BUNDLED_PACK_ID)
    val activePackId: StateFlow<String> = _activePackId.asStateFlow()

    private val _installedPackIds = MutableStateFlow<Set<String>>(setOf(OcrLanguageCatalog.BUNDLED_PACK_ID))
    val installedPackIds: StateFlow<Set<String>> = _installedPackIds.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // Structured caller-scope download tracking guarded by operationMutex
    private var activeDownloadToken: Any? = null
    private var activeDownloadJob: Job? = null
    private var activeDownloadingPackId: String? = null

    /**
     * Suspend initialization performing filesystem checks and preferences off the main thread.
     * Publishes ready only on success. Cheap and idempotent once initialized.
     */
    suspend fun init() {
        if (_isInitialized.value) return
        operationMutex.withLock {
            initLocked()
        }
    }

    private suspend fun initLocked() {
        if (_isInitialized.value) return
        withContext(Dispatchers.IO) {
            try {
                storage.ensureDirectories()
                storage.cleanStaleStagingDirs()
                refreshInstalledPacksLocked()

                val savedActive = preferences.getActivePackId() ?: OcrLanguageCatalog.BUNDLED_PACK_ID
                val effectiveActive = if (storage.isPackInstalled(savedActive)) {
                    savedActive
                } else {
                    preferences.setActivePackId(OcrLanguageCatalog.BUNDLED_PACK_ID)
                    OcrLanguageCatalog.BUNDLED_PACK_ID
                }

                _activePackId.value = effectiveActive
                _activePack.value = OcrLanguageCatalog.getPack(effectiveActive) ?: OcrLanguageCatalog.bundledPack
                _isInitialized.value = true
                _initError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _initError.value = e.localizedMessage ?: "Failed to initialize OCR language storage"
                throw e
            }
        }
    }

    /**
     * Non-blocking, lock-free in-memory read of the active pack.
     * Called by runtime engine during inference without lock inversion.
     */
    fun getActivePackInMemory(): OcrLanguagePack {
        return _activePack.value
    }

    suspend fun getActivePack(): OcrLanguagePack {
        if (!_isInitialized.value) {
            init()
        }
        return _activePack.value
    }

    suspend fun getActivePackId(): String {
        return getActivePack().id
    }

    /**
     * Switches the active recognition pack.
     * Serialized under [operationMutex] and engine's mutex on Dispatchers.IO.
     * Rejects uninstalled packs.
     */
    suspend fun selectActivePack(packId: String): Boolean {
        init()
        return operationMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!storage.isPackInstalled(packId)) {
                    throw IllegalArgumentException("Cannot select uninstalled pack: $packId")
                }
                if (_activePackId.value == packId) return@withContext true

                val targetPack = OcrLanguageCatalog.getPack(packId)
                    ?: throw IllegalArgumentException("Unknown pack: $packId")

                engineBoundary.executeMutation {
                    if (_activePackId.value != packId) {
                        engineBoundary.detachAndRelease()
                    }
                    preferences.setActivePackId(packId)
                    _activePackId.value = packId
                    _activePack.value = targetPack
                }
                true
            }
        }
    }

    /**
     * Initiates and runs a structured download of [packId] in the caller coroutine's scope.
     * Enforces strictly one download process-wide, rejecting concurrent requests.
     */
    suspend fun startDownload(packId: String) {
        init()
        val pack = OcrLanguageCatalog.getPack(packId)
            ?: throw IllegalArgumentException("Unknown pack for download: $packId")
        if (pack.isBundled) {
            throw IllegalArgumentException("Cannot download bundled pack: $packId")
        }

        // startDownload installed check must be on Dispatchers.IO
        val alreadyInstalled = withContext(Dispatchers.IO) {
            storage.isPackInstalled(packId)
        }
        if (alreadyInstalled) {
            return
        }

        val callerJob = currentCoroutineContext().job
        val token = Any()

        operationMutex.withLock {
            if (storage.isPackInstalled(packId)) {
                return
            }
            if (activeDownloadToken != null) {
                throw IllegalStateException("Another download is currently in progress (${activeDownloadingPackId ?: "active"})")
            }
            if (_downloadStates.value[packId] is DownloadState.Cancelling) {
                throw IllegalStateException("Pack $packId is currently cancelling; wait for cleanup to complete")
            }

            activeDownloadToken = token
            activeDownloadJob = callerJob
            activeDownloadingPackId = packId
            _downloadStates.update { it + (packId to DownloadState.Downloading(0f, 0L, pack.totalSizeBytes)) }
        }

        var failureReason: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                downloader.download(pack) { downloaded, total ->
                    if (!callerJob.isActive) return@download
                    val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _downloadStates.update { map ->
                        if (activeDownloadToken !== token) return@update map
                        val current = map[packId]
                        if (current is DownloadState.Downloading) {
                            map + (packId to DownloadState.Downloading(progress, downloaded, total))
                        } else {
                            map
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failureReason = e
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                operationMutex.withLock {
                    if (activeDownloadToken === token) {
                        activeDownloadToken = null
                        activeDownloadJob = null
                        activeDownloadingPackId = null
                        refreshInstalledPacksLocked()
                        val installed = storage.isPackInstalled(packId)
                        if (installed) {
                            _downloadStates.update { it - packId }
                        } else if (failureReason != null) {
                            val sanitizedMsg = sanitizeDownloadErrorMessage(failureReason)
                            _downloadStates.update { it + (packId to DownloadState.Failed(sanitizedMsg)) }
                        } else {
                            _downloadStates.update { it - packId }
                        }
                    }
                }
            }
        }
    }

    /**
     * Cancels an in-progress download for [packId].
     * Releases mutex before cancelling the job, avoiding deadlocks with finally cleanup.
     */
    suspend fun cancelDownload(packId: String) {
        val jobToCancel: Job? = operationMutex.withLock {
            if (activeDownloadingPackId == packId && activeDownloadToken != null) {
                _downloadStates.update { it + (packId to DownloadState.Cancelling) }
                activeDownloadJob
            } else {
                null
            }
        }
        jobToCancel?.cancel()
    }

    /**
     * Removes an installed pack from disk coherently under engine lock.
     * Reverts active pack to bundled if removed pack was active.
     * Never reports silent success on delete failure.
     */
    suspend fun removePack(packId: String): Boolean {
        init()
        if (packId == OcrLanguageCatalog.BUNDLED_PACK_ID) {
            throw IllegalArgumentException("Bundled pack cannot be removed")
        }

        return operationMutex.withLock {
            if (activeDownloadingPackId == packId) {
                throw IllegalStateException("Cannot remove pack $packId while download is in progress; cancel the download first")
            }

            withContext(Dispatchers.IO) {
                val wasActive = (_activePackId.value == packId)
                engineBoundary.executeMutation {
                    if (wasActive) {
                        preferences.setActivePackId(OcrLanguageCatalog.BUNDLED_PACK_ID)
                        _activePackId.value = OcrLanguageCatalog.BUNDLED_PACK_ID
                        _activePack.value = OcrLanguageCatalog.bundledPack
                    }
                    engineBoundary.detachAndRelease()
                    val deleted = storage.deleteInstalledPack(packId)
                    if (!deleted) {
                        throw IOException("Failed to delete pack directory for $packId")
                    }
                }
                refreshInstalledPacksLocked()
                _downloadStates.update { it - packId }
                wasActive
            }
        }
    }

    suspend fun refreshInstalledPacks() {
        init()
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                refreshInstalledPacksLocked()
            }
        }
    }

    private fun refreshInstalledPacksLocked() {
        val installed = mutableSetOf(OcrLanguageCatalog.BUNDLED_PACK_ID)
        OcrLanguageCatalog.downloadablePacks.forEach { pack ->
            if (storage.isPackInstalled(pack.id)) {
                installed.add(pack.id)
            }
        }
        _installedPackIds.value = installed
    }

    companion object {
        @Volatile
        private var instance: OcrLanguageManager? = null

        /**
         * Asynchronously gets or creates the singleton [OcrLanguageManager] on [Dispatchers.IO],
         * ensuring filesystem directory creation and SharedPreferences access never occur on the main thread.
         */
        suspend fun getInstance(context: Context): OcrLanguageManager {
            instance?.let { return it }
            return withContext(Dispatchers.IO) {
                instance ?: synchronized(this) {
                    instance ?: run {
                        val appContext = context.applicationContext
                        val baseDir = appContext.noBackupFilesDir ?: appContext.filesDir
                        val storage = OcrLanguageStorage(File(baseDir, "ocr_models"))
                        val prefs = SharedPreferencesOcrPreferences(
                            appContext.getSharedPreferences("ocr_language_prefs", Context.MODE_PRIVATE)
                        )
                        OcrLanguageManager(
                            storage = storage,
                            preferences = prefs,
                            engineBoundary = DefaultOcrEngineBoundary,
                            downloader = DefaultOcrDownloader(storage),
                        ).also { instance = it }
                    }
                }
            }
        }

        /**
         * Runtime preparation helper: ensures manager singleton is acquired and initialized
         * with persisted preferences on Dispatchers.IO. Cheap and idempotent once initialized.
         */
        suspend fun prepareRuntime(context: Context): OcrLanguageManager {
            val manager = getInstance(context)
            manager.init()
            return manager
        }

        /**
         * Lock-free in-memory read of the active pack for engine inference.
         * Must be preceded by [prepareRuntime] on cold-start entry paths outside engine lock.
         */
        fun getActivePack(context: Context): OcrLanguagePack {
            val current = instance
            return current?.getActivePackInMemory() ?: OcrLanguageCatalog.bundledPack
        }

        @androidx.annotation.VisibleForTesting
        internal fun setTestInstance(testInstance: OcrLanguageManager?) {
            instance = testInstance
        }
    }
}
