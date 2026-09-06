package com.akslabs.circletosearch

import android.app.assist.AssistStructure
import com.akslabs.circletosearch.ui.components.TextNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val ASSIST_WHITESPACE = Regex("\\s+")
private val assistAnalysisMutex = Mutex()

/** All entry points and completion callbacks belong to the session's main scope. */
internal class AssistAnalysisRunner(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var generation = 0L
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun start(
        readNodes: suspend () -> List<TextNode>,
        onComplete: (Result<List<TextNode>>) -> Unit,
    ) {
        cancel()
        val requestGeneration = generation
        job = scope.launch {
            val result = try {
                Result.success(withContext(workerDispatcher) {
                    // Serialize across replaced sessions as well as repeated assist callbacks.
                    assistAnalysisMutex.withLock { readNodes() }
                })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (generation == requestGeneration) {
                job = null
                onComplete(result)
            }
        }
    }
}
internal suspend fun readAssistTextNodes(structure: AssistStructure?): List<TextNode> {
    if (structure == null) return emptyList()
    val coroutineContext = currentCoroutineContext()
    val cancellationCheck = { coroutineContext.ensureActive() }
    cancellationCheck()
    val nodes = mutableListOf<TextNode>()
    for (i in (structure.windowNodeCount - 1) downTo 0) {
        cancellationCheck()
        val window = structure.getWindowNodeAt(i)
        if (window.displayId != android.view.Display.DEFAULT_DISPLAY) continue
        collectTextNodes(window.rootViewNode, window.left, window.top, nodes, cancellationCheck)
    }
    return deduplicateAssistNodes(nodes, cancellationCheck)
}

internal fun deduplicateAssistNodes(
    nodes: List<TextNode>,
    cancellationCheck: () -> Unit = {},
): List<TextNode> {
    val accepted = mutableListOf<TextNode>()
    val byText = mutableMapOf<String, MutableList<TextNode>>()
    fun area(node: TextNode): Long = (node.bounds.right - node.bounds.left).toLong() *
        (node.bounds.bottom - node.bounds.top).toLong()
    val sorted = nodes.sortedWith { first, second ->
        cancellationCheck()
        area(first).compareTo(area(second))
    }
    for (candidate in sorted) {
        cancellationCheck()
        val normalized = candidate.fullText.trim().replace(ASSIST_WHITESPACE, " ")
        val sameText = byText.getOrPut(normalized) { mutableListOf() }
        val duplicate = sameText.any { existing ->
            cancellationCheck()
            val overlapWidth = (minOf(existing.bounds.right, candidate.bounds.right) -
                maxOf(existing.bounds.left, candidate.bounds.left)).coerceAtLeast(0)
            val overlapHeight = (minOf(existing.bounds.bottom, candidate.bounds.bottom) -
                maxOf(existing.bounds.top, candidate.bounds.top)).coerceAtLeast(0)
            overlapWidth.toLong() * overlapHeight >= minOf(area(existing), area(candidate)) * 0.8
        }
        if (!duplicate) {
            accepted += candidate
            sameText += candidate
        }
    }
    return accepted
}

private fun collectTextNodes(
    node: AssistStructure.ViewNode,
    parentX: Int,
    parentY: Int,
    list: MutableList<com.akslabs.circletosearch.ui.components.TextNode>,
    cancellationCheck: () -> Unit,
) {
    cancellationCheck()
    if (node.transformation != null) return

    val nodeX = parentX + node.left
    val nodeY = parentY + node.top
    val nodeRect = android.graphics.Rect(nodeX, nodeY, nodeX + node.width, nodeY + node.height)

    // Visual text is authoritative. Content descriptions and hints describe
    // semantics (often icons), not pixels, so they must not replace OCR text.
    val text = node.text?.toString()

    if (!text.isNullOrBlank() &&
        node.visibility == android.view.View.VISIBLE &&
        node.width > 0 && node.height > 10) {

        // AssistStructure does not expose reliable per-word geometry for all
        // widgets. Keep one accurate selectable block instead of inventing
        // character boxes that break with wrapping, bidi, and proportional text.
        val words = listOf(
            com.akslabs.circletosearch.ui.components.Word(
                text = text,
                index = 0,
                startIndex = 0,
                endIndex = text.length,
                bounds = android.graphics.RectF(nodeRect),
            )
        )

        list.add(
            com.akslabs.circletosearch.ui.components.TextNode(
                id = java.util.UUID.randomUUID().toString(),
                fullText = text,
                bounds = nodeRect,
                words = words
            )
        )
    }

    // Important: For children, subtract current node's scroll position from translated coordinates
    val nextParentX = nodeX - node.scrollX
    val nextParentY = nodeY - node.scrollY

    for (i in 0 until node.childCount) {
        collectTextNodes(node.getChildAt(i), nextParentX, nextParentY, list, cancellationCheck)
    }
}
