package com.akslabs.circletosearch

import android.graphics.Rect
import com.akslabs.circletosearch.ui.components.TextNode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AssistTextProcessingTest {
    @Test
    fun removesOnlyOverlappingSameTextAndKeepsSmallestGeometry() {
        val small = node("small", "first second", 0, 0, 100, 20)
        val parent = node("parent", "  first\n second  ", 0, 0, 200, 40)
        val elsewhere = node("elsewhere", "first second", 0, 100, 100, 120)
        val different = node("different", "Other", 0, 0, 100, 20)
        val result = deduplicateAssistNodes(listOf(parent, small, elsewhere, different))
        assertEquals(listOf("small", "elsewhere", "different"), result.map { it.id })
    }

    @Test
    fun largeDistinctTextSetAvoidsPairwiseComparisons() {
        val nodes = (0 until 2000).map { node("$it", "Label $it", 0, 0, 100, 20) }
        var checkpoints = 0
        val result = deduplicateAssistNodes(nodes) { checkpoints++ }
        assertEquals(nodes, result)
        // Counts bounded work, not wall-clock time: comparing every pair exceeds this by far.
        assertTrue(checkpoints < 40000)
    }

    @Test
    fun deduplicationPropagatesCancellation() {
        var checkpoints = 0
        val cancellation = CancellationException("cancel")
        try {
            deduplicateAssistNodes((0 until 100).map { node("$it", "text", it, 0, it + 5, 20) }) {
                if (++checkpoints == 10) throw cancellation
            }
            fail("Cancellation was ignored")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test
    fun analysisRunsOffCallerThreadAndCompletesOnCallerScope() = runBlocking {
        withTimeout(5000) {
            val caller = Thread.currentThread()
            val done = CompletableDeferred<Unit>()
            AssistAnalysisRunner(this).start(
                readNodes = {
                    assertNotSame(caller, Thread.currentThread())
                    emptyList()
                },
                onComplete = {
                    assertSame(caller, Thread.currentThread())
                    assertTrue(it.isSuccess)
                    done.complete(Unit)
                },
            )
            done.await()
        }
    }

    @Test
    fun runningAnalysisRemainsPendingUntilItsResultIsDelivered() = runBlocking {
        withTimeout(5000) {
            val runner = AssistAnalysisRunner(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()
            runner.start(
                readNodes = {
                    entered.complete(Unit)
                    release.await()
                    emptyList()
                },
                onComplete = {
                    assertFalse(runner.isRunning)
                    delivered.complete(Unit)
                },
            )
            entered.await()
            // The delivery-grace callback must observe in-flight work, not finish the session.
            assertTrue(runner.isRunning)
            release.complete(Unit)
            delivered.await()
            assertFalse(runner.isRunning)
        }
    }

    @Test
    fun replacedNonCooperativeAnalysisCannotPublishLateResult() = runBlocking {
        withTimeout(5000) {
            val runner = AssistAnalysisRunner(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val delivered = mutableListOf<String>()
            runner.start(
                readNodes = {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    emptyList()
                },
                onComplete = { delivered += "old" },
            )
            entered.await()
            runner.start(
                readNodes = { emptyList() },
                onComplete = {
                    delivered += "new"
                    finished.complete(Unit)
                },
            )
            release.complete(Unit)
            finished.await()
            coroutineContext.job.children.toList().joinAll()
            assertEquals(listOf("new"), delivered)
        }
    }

    @Test
    fun finishingSessionCancelsPendingDelivery() = runBlocking {
        withTimeout(5000) {
            val runner = AssistAnalysisRunner(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var delivered = false
            runner.start(
                readNodes = {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    emptyList()
                },
                onComplete = { delivered = true },
            )
            entered.await()
            runner.cancel()
            assertFalse(runner.isRunning)
            release.complete(Unit)
            coroutineContext.job.children.toList().joinAll()
            assertFalse(delivered)
        }
    }

    @Test
    fun readFailureIsDeliveredAsFailure() = runBlocking {
        withTimeout(5000) {
            val failure = IllegalStateException("read failed")
            val result = CompletableDeferred<Result<List<TextNode>>>()
            AssistAnalysisRunner(this).start(
                readNodes = { throw failure },
                onComplete = { result.complete(it) },
            )
            val delivered = result.await()
            assertTrue(delivered.isFailure)
            assertEquals(failure.javaClass, delivered.exceptionOrNull()?.javaClass)
            assertEquals(failure.message, delivered.exceptionOrNull()?.message)
        }
    }

    private fun node(id: String, text: String, left: Int, top: Int, right: Int, bottom: Int) =
        TextNode(id, text, Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }, emptyList())
}
