package com.akslabs.circletosearch.ocr

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class OcrLanguageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testBaseDir: File
    private lateinit var storage: OcrLanguageStorage
    private lateinit var fakePrefs: InMemoryOcrPreferences
    private lateinit var fakeEngineBoundary: FakeOcrEngineBoundary
    private lateinit var fakeDownloader: FakeOcrDownloader
    private lateinit var testScope: CoroutineScope
    private lateinit var manager: OcrLanguageManager

    @Before
    fun setUp() {
        testBaseDir = tempFolder.newFolder("ocr_manager_test")
        storage = OcrLanguageStorage(testBaseDir)
        fakePrefs = InMemoryOcrPreferences()
        fakeEngineBoundary = FakeOcrEngineBoundary()
        fakeDownloader = FakeOcrDownloader()
        testScope = CoroutineScope(Dispatchers.IO + Job())
        manager = OcrLanguageManager(
            storage = storage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        OcrLanguageManager.setTestInstance(null)
    }

    private fun installMockPack(packId: String) {
        val pack = OcrLanguageCatalog.getPack(packId)!!
        val packDir = storage.getPackDir(packId)
        packDir.mkdirs()
        RandomAccessFile(File(packDir, pack.onnxFilename), "rw").use { it.setLength(pack.onnxSize) }
        RandomAccessFile(File(packDir, pack.yamlFilename), "rw").use { it.setLength(pack.yamlSize) }
    }

    @Test
    fun defaultActivePackIsBundledEastSlavic(): Unit = runBlocking {
        manager.init()
        val active = manager.getActivePack()
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, active.id)
        assertTrue(active.isBundled)
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, manager.getActivePackInMemory().id)
    }

    @Test
    fun suspendInitializationPublishesReadyOnlyOnSuccess(): Unit = runBlocking {
        assertFalse(manager.isInitialized.value)
        installMockPack("latin")
        fakePrefs.setActivePackId("latin")

        manager.init()

        assertTrue(manager.isInitialized.value)
        assertEquals("latin", manager.getActivePack().id)
        assertEquals("latin", manager.getActivePackInMemory().id)
    }

    @Test
    fun selectingUninstalledPackFailsAndKeepsBundled(): Unit = runBlocking {
        manager.init()
        try {
            manager.selectActivePack("latin")
            fail("Expected IllegalArgumentException when selecting uninstalled pack")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("uninstalled"))
        }
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, manager.getActivePackId())
    }

    @Test
    fun selectingInstalledPackSucceedsAndPersistsUnderEngineLock(): Unit = runBlocking {
        manager.init()
        installMockPack("latin")
        manager.refreshInstalledPacks()

        val selected = manager.selectActivePack("latin")
        assertTrue("Installed pack should be selected successfully", selected)
        assertEquals("latin", manager.getActivePackId())
        assertEquals("latin", fakePrefs.getActivePackId())
        assertTrue("Engine boundary mutation must be executed", fakeEngineBoundary.mutationCount > 0)
    }

    @Test
    fun removingActivePackSwitchesBackToBundledUnderEngineLock(): Unit = runBlocking {
        manager.init()
        installMockPack("latin")
        manager.refreshInstalledPacks()
        manager.selectActivePack("latin")
        assertEquals("latin", manager.getActivePackId())

        val wasActive = manager.removePack("latin")
        assertTrue("removePack should report pack was active", wasActive)

        assertEquals("Should revert to bundled East Slavic", OcrLanguageCatalog.BUNDLED_PACK_ID, manager.getActivePackId())
        assertEquals("Preference should revert to bundled", OcrLanguageCatalog.BUNDLED_PACK_ID, fakePrefs.getActivePackId())
        assertFalse(storage.isPackInstalled("latin"))
        assertTrue("Engine session should be detached and released", fakeEngineBoundary.detachCount > 0)
    }

    @Test
    fun removingInactivePackPreservesActivePack(): Unit = runBlocking {
        manager.init()
        installMockPack("latin")
        installMockPack("korean")
        manager.refreshInstalledPacks()

        manager.selectActivePack("latin")
        assertEquals("latin", manager.getActivePackId())

        val wasActive = manager.removePack("korean")
        assertFalse("Removed pack was not active", wasActive)

        assertEquals("Active pack should remain latin", "latin", manager.getActivePackId())
        assertFalse(storage.isPackInstalled("korean"))
        assertTrue(storage.isPackInstalled("latin"))
    }

    @Test
    fun bundledPackCannotBeRemoved(): Unit = runBlocking {
        manager.init()
        try {
            manager.removePack(OcrLanguageCatalog.BUNDLED_PACK_ID)
            fail("Expected IllegalArgumentException when removing bundled pack")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Bundled pack cannot be removed"))
        }
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, manager.getActivePackId())
    }

    @Test
    fun failedDeleteStorageThrowsHonestErrorAndMaintainsState(): Unit = runBlocking {
        manager.init()
        installMockPack("latin")
        manager.refreshInstalledPacks()

        val failingStorage = object : OcrLanguageStorage(testBaseDir) {
            override fun deleteInstalledPack(packId: String): Boolean {
                return false
            }
        }
        val customManager = OcrLanguageManager(
            storage = failingStorage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
        customManager.init()

        try {
            customManager.removePack("latin")
            fail("Expected IOException when storage deletion fails")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Failed to delete pack directory"))
        }
    }

    // ==========================================
    // Requirement A: Mutex re-entrancy deadlock regressions
    // ==========================================

    @Test
    fun freshManagerSelectBundledDeadlockRegression(): Unit = runBlocking {
        val freshManager = OcrLanguageManager(
            storage = storage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
        withTimeout(2000) {
            val selected = freshManager.selectActivePack(OcrLanguageCatalog.BUNDLED_PACK_ID)
            assertTrue(selected)
        }
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, freshManager.getActivePackId())
    }

    @Test
    fun freshManagerStartDownloadDeadlockRegression(): Unit = runBlocking {
        val freshManager = OcrLanguageManager(
            storage = storage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            installMockPack(pack.id)
            onProgress(pack.totalSizeBytes, pack.totalSizeBytes)
        }
        withTimeout(2000) {
            freshManager.startDownload("latin")
        }
        assertTrue(freshManager.isInitialized.value)
        assertTrue(storage.isPackInstalled("latin"))
    }

    @Test
    fun freshManagerRemovePackDeadlockRegression(): Unit = runBlocking {
        installMockPack("latin")
        val freshManager = OcrLanguageManager(
            storage = storage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
        withTimeout(2000) {
            freshManager.removePack("latin")
        }
        assertTrue(freshManager.isInitialized.value)
        assertFalse(storage.isPackInstalled("latin"))
    }

    // ==========================================
    // Requirement B & E: Cold-start persisted selection via prepareRuntime
    // ==========================================

    @Test
    fun coldStartPersistedSelectionRestoredViaPrepareRuntime(): Unit = runBlocking {
        installMockPack("latin")
        fakePrefs.setActivePackId("latin")

        val freshManager = OcrLanguageManager(
            storage = storage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
        OcrLanguageManager.setTestInstance(freshManager)

        val dummyContext: Context = object : ContextWrapper(null) {}

        val prepared = OcrLanguageManager.prepareRuntime(dummyContext)
        assertTrue(prepared.isInitialized.value)
        assertEquals("latin", prepared.getActivePack().id)
        assertEquals("latin", OcrLanguageManager.getActivePack(dummyContext).id)
    }

    @Test
    fun coldStartMissingPackFallsBackToBundledViaPrepareRuntime(): Unit = runBlocking {
        fakePrefs.setActivePackId("latin")

        val freshManager = OcrLanguageManager(
            storage = storage,
            preferences = fakePrefs,
            engineBoundary = fakeEngineBoundary,
            downloader = fakeDownloader,
        )
        OcrLanguageManager.setTestInstance(freshManager)

        val dummyContext: Context = object : ContextWrapper(null) {}

        val prepared = OcrLanguageManager.prepareRuntime(dummyContext)
        assertTrue(prepared.isInitialized.value)
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, prepared.getActivePack().id)
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, fakePrefs.getActivePackId())
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, OcrLanguageManager.getActivePack(dummyContext).id)
    }

    // ==========================================
    // Requirement C: Download lifetime and structured cancellation
    // ==========================================

    @Test
    fun ownerScopeCancelWithoutCallingCancelDownloadClearsState(): Unit = runBlocking {
        manager.init()
        val downloadStarted = CompletableDeferred<Unit>()

        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            downloadStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        val ownerScope = CoroutineScope(Dispatchers.IO + Job())
        val downloadJob = ownerScope.launch {
            try {
                manager.startDownload("latin")
            } catch (_: CancellationException) {
            }
        }

        withTimeout(3000) { downloadStarted.await() }

        // Cancel owner scope directly without calling manager.cancelDownload
        ownerScope.cancel()
        withTimeout(3000) { downloadJob.join() }

        assertEquals(DownloadState.Idle, manager.downloadStates.value["latin"] ?: DownloadState.Idle)
    }

    @Test
    fun cancellationBeforeFirstIoWorkClearsState(): Unit = runBlocking {
        manager.init()
        val ownerScope = CoroutineScope(Dispatchers.IO + Job())
        ownerScope.cancel()

        withTimeout(3000) {
            try {
                ownerScope.launch {
                    manager.startDownload("latin")
                }.join()
            } catch (_: CancellationException) {
            }
        }

        assertEquals(DownloadState.Idle, manager.downloadStates.value["latin"] ?: DownloadState.Idle)
    }

    @Test
    fun immediateRetryRemainsRejectedDuringCleanupBarrierThenSucceedsAfter(): Unit = runBlocking {
        manager.init()
        val downloadStarted = CompletableDeferred<Unit>()
        val cleanupBarrierEntered = CompletableDeferred<Unit>()
        val allowCleanupToFinish = CompletableDeferred<Unit>()

        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            downloadStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                withContext(NonCancellable) {
                    cleanupBarrierEntered.complete(Unit)
                    allowCleanupToFinish.await()
                }
            }
        }

        val ownerJob = testScope.launch {
            try {
                manager.startDownload("latin")
            } catch (_: CancellationException) {
            }
        }
        withTimeout(3000) { downloadStarted.await() }

        // Cancel download: cancelDownload marks Cancelling and cancels the job
        manager.cancelDownload("latin")
        withTimeout(3000) { cleanupBarrierEntered.await() }

        // During cleanup barrier, immediate retry must be rejected
        withTimeout(3000) {
            try {
                manager.startDownload("latin")
                fail("Expected IllegalStateException while cleanup barrier is active")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("cancelling") || e.message!!.contains("in progress"))
            }
        }

        // Release barrier to let cleanup complete
        allowCleanupToFinish.complete(Unit)
        withTimeout(3000) { ownerJob.join() }

        // After cleanup finishes, retry succeeds
        val secondCompleted = CompletableDeferred<Unit>()
        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            installMockPack(pack.id)
            secondCompleted.complete(Unit)
        }

        val secondJob = testScope.launch {
            manager.startDownload("latin")
        }
        withTimeout(3000) { secondCompleted.await() }
        withTimeout(3000) { secondJob.join() }

        manager.refreshInstalledPacks()
        assertTrue(storage.isPackInstalled("latin"))
    }

    @Test
    fun failedDownloadPreservesFailureStateAndSanitizesMessage(): Unit = runBlocking {
        manager.init()
        fakeDownloader.onDownloadBlock = { _, _ ->
            throw IOException("Connection failed to https://cdn.huggingface.co/models/secret/inference.onnx: timeout")
        }

        withTimeout(3000) {
            try {
                manager.startDownload("latin")
                fail("Expected IOException from failed download")
            } catch (e: IOException) {
                // Expected
            }
        }

        val state = manager.downloadStates.value["latin"]
        assertTrue("State must be Failed", state is DownloadState.Failed)
        val failed = state as DownloadState.Failed
        assertFalse("Error message must not leak CDN URL", failed.errorMessage.contains("https://"))
        assertFalse("Error message must not leak secret path", failed.errorMessage.contains("secret"))
    }

    @Test
    fun twoStartsRejectedWhileFirstIsActive(): Unit = runBlocking {
        manager.init()
        val downloadStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()

        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            downloadStarted.complete(Unit)
            allowCompletion.await()
        }

        val job1 = testScope.launch {
            manager.startDownload("latin")
        }
        withTimeout(3000) { downloadStarted.await() }

        // Second start must be rejected
        withTimeout(3000) {
            try {
                manager.startDownload("korean")
                fail("Expected IllegalStateException for concurrent download start")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Another download is currently in progress"))
            }
        }

        allowCompletion.complete(Unit)
        withTimeout(3000) { job1.join() }
    }

    @Test
    fun noStaleCallbacksUpdateReplacement(): Unit = runBlocking {
        manager.init()
        val job1Started = CompletableDeferred<Unit>()
        var staleProgressEmitter: ((Long, Long) -> Unit)? = null

        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            staleProgressEmitter = onProgress
            job1Started.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        val job1 = testScope.launch {
            try {
                manager.startDownload("latin")
            } catch (_: CancellationException) {
            }
        }
        withTimeout(3000) { job1Started.await() }

        manager.cancelDownload("latin")
        withTimeout(3000) { job1.join() }
        assertEquals(DownloadState.Idle, manager.downloadStates.value["latin"] ?: DownloadState.Idle)

        // Stale progress callback from cancelled coroutine
        staleProgressEmitter?.invoke(500L, 1000L)

        // State must remain Idle
        assertEquals(DownloadState.Idle, manager.downloadStates.value["latin"] ?: DownloadState.Idle)
    }

    @Test
    fun removingCurrentlyDownloadingPackIsRejectedActionably(): Unit = runBlocking {
        manager.init()
        val downloadStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()

        fakeDownloader.onDownloadBlock = { pack, onProgress ->
            downloadStarted.complete(Unit)
            allowCompletion.await()
        }

        val job = testScope.launch {
            manager.startDownload("latin")
        }
        withTimeout(3000) { downloadStarted.await() }

        withTimeout(3000) {
            try {
                manager.removePack("latin")
                fail("Expected IllegalStateException when removing pack being downloaded")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("download is in progress"))
            }
        }

        allowCompletion.complete(Unit)
        withTimeout(3000) { job.join() }
    }

    @Test
    fun selectionVsInferenceSerializedUnderEngineBoundary(): Unit = runBlocking {
        manager.init()
        installMockPack("latin")
        manager.refreshInstalledPacks()

        val inferenceHold = CompletableDeferred<Unit>()
        val selectionStarted = CompletableDeferred<Unit>()

        // Simulate an ongoing inference pass holding the engine lock
        val inferenceJob = testScope.launch {
            fakeEngineBoundary.executeMutation {
                selectionStarted.complete(Unit)
                inferenceHold.await()
            }
        }

        withTimeout(3000) { selectionStarted.await() }

        // Try selecting pack: must suspend until inference releases engine lock
        val selectJob = testScope.launch {
            manager.selectActivePack("latin")
        }

        // Active pack must still be bundled while inference holds lock
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, manager.getActivePackInMemory().id)

        // Release inference
        inferenceHold.complete(Unit)
        withTimeout(3000) { selectJob.join() }

        assertEquals("latin", manager.getActivePackInMemory().id)
        withTimeout(3000) { inferenceJob.join() }
    }

    private class FakeOcrEngineBoundary : OcrEngineBoundary {
        private val mutex = Mutex()
        var mutationCount = 0
        var detachCount = 0

        override suspend fun <T> executeMutation(block: suspend () -> T): T {
            return mutex.withLock {
                mutationCount++
                block()
            }
        }

        override suspend fun detachAndRelease() {
            detachCount++
        }
    }

    private class FakeOcrDownloader : OcrDownloader {
        var onDownloadBlock: (suspend (OcrLanguagePack, (Long, Long) -> Unit) -> Unit)? = null

        override suspend fun download(
            pack: OcrLanguagePack,
            onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
        ) {
            val custom = onDownloadBlock
            if (custom != null) {
                custom(pack, onProgress)
            } else {
                onProgress(pack.totalSizeBytes, pack.totalSizeBytes)
            }
        }
    }
}
