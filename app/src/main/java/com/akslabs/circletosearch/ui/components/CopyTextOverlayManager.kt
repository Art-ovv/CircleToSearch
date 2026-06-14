/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.akslabs.circletosearch.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.utils.ImageUtils
import kotlinx.coroutines.*
/** Simple holder for a floating-toolbar button's label and screen hit-rect. */
private class ToolbarButton(val label: String, val rect: Rect)

/**
 * Manages the dim+punch-out Copy Text overlay with OCR capabilities.
 */
class CopyTextOverlayManager(
    private val context: Context,
    private val screenshotBitmap: android.graphics.Bitmap?
) {
    private var dimView: DimPunchOutView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var onAnalysisCompleteCallback: ((Int) -> Unit)? = null

    private val isScanning = mutableStateOf(false)
    private val statusMessage = mutableStateOf<String?>(null)
    private val textNodes = mutableListOf<TextNode>()
    private var allWords: List<Word> = emptyList()

    /**
     * Sets a callback that is invoked after the analysis is complete.
     * @param callback receives the number of text nodes found.
     */
    fun setOnAnalysisComplete(callback: (Int) -> Unit) {
        onAnalysisCompleteCallback = callback
    }

    /**
     * Starts text analysis. Called automatically on startup.
     */
    fun startAnalysis() {
        // No-op: getOverlayView already calls scanNodes natively. Prevents double-scanning.
    }

    /**
     * Returns the number of found text nodes.
     */
    fun getNodeCount(): Int = textNodes.size

    /**
     * Checks if scanning is currently in progress.
     */
    fun isScanning(): Boolean = isScanning.value

    private fun updateAllWords() {
        allWords = textNodes.flatMap { it.words }
    }
    
    // Selection state
    private var globalSelectionStart: Int = -1
    private var globalSelectionEnd: Int = -1

    fun getOverlayView(onDismiss: () -> Unit): View {
        onDismissCallback = onDismiss
        
        // Reset interactive state
        globalSelectionStart = -1
        globalSelectionEnd = -1
        
        val container = FrameLayout(context)
        
        val view = DimPunchOutView(context)
        dimView = view
        container.addView(view)
        
        val topBar = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TopBarUI(onClose = { dismiss() })

                        statusMessage.value?.let { msg ->
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 100.dp)
                                    .background(ComposeColor.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    msg,
                                    color = ComposeColor.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
        container.addView(topBar)

        scanNodes(view)
        
        return container
    }

    @Composable
    private fun TopBarUI(onClose: () -> Unit) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .background(ComposeColor.Black.copy(alpha = 0.35f), CircleShape)
                    .size(40.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Exit Copy Mode", tint = ComposeColor.White)
            }
        }
    }

    private fun Int.toComposeColor(): ComposeColor = ComposeColor(this)

    fun dismiss() {
        scanJob?.cancel()
        dimView = null
        onDismissCallback?.invoke()
        onDismissCallback = null
    }

    fun rescanNodes() {
        dimView?.let { scanNodes(it) }
    }

    private fun scanNodes(view: View) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.Main) {
            isScanning.value = true
            val bitmap = screenshotBitmap ?: BitmapRepository.getScreenshot()

            if (bitmap == null) {
                isScanning.value = false
                view.invalidate()
                return@launch
            }

            try {
                val ocrNodes = withContext(Dispatchers.IO) {
                    com.akslabs.circletosearch.ocr.TesseractEngine.extractText(context, bitmap)
                }
                val sortedNodes = ocrNodes.textNodes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                textNodes.clear()
                textNodes.addAll(sortedNodes)
                updateAllWords()
                
                onAnalysisCompleteCallback?.invoke(textNodes.size)
                Log.d("CopyTextOverlay", "Analysis complete: ${textNodes.size} total nodes")
            } catch (e: Throwable) { // Catch Throwable to prevent silent OutOfMemoryErrors from locking UI
                Log.e("CopyTextOverlay", "Extraction failed: ${e.message}")
            } finally {
                isScanning.value = false
                view.invalidate()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class DimPunchOutView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density

        private val dimPaint = Paint().apply { color = Color.BLACK; alpha = 38; isAntiAlias = false }
        private val selectedWordPaint = Paint().apply {
            color = try { context.getColor(android.R.color.system_accent1_200) } catch(e: Exception) { Color.parseColor("#D0BCFF") }
            alpha = 90
            isAntiAlias = true
        }
        private val handlePaint = Paint().apply { color = Color.parseColor("#6750A4"); isAntiAlias = true }
        private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F3EDF7") }
        private val toolbarActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6750A4") }
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }

        private var dragHandleType = 0
        private var toolbarButtons: List<ToolbarButton> = emptyList()
        private var toolbarRect = RectF()
        private var dragHandleRect = RectF()
        private var toolbarOffsetX = 0f
        private var toolbarOffsetY = 0f
        private var isDraggingToolbar = false
        private var toolbarInitialized = false
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        
        // Allocation-free drawing fields
        private val tempRect = RectF()
        private val tempBtnRect = RectF()
        private val highlightPath = Path()
        private val encompassingRect = RectF()
        private var currentSx = 1f
        private var currentSy = 1f
        
        // Hoisted UI properties
        private val dynamicSurface = try { context.getColor(android.R.color.system_surface_container_light) } catch(e: Exception) { Color.parseColor("#F3EDF7") }
        private val dynamicPrimary = try { context.getColor(android.R.color.system_accent1_600) } catch(e: Exception) { Color.parseColor("#6750A4") }
        private val shadowPaint = Paint(toolbarBgPaint).apply { setShadowLayer(12f * density, 0f, 4f * density, Color.BLACK and 0x2F000000) }
        private val hPaint = Paint(toolbarActionPaint).apply { color = Color.LTGRAY; style = Paint.Style.FILL }
        private val btnPaint = Paint(handlePaint).apply { color = dynamicPrimary }
        private val btnTextPaint = Paint(toolbarActionPaint).apply { 
            color = Color.WHITE; style = Paint.Style.FILL; textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }

        private fun updateSelection(start: Int, end: Int) {
            globalSelectionStart = start
            globalSelectionEnd = end
            recalculateHighlightPath()
            invalidate()
        }

        private fun recalculateHighlightPath() {
            highlightPath.reset()
            encompassingRect.setEmpty()

            if (globalSelectionStart == -1 || globalSelectionEnd == -1) return

            val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
            val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
            val selectedWords = (start..end).mapNotNull { allWords.getOrNull(it) }

            if (selectedWords.isEmpty()) return

            encompassingRect.set(selectedWords.first().bounds)
            selectedWords.forEach { encompassingRect.union(it.bounds) }

            val sx = if (width > 0) width.toFloat() / (screenshotBitmap?.width?.toFloat() ?: 1f) else 1f
            val sy = if (height > 0) height.toFloat() / (screenshotBitmap?.height?.toFloat() ?: 1f) else 1f

            selectedWords.groupBy { (it.bounds.centerY() / 20).toInt() }.forEach { (_, wordsInLine) ->
                if (wordsInLine.isEmpty()) return@forEach
                val first = wordsInLine.minByOrNull { it.bounds.left } ?: return@forEach
                val last = wordsInLine.maxByOrNull { it.bounds.right } ?: return@forEach
                val lineRect = RectF(first.bounds.left, wordsInLine.minOf { it.bounds.top }, last.bounds.right, wordsInLine.maxOf { it.bounds.bottom })
                lineRect.inset(-8f / sx, -4f / sy)
                highlightPath.addRoundRect(lineRect, 8f / sx, 8f / sy, Path.Direction.CW)
            }
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        private fun scaleRect(r: RectF): RectF {
            val sx = width.toFloat() / (screenshotBitmap?.width?.toFloat() ?: 1f)
            val sy = height.toFloat() / (screenshotBitmap?.height?.toFloat() ?: 1f)
            return RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
        }
        
        private fun scaleRect(r: Rect): RectF = scaleRect(RectF(r))

        override fun onDraw(canvas: Canvas) {
            if (globalSelectionStart == -1 || globalSelectionEnd == -1) {
                return // Do not dim or show text blocks if nothing is selected
            }

            val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            // Use Matrix scaling to avoid allocating RectFs for every word
            val sx = width.toFloat() / (screenshotBitmap?.width?.toFloat() ?: 1f)
            val sy = height.toFloat() / (screenshotBitmap?.height?.toFloat() ?: 1f)
            
            if (sx != currentSx || sy != currentSy) {
                currentSx = sx
                currentSy = sy
                recalculateHighlightPath()
            }

            canvas.scale(sx, sy)

            textNodes.forEach { node ->
                tempRect.set(node.bounds)
                tempRect.inset(-8f / sx, -4f / sy)
                canvas.drawRoundRect(tempRect, 8f / sx, 8f / sy, clearPaint)
            }

            if (globalSelectionStart != -1 && globalSelectionEnd != -1) {
                val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
                val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
                canvas.drawPath(highlightPath, selectedWordPaint)

                drawHandle(canvas, allWords[start].bounds.left, allWords[start].bounds.top, true, sx, sy)
                drawHandle(canvas, allWords[end].bounds.right, allWords[end].bounds.bottom, false, sx, sy)

                // Re-scale canvas to identity for UI components so they don't get stretched
                canvas.scale(1/sx, 1/sy)
                tempRect.set(encompassingRect.left * sx, encompassingRect.top * sy, encompassingRect.right * sx, encompassingRect.bottom * sy)
                drawFloatingToolbar(canvas, tempRect)
            }
            canvas.restoreToCount(saveCount)
        }

        private fun drawHandle(canvas: Canvas, x: Float, y: Float, isStart: Boolean, sx: Float = 1f, sy: Float = 1f) {
            canvas.drawCircle(x, y, 18f / sx, handlePaint)
            if (isStart) canvas.drawRect(x - 2f / sx, y, x + 2f / sx, y + 40f / sy, handlePaint)
            else canvas.drawRect(x - 2f / sx, y - 40f / sy, x + 2f / sx, y, handlePaint)
        }

        private fun drawFloatingToolbar(canvas: Canvas, anchor: RectF) {
            val buttonLabels = listOf("Copy", "Share", "Translate", "All", "Cancel")
            val btnPadding = 16f * density
            val btnHeight = 36f * density
            val btnSpacing = 6f * density
            val m = 10f * density
            
            toolbarActionPaint.textSize = 30f
            val dragHandleWidth = 24f * density
            
            if (!toolbarInitialized) {
                val labelWidths = buttonLabels.map { toolbarActionPaint.measureText(it) + btnPadding * 2 }
                val totalWidth = labelWidths.sum() + (buttonLabels.size - 1) * btnSpacing + m * 2 + dragHandleWidth + btnSpacing
                val tx = ((width - totalWidth) / 2)
                toolbarOffsetY = anchor.top - (btnHeight + m * 2) - 32f
                if (toolbarOffsetY < 150f) toolbarOffsetY = anchor.bottom + 32f
                
                var currentX = tx + m + dragHandleWidth + btnSpacing
                val newButtons = mutableListOf<ToolbarButton>()
                buttonLabels.forEachIndexed { i, label ->
                    val bWidth = labelWidths[i]
                    newButtons.add(ToolbarButton(label, Rect(currentX.toInt(), 0, (currentX + bWidth).toInt(), 0)))
                    currentX += bWidth + btnSpacing
                }
                toolbarButtons = newButtons
                toolbarInitialized = true
                toolbarRect.set(tx, 0f, tx + totalWidth, 0f)
            }
            
            val ty = toolbarOffsetY
            val tx = toolbarRect.left + toolbarOffsetX
            val totalWidth = toolbarRect.width()
            
            toolbarRect.set(tx, ty, tx + totalWidth, ty + btnHeight + m * 2)
            
            canvas.drawRoundRect(toolbarRect, 22f * density, 22f * density, shadowPaint)
            toolbarBgPaint.color = dynamicSurface
            canvas.drawRoundRect(toolbarRect, 22f * density, 22f * density, toolbarBgPaint)

            val dx = tx + m
            dragHandleRect.set(dx, ty + m, dx + dragHandleWidth, ty + m + btnHeight)
            canvas.drawRoundRect(dx + 8f * density, ty + m + 8f * density, dx + 16f * density, ty + m + btnHeight - 8f * density, 4f * density, 4f * density, hPaint)

            val fontMetrics = btnTextPaint.fontMetrics
            val textOffset = ((fontMetrics.descent - fontMetrics.ascent) / 2) - fontMetrics.descent

            toolbarButtons.forEach { btn ->
                val btnW = btn.rect.width().toFloat()
                val startX = btn.rect.left.toFloat() + toolbarOffsetX
                tempBtnRect.set(startX, ty + m, startX + btnW, ty + m + btnHeight)
                
                canvas.drawRoundRect(tempBtnRect, btnHeight / 2, btnHeight / 2, btnPaint)
                canvas.drawText(btn.label, tempBtnRect.centerX(), tempBtnRect.centerY() + textOffset, btnTextPaint)
                
                // Update rect for touch events
                btn.rect.set(tempBtnRect.left.toInt(), tempBtnRect.top.toInt(), tempBtnRect.right.toInt(), tempBtnRect.bottom.toInt())
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val lx = event.x; val ly = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = lx; lastTouchY = ly
                    for (btn in toolbarButtons) {
                        if (Rect(btn.rect).apply { inset(-24, -24) }.contains(lx.toInt(), ly.toInt())) {
                            handleToolbarAction(btn.label); return true
                        }
                    }
                    if (dragHandleRect.contains(lx, ly)) { isDraggingToolbar = true; return true }
                    if (globalSelectionStart != -1) {
                        val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
                        val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
                        val startLocal = scaleRect(allWords[start].bounds)
                        val endLocal = scaleRect(allWords[end].bounds)
                        if (isPointNear(lx, ly, startLocal.left, startLocal.top)) { dragHandleType = 1; return true }
                        if (isPointNear(lx, ly, endLocal.right, endLocal.bottom)) { dragHandleType = 2; return true }
                    }
                    val nearest = findNearestWordGlobal(lx, ly)
                    if (nearest != -1) {
                        updateSelection(nearest, nearest)
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); return true
                    } else { 
                        updateSelection(-1, -1)
                        return false 
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = lx - lastTouchX; val dy = ly - lastTouchY
                    lastTouchX = lx; lastTouchY = ly
                    if (isDraggingToolbar) { toolbarOffsetX += dx; toolbarOffsetY += dy; invalidate(); return true }
                    if (dragHandleType != 0) {
                        val nearest = findNearestWordGlobal(lx, ly)
                        if (nearest != -1) {
                            if (dragHandleType == 1) updateSelection(nearest, globalSelectionEnd) else updateSelection(globalSelectionStart, nearest)
                        }
                        return true
                    } else if (globalSelectionStart != -1) {
                        val nearest = findNearestWordGlobal(lx, ly)
                        if (nearest != -1) { updateSelection(globalSelectionStart, nearest); return true }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingToolbar = false; dragHandleType = 0; invalidate()
                }
            }
            return true
        }

        private fun findNearestWordGlobal(sx: Float, sy: Float): Int {
            allWords.forEachIndexed { idx, word ->
                val scaled = scaleRect(word.bounds)
                val expanded = RectF(scaled).apply { inset(-30f, -30f) }
                if (expanded.contains(sx, sy)) return idx
            }
            return -1
        }

        private fun isPointNear(px: Float, py: Float, x: Float, y: Float): Boolean {
            val dx = px - x; val dy = py - y
            return dx * dx + dy * dy < 80 * 80
        }

        private fun handleToolbarAction(label: String) {
            val start = globalSelectionStart.coerceAtMost(globalSelectionEnd)
            val end = globalSelectionStart.coerceAtLeast(globalSelectionEnd)
            if (start == -1) return
            val selectedText = (start..end).mapNotNull { allWords.getOrNull(it) }.joinToString(" ") { it.text }

            when (label) {
                "Copy" -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", selectedText))
                    Toast.makeText(context, "Text copied ✓", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                "Share" -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, selectedText); addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share text via").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
                    dismiss()
                }
                "All" -> { globalSelectionStart = 0; globalSelectionEnd = allWords.lastIndex; invalidate() }
                "Cancel" -> { globalSelectionStart = -1; globalSelectionEnd = -1; invalidate() }
                "Translate" -> { openUrl(context, "https://translate.google.com/?text=${Uri.encode(selectedText)}") }
            }
        }
    }

    private fun openUrl(context: Context, url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://" + finalUrl
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }
}
