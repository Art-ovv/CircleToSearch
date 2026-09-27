/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.akslabs.circletosearch.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Rect
import com.akslabs.circletosearch.CircleToSearchAccessibilityService
import com.akslabs.circletosearch.ui.components.SmartEntity
import com.akslabs.circletosearch.ui.components.extractRegionText
import android.util.Base64
import android.view.Display
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import com.akslabs.circletosearch.data.AssistDataRepository
import com.akslabs.circletosearch.data.SearchEngine
import com.akslabs.circletosearch.ui.components.searchWithGoogleLens
import com.akslabs.circletosearch.ui.components.mergeTextNodes
import com.akslabs.circletosearch.ui.components.mergeRegionTextNodes
import com.akslabs.circletosearch.ui.theme.OverlayGradientColors
import com.akslabs.circletosearch.utils.ImageSearchUploader
import com.akslabs.circletosearch.utils.ImageUtils
import com.akslabs.circletosearch.utils.QrResult
import com.akslabs.circletosearch.utils.QrResultWithBounds
import com.akslabs.circletosearch.utils.QrScanner
import com.akslabs.circletosearch.ui.qrResultShortLabel
import com.akslabs.circletosearch.utils.UIPreferences
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.tasks.await
import androidx.webkit.WebViewFeature
import com.akslabs.circletosearch.data.isDirectUpload
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.os.Build
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Surface

private const val FULL_SCREEN_OCR_TIMEOUT_MS = 25_000L
private const val REGION_OCR_TIMEOUT_MS = 10_000L
private val GENERIC_ASSIST_CONTAINER_LABELS = setOf(
    "photo",
    "image",
    "picture",
    "video",
    "media",
    "фото",
    "фотография",
    "изображение",
    "видео",
    "медиа",
)

private fun isGenericAssistContainerLabel(text: String): Boolean =
    text.trim().lowercase() in GENERIC_ASSIST_CONTAINER_LABELS

private class SelectionRuntimeState {
    var isResizing = false
    var activeHandle: String? = null
    var preResizeRect: Rect? = null
    var cropJob: Job? = null
    var textJob: Job? = null
    var qrJob: Job? = null
    var backgroundGestureStart: Offset? = null
    var isBackgroundGestureDragging = false
}

internal fun mapViewRectToBitmap(
    viewRect: Rect,
    viewportWidth: Int,
    viewportHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Rect {
    if (
        viewportWidth <= 0 || viewportHeight <= 0 ||
        bitmapWidth <= 0 || bitmapHeight <= 0
    ) return Rect()
    val scaleX = bitmapWidth.toFloat() / viewportWidth
    val scaleY = bitmapHeight.toFloat() / viewportHeight
    return Rect().apply {
        left = kotlin.math.floor(viewRect.left * scaleX).toInt().coerceIn(0, bitmapWidth)
        top = kotlin.math.floor(viewRect.top * scaleY).toInt().coerceIn(0, bitmapHeight)
        right = kotlin.math.ceil(viewRect.right * scaleX).toInt().coerceIn(0, bitmapWidth)
        bottom = kotlin.math.ceil(viewRect.bottom * scaleY).toInt().coerceIn(0, bitmapHeight)
    }
}

private fun copyDetectedCode(
    context: android.content.Context,
    rawText: String,
    format: com.google.zxing.BarcodeFormat?,
) {
    val clipboard = context.getSystemService(
        android.content.Context.CLIPBOARD_SERVICE,
    ) as android.content.ClipboardManager
    clipboard.setPrimaryClip(
        android.content.ClipData.newPlainText(
            QrScanner.formatDisplayName(format),
            rawText,
        )
    )
    android.widget.Toast.makeText(
        context,
        "${QrScanner.formatDisplayName(format)} copied",
        android.widget.Toast.LENGTH_SHORT,
    ).show()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CircleToSearchScreen(
    screenshot: Bitmap?,
    preparedTextNodes: List<com.akslabs.circletosearch.ui.components.TextNode>? = null,
    onClose: () -> Unit,
    searchModeOverride: Boolean? = null,
    assistToken: String? = null,
    copyTextManager: com.akslabs.circletosearch.ui.components.CopyTextOverlayManager? = null,
    onCopyText: () -> Unit = {},
    onExitCopyMode: () -> Unit = {},
    onTranslate: () -> Unit = {},
    onTextAnalysisUpdate: (
        Bitmap?,
        List<com.akslabs.circletosearch.ui.components.TextNode>,
        Boolean,
    ) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Initialize preferences
    val uiPreferences = remember { UIPreferences(context) }
    
    // Reactive search method state — observed via flow so it is always in sync
    // with whatever set it (home screen, settings sheet, or accessibility actions).
    // Reading from the flow avoids the SharedPreferences in-memory cache lag
    // that occurs when a write comes from a different activity instance.
    val isGoogleLensOnly by uiPreferences.observeUseGoogleLensOnly()
        .collectAsState(initial = uiPreferences.isUseGoogleLensOnly())
    
    // Effective state: use override if provided by trigger, else use global preference
    val effectiveLensOnly = searchModeOverride ?: isGoogleLensOnly
    
    
    // New Sheet States

    // Material You logic for colors
    val isDark = isSystemInDarkTheme()
    val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColor && isDark -> dynamicDarkColorScheme(context)
        dynamicColor && !isDark -> dynamicLightColorScheme(context)
        else -> MaterialTheme.colorScheme // Fallback standard
    }
    //Colors for dark theme
    val barBgColor = if (isDark) Color(0xFF0D0D0D) else colorScheme.surface
    val bubbleColor = if (isDark) Color(0xFF1F1F1F) else colorScheme.secondaryContainer.copy(alpha = 0.9f)
    val contentColor = if (isDark) Color.White else colorScheme.onSecondaryContainer

    //Haptic feedback
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    //Slide-in animation
    var isUIVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isUIVisible = true // Déclenche l'animation à l'ouverture
    }

    // Search Engines Order Logic
    val preferredOrder = remember(uiPreferences.getSearchEngineOrder()) {
        val allEngines = SearchEngine.values()
        val orderString = uiPreferences.getSearchEngineOrder()
        if (orderString == null) allEngines
        else {
            val preferredNames = orderString.split(",")
            val ordered = mutableListOf<SearchEngine>()
            preferredNames.forEach { name ->
                allEngines.find { it.name == name }?.let { ordered.add(it) }
            }
            allEngines.forEach { if (!ordered.contains(it)) ordered.add(it) }
            ordered
        }
    }
    val searchEngines = preferredOrder
    
    // Support Settings Sheet
    var showSettingsScreen by remember { mutableStateOf(false) }


    
    // High-frequency gesture/job bookkeeping is deliberately not Compose state.
    val selectionRuntime = remember(screenshot) { SelectionRuntimeState() }
    
    // QR Scanner state
    var showQrSheet by remember(screenshot) { mutableStateOf(false) }
    var qrScanBitmap by remember(screenshot) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var detectedQrCodes by remember(screenshot) { mutableStateOf<List<QrResultWithBounds>>(emptyList()) }
    var selectedQrResult by remember(screenshot) { mutableStateOf<QrResultWithBounds?>(null) }
    var isAnalyzingText by remember(screenshot) { mutableStateOf(false) }
    var isTextAnalysisComplete by remember(screenshot) { mutableStateOf(false) }
    var textPipelineReadyForQr by remember(screenshot) { mutableStateOf(false) }
    var qrRestartGeneration by remember(screenshot) { mutableStateOf(0L) }
    
    var isCopyMode by remember { mutableStateOf(false) }
    var detectedTextEntities by remember(screenshot) { mutableStateOf<List<SmartEntity>>(emptyList()) }
    var ocrTextNodes by remember(screenshot) {
        mutableStateOf<List<com.akslabs.circletosearch.ui.components.TextNode>>(emptyList())
    }
    val assistSnapshot by AssistDataRepository.snapshot.collectAsState()
    val scanGeneration = remember { AtomicLong(0L) }
    var showTranslationLangDialog by remember { mutableStateOf(false) }
    val detectedEntities = remember(detectedTextEntities, detectedQrCodes) {
        detectedTextEntities + detectedQrCodes.mapNotNull { qr ->
            qr.bounds?.let { bounds ->
                SmartEntity.QrCode(
                    qrResult = qr.result,
                    rawText = qr.rawText,
                    bounds = bounds,
                    format = qr.format,
                )
            }
        }
    }
    val unpositionedQrCodes = remember(detectedQrCodes) {
        detectedQrCodes.filter { it.bounds == null }
    }

    val matchingAssistSnapshot = remember(assistSnapshot, assistToken) {
        assistSnapshot.takeIf { snapshot ->
            assistToken != null && snapshot.token == assistToken && snapshot.ready
        }
    }
    var mergedTextNodes by remember(screenshot) {
        mutableStateOf<List<com.akslabs.circletosearch.ui.components.TextNode>>(emptyList())
    }
    val currentOnTextAnalysisUpdate by rememberUpdatedState(onTextAnalysisUpdate)
    LaunchedEffect(
        ocrTextNodes,
        matchingAssistSnapshot,
        screenshot,
        isTextAnalysisComplete,
        copyTextManager,
    ) {
        val source = screenshot
        if (source == null) {
            mergedTextNodes = emptyList()
            currentOnTextAnalysisUpdate(null, emptyList(), false)
        } else {
            val ocrSnapshot = ocrTextNodes
            val assist = matchingAssistSnapshot
            val mergedNodes = withContext(Dispatchers.Default) {
                mergeTextNodes(
                    assistNodes = assist?.nodes.orEmpty(),
                    ocrNodes = ocrSnapshot,
                    bitmapWidth = source.width,
                    bitmapHeight = source.height,
                    assistCoordinateWidth = assist?.coordinateWidth ?: source.width,
                    assistCoordinateHeight = assist?.coordinateHeight ?: source.height,
                )
            }
            currentCoroutineContext().ensureActive()
            mergedTextNodes = mergedNodes
            copyTextManager?.updateNodes(mergedNodes)
            currentOnTextAnalysisUpdate(source, mergedNodes, isTextAnalysisComplete)
        }
    }
    



    // Search State
    var selectedEngine by remember(searchEngines) { mutableStateOf<SearchEngine>(searchEngines.first()) }
    var searchUrl by remember { mutableStateOf<String?>(null) }
    var hostedImageUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    // Desktop Mode - Per Tab
    // We use a set to track which engines are in desktop mode
    val initialDesktopMode = uiPreferences.isDesktopMode() // Global default
    var desktopModeEngines by remember { mutableStateOf<Set<SearchEngine>>(if(initialDesktopMode) searchEngines.toSet() else emptySet()) }
    
    var isDarkMode by remember { mutableStateOf(uiPreferences.isDarkMode()) }
    var showGradientBorder by remember { mutableStateOf(uiPreferences.isShowGradientBorder()) }
    
    // Back gesture handler for QR sheet
    // We use a high-priority check or ensure it's at the very top of the hierarchy
    BackHandler(enabled = showQrSheet) {
        showQrSheet = false
        selectedQrResult = null
    }

    // Track initialized engines for Smart Loading
    val initializedEngines = remember { mutableStateListOf<SearchEngine>() }
    
    // Save global preference if user toggles it for the MAIN engine (Google) - Optional choice, 
    // or we just keep it per session. Let's keep it simple: no auto-save of per-tab state to verify complex persistence yet.
    // simpler: If user toggles, we just update the state.
    
    // Helper to check desktop mode
    fun isDesktop(engine: SearchEngine) = desktopModeEngines.contains(engine)
    
    // Removed auto-save of isDesktopMode for now as it is complex with per-tab
    // We could save "If ALL are desktop" or just the active one? 
    // User requested "depending on opened tab", so per-session state is safer.
    
    LaunchedEffect(isDarkMode) {
        uiPreferences.setDarkMode(isDarkMode)
    }
    
    LaunchedEffect(showGradientBorder) {
        uiPreferences.setShowGradientBorder(showGradientBorder)
    }
    
    // Cache for preloaded URLs to avoid re-uploading/re-generating
    val preloadedUrls = remember { mutableMapOf<SearchEngine, String>() }
    
    // WebView Cache
    val webViews = remember { mutableMapOf<SearchEngine, WebView>() }
    
    // Update User Agent dynamically when desktop mode changes for a specific engine
    // This is now handled in the AndroidView update block or individual engine effects
    // BUT we need to force reload if the state changes.
    LaunchedEffect(desktopModeEngines) {
        webViews.forEach { (engine, wv) ->
             val isDesktop = desktopModeEngines.contains(engine)
             val newUserAgent = if (isDesktop) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            
            if (wv.settings.userAgentString != newUserAgent) {
                wv.settings.userAgentString = newUserAgent
                wv.reload()
                android.util.Log.d("CircleToSearch", "Desktop mode changed for $engine - Reloaded")
            }
        }
    }
    
    // Update WebViews when dark mode changes
    LaunchedEffect(isDarkMode) {
        webViews.values.forEach { wv ->
            try {
                // Update WebViewClient for dark mode
                if (isDarkMode) {
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val darkModeCSS = """
                                javascript:(function() {
                                    var style = document.createElement('style');
                                    style.innerHTML = `
                                        html { filter: invert(1) hue-rotate(180deg) !important; background: #000 !important; }
                                        img, video, [style*="background-image"] { filter: invert(1) hue-rotate(180deg) !important; }
                                    `;
                                    document.head.appendChild(style);
                                })()
                            """.trimIndent()
                            view?.loadUrl(darkModeCSS)
                        }
                    }
                } else {
                    wv.webViewClient = android.webkit.WebViewClient()
                }
                wv.reload()
                android.util.Log.d("CircleToSearch", "Dark mode changed to: $isDarkMode - Reloaded WebViews")
            } catch (e: Exception) {
                android.util.Log.e("CircleToSearch", "Error updating dark mode", e)
            }
        }
    }
    
    // Bottom Sheet State
    val scaffoldState = androidx.compose.material3.rememberBottomSheetScaffoldState(
        bottomSheetState = androidx.compose.material3.rememberStandardBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.Hidden,
            skipHiddenState = false
        )
    )

    // Drawing State
    val currentPathPoints = remember { mutableStateListOf<Offset>() }
    
    // Selection State
    val imageViewportSize = remember(screenshot) {
        mutableStateOf(
            screenshot?.let { IntSize(it.width, it.height) } ?: IntSize.Zero,
        )
    }
    var selectedBitmap by remember(screenshot) { mutableStateOf<Bitmap?>(null) }
    val selectionBitmapConsumers = remember(screenshot) { IdentityHashMap<Bitmap, Int>() }
    val selectionBitmapOwnerActive = remember(screenshot) { AtomicBoolean(true) }
    val selectionCropGeneration = remember(screenshot) { AtomicLong(0L) }
    var isSearching by remember { mutableStateOf(false) }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }
    var committedSelectionRect by remember(screenshot) { mutableStateOf<Rect?>(null) }
    var isSelectionReady by remember(screenshot) { mutableStateOf(false) }
    val selectedRegionText = remember(
        committedSelectionRect,
        mergedTextNodes,
        ocrTextNodes,
        imageViewportSize.value,
        screenshot,
    ) {
        committedSelectionRect?.let { viewRegion ->
            val source = screenshot ?: return@let ""
            val viewport = imageViewportSize.value
            val region = mapViewRectToBitmap(
                viewRect = viewRegion,
                viewportWidth = viewport.width,
                viewportHeight = viewport.height,
                bitmapWidth = source.width,
                bitmapHeight = source.height,
            )
            val ocrText = extractRegionText(region, ocrTextNodes)
            if (ocrText.isNotBlank()) {
                ocrText
            } else {
                val ocrIds = ocrTextNodes.asSequence().map { it.id }.toHashSet()
                extractRegionText(
                    region,
                    mergedTextNodes.filter { node ->
                        node.id in ocrIds || !isGenericAssistContainerLabel(node.fullText)
                    },
                )
            }
        }.orEmpty()
    }
    var isRefiningSelectionText by remember(screenshot) { mutableStateOf(false) }
    val selectionTextGeneration = remember(screenshot) { AtomicLong(0L) }
    val fullTextScanRunning = remember(screenshot) { AtomicBoolean(false) }
    var pendingSelectionTextRect by remember(screenshot) { mutableStateOf<Rect?>(null) }
    val selectionAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val renderDensity = androidx.compose.ui.platform.LocalDensity.current

    // Drawing resources are retained across frames. Gesture callbacks mutate the
    // trail path; the Canvas only consumes it and never allocates a Path/Paint/Brush.
    val selectionTrailPath = remember { Path() }
    val selectionTrailBrush = remember {
        Brush.linearGradient(
            colors = OverlayGradientColors + OverlayGradientColors.first(),
        )
    }
    val selectionTrailCoreStroke = remember(renderDensity) {
        Stroke(
            width = with(renderDensity) { 7.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val selectionTrailHighlightStroke = remember(renderDensity) {
        Stroke(
            width = with(renderDensity) { 2.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val selectionTrailGlowPaint = remember(renderDensity) {
        androidx.compose.ui.graphics.Paint().apply {
            color = Color(0xFF8AB4F8)
            alpha = 0.78f
            isAntiAlias = true
            style = PaintingStyle.Stroke
            strokeWidth = with(renderDensity) { 15.dp.toPx() }
            strokeCap = StrokeCap.Round
            strokeJoin = StrokeJoin.Round
            asFrameworkPaint().maskFilter = BlurMaskFilter(
                with(renderDensity) { 9.dp.toPx() },
                BlurMaskFilter.Blur.NORMAL,
            )
        }
    }
    val selectionBracketPaths = remember { List(4) { Path() } }
    val selectionHolePath = remember { Path() }
    val selectionBracketStroke = remember(renderDensity) {
        Stroke(
            width = with(renderDensity) { 4.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val selectionFlashStroke = remember(renderDensity) {
        Stroke(width = with(renderDensity) { 1.5.dp.toPx() })
    }
    val tintOverlayBrush = remember {
        Brush.verticalGradient(
            colors = OverlayGradientColors.map { it.copy(alpha = 0.15f) },
        )
    }

    fun retainSelectionBitmap(bitmap: Bitmap): Boolean {
        if (!selectionBitmapOwnerActive.get() || bitmap.isRecycled) return false
        selectionBitmapConsumers[bitmap] = (selectionBitmapConsumers[bitmap] ?: 0) + 1
        return true
    }

    fun recycleSelectionBitmapIfIdle(bitmap: Bitmap) {
        if (
            selectionBitmapConsumers.containsKey(bitmap) ||
            bitmap === screenshot ||
            (selectionBitmapOwnerActive.get() && bitmap === selectedBitmap) ||
            bitmap.isRecycled
        ) {
            return
        }
        bitmap.recycle()
    }

    fun releaseSelectionBitmap(bitmap: Bitmap) {
        val remaining = (selectionBitmapConsumers[bitmap] ?: 0) - 1
        if (remaining > 0) {
            selectionBitmapConsumers[bitmap] = remaining
            return
        }
        selectionBitmapConsumers.remove(bitmap)
        if (
            bitmap !== screenshot &&
            (!selectionBitmapOwnerActive.get() || bitmap !== selectedBitmap) &&
            !bitmap.isRecycled
        ) {
            bitmap.recycle()
        }
    }

    fun updateSelectionCrop(rect: Rect) {
        val source = screenshot ?: return
        val requestedViewRect = Rect(rect)
        val requestedViewport = imageViewportSize.value
        val requestedSourceRect = mapViewRectToBitmap(
            viewRect = requestedViewRect,
            viewportWidth = requestedViewport.width,
            viewportHeight = requestedViewport.height,
            bitmapWidth = source.width,
            bitmapHeight = source.height,
        )
        if (
            requestedSourceRect.right <= requestedSourceRect.left ||
            requestedSourceRect.bottom <= requestedSourceRect.top
        ) return
        val generation = selectionCropGeneration.incrementAndGet()
        selectionRuntime.cropJob?.cancel()
        val displacedBitmap = selectedBitmap
        selectedBitmap = null
        displacedBitmap?.let(::recycleSelectionBitmapIfIdle)
        selectionRuntime.cropJob = scope.launch(Dispatchers.Default) {
            val cropped = ImageUtils.cropBitmap(source, requestedSourceRect)
            // Bitmap.createBitmap itself cannot be interrupted. Always cross
            // back to Main in a non-cancellable cleanup section so a result
            // created concurrently with cancellation is never leaked.
            withContext(Dispatchers.Main.immediate + NonCancellable) {
                if (
                    selectionCropGeneration.get() == generation &&
                    screenshot === source &&
                    selectionRect == requestedViewRect &&
                    imageViewportSize.value == requestedViewport &&
                    cropped != null
                ) {
                    selectedBitmap = cropped
                } else if (cropped != null) {
                    // Bitmap.createBitmap() is allowed to return its source for
                    // an exact full-frame crop. Identity-aware cleanup must never
                    // recycle the screenshot still used by Image/OCR.
                    recycleSelectionBitmapIfIdle(cropped)
                }
            }
        }
    }

    fun requestRegionTextRefinement(rect: Rect) {
        val source = screenshot ?: return
        val requestedViewRect = Rect(rect)
        val requestedViewport = imageViewportSize.value
        val requestedSourceRect = mapViewRectToBitmap(
            viewRect = requestedViewRect,
            viewportWidth = requestedViewport.width,
            viewportHeight = requestedViewport.height,
            bitmapWidth = source.width,
            bitmapHeight = source.height,
        )
        if (
            requestedSourceRect.right <= requestedSourceRect.left ||
            requestedSourceRect.bottom <= requestedSourceRect.top
        ) return
        if (fullTextScanRunning.get()) {
            // Do not let an early interaction cancel the full-screen primary
            // scan. Run the latest requested ROI immediately after that scan
            // releases PaddleOCR's serialized inference pipeline.
            pendingSelectionTextRect = requestedViewRect
            isRefiningSelectionText = true
            return
        }

        pendingSelectionTextRect = null
        val generation = selectionTextGeneration.incrementAndGet()
        selectionRuntime.textJob?.cancel()
        val qrJobToAwait = selectionRuntime.qrJob?.takeIf { it.isActive }
        if (qrJobToAwait != null) {
            // ZXing and PaddleOCR both allocate large pixel buffers. Stop and
            // fully join an active barcode pass before starting interactive OCR;
            // a fresh QR pass is scheduled after this ROI completes.
            qrJobToAwait.cancel()
            qrRestartGeneration++
        }
        isRefiningSelectionText = true
        selectionRuntime.textJob = scope.launch {
            try {
                qrJobToAwait?.join()
                // A visible primary result may still contain only one mistaken
                // icon/word from a multi-line selection. Always give an explicit
                // user region one bounded high-resolution refinement pass.
                val refinedNodes = withTimeout(REGION_OCR_TIMEOUT_MS) {
                    com.akslabs.circletosearch.ocr.PaddleOcrEngine
                        .extractTextInRegion(
                            context = context.applicationContext,
                            bitmap = source,
                            sourceRegion = android.graphics.RectF(requestedSourceRect),
                        )
                }
                if (
                    selectionTextGeneration.get() == generation &&
                    screenshot === source &&
                    selectionRect == requestedViewRect &&
                    imageViewportSize.value == requestedViewport
                ) {
                    ocrTextNodes = mergeRegionTextNodes(
                        existingNodes = ocrTextNodes,
                        refinedNodes = refinedNodes,
                        sourceRegion = requestedSourceRect,
                    )
                }
            } catch (error: TimeoutCancellationException) {
                android.util.Log.w(
                    "CircleToSearch",
                    "Targeted OCR exceeded ${REGION_OCR_TIMEOUT_MS}ms",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w(
                    "CircleToSearch",
                    "Targeted text refinement failed",
                    error,
                )
            } finally {
                if (selectionTextGeneration.get() == generation) {
                    isRefiningSelectionText = false
                    selectionRuntime.textJob = null
                }
            }
        }
    }

    fun runPendingSelectionRefinement() {
        val pendingRegion = pendingSelectionTextRect
        pendingSelectionTextRect = null
        if (pendingRegion != null && selectionRect == pendingRegion) {
            isRefiningSelectionText = false
            requestRegionTextRefinement(pendingRegion)
        } else if (pendingRegion != null) {
            isRefiningSelectionText = false
        }
    }

    fun beginRegionGesture(offset: Offset) {
        isUIVisible = false
        val rect = selectionRect
        if (rect != null && isSelectionReady) {
            val handleSize = 64f
            val tl = Offset(rect.left.toFloat(), rect.top.toFloat())
            val tr = Offset(rect.right.toFloat(), rect.top.toFloat())
            val bl = Offset(rect.left.toFloat(), rect.bottom.toFloat())
            val br = Offset(rect.right.toFloat(), rect.bottom.toFloat())
            when {
                (offset - tl).getDistance() < handleSize -> {
                    selectionRuntime.isResizing = true
                    selectionRuntime.activeHandle = "tl"
                }
                (offset - tr).getDistance() < handleSize -> {
                    selectionRuntime.isResizing = true
                    selectionRuntime.activeHandle = "tr"
                }
                (offset - bl).getDistance() < handleSize -> {
                    selectionRuntime.isResizing = true
                    selectionRuntime.activeHandle = "bl"
                }
                (offset - br).getDistance() < handleSize -> {
                    selectionRuntime.isResizing = true
                    selectionRuntime.activeHandle = "br"
                }
                else -> {
                    selectionRuntime.isResizing = false
                    selectionRuntime.activeHandle = null
                }
            }
            if (selectionRuntime.isResizing) {
                selectionRuntime.preResizeRect = Rect(rect)
                isSelectionReady = false
                return
            }
            selectionRuntime.preResizeRect = null
        }

        currentPathPoints.clear()
        currentPathPoints.add(offset)
        selectionTrailPath.reset()
        selectionTrailPath.moveTo(offset.x, offset.y)
        selectionRect = null
        committedSelectionRect = null
        isSelectionReady = false
        updateSelectionBracketPaths(selectionBracketPaths, null)
        updateSelectionHolePath(selectionHolePath, null)
        scope.launch { selectionAnim.snapTo(0f) }
    }

    fun updateRegionGesture(position: Offset) {
        if (selectionRuntime.isResizing && selectionRuntime.activeHandle != null) {
            val rect = selectionRect ?: return
            val newRect = android.graphics.Rect(rect)
            val viewport = imageViewportSize.value
            val maximumX = viewport.width.takeIf { it > 0 } ?: return
            val maximumY = viewport.height.takeIf { it > 0 } ?: return
            val clampedX = position.x.toInt().coerceIn(0, maximumX)
            val clampedY = position.y.toInt().coerceIn(0, maximumY)
            when (selectionRuntime.activeHandle) {
                "tl" -> {
                    newRect.left = clampedX
                    newRect.top = clampedY
                }
                "tr" -> {
                    newRect.right = clampedX
                    newRect.top = clampedY
                }
                "bl" -> {
                    newRect.left = clampedX
                    newRect.bottom = clampedY
                }
                "br" -> {
                    newRect.right = clampedX
                    newRect.bottom = clampedY
                }
            }
            if (newRect.width() > 20 && newRect.height() > 20) {
                selectionRect = newRect
                updateSelectionBracketPaths(selectionBracketPaths, newRect)
                updateSelectionHolePath(selectionHolePath, newRect)
            }
        } else {
            currentPathPoints.add(position)
            selectionTrailPath.lineTo(position.x, position.y)
        }
    }

    fun cancelRegionGesture() {
        currentPathPoints.clear()
        selectionTrailPath.reset()
        if (selectionRuntime.isResizing) {
            // ACTION_CANCEL is not a commit. Restore the rectangle whose crop
            // and OCR text are still backing the visible action buttons.
            selectionRuntime.preResizeRect?.let { original ->
                selectionRect = Rect(original)
                committedSelectionRect = Rect(original)
                updateSelectionBracketPaths(selectionBracketPaths, original)
                updateSelectionHolePath(selectionHolePath, original)
                // A crop for this rectangle may have become stale while the
                // cancelled resize was moving. Recreate it only when no valid
                // selected bitmap survived, and refresh ROI text defensively.
                if (screenshot != null && selectedBitmap == null) {
                    updateSelectionCrop(original)
                }
                requestRegionTextRefinement(original)
            }
            isSelectionReady = true
        }
        selectionRuntime.isResizing = false
        selectionRuntime.activeHandle = null
        selectionRuntime.preResizeRect = null
        isUIVisible = true
    }

    fun finishRegionGesture() {
        isUIVisible = true
        if (selectionRuntime.isResizing) {
            selectionRuntime.isResizing = false
            selectionRuntime.activeHandle = null
            selectionRuntime.preResizeRect = null
            if (screenshot != null && selectionRect != null) {
                val committed = Rect(selectionRect!!)
                committedSelectionRect = committed
                isSelectionReady = true
                updateSelectionCrop(committed)
                requestRegionTextRefinement(committed)
            }
        } else if (currentPathPoints.isNotEmpty()) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            currentPathPoints.forEach { point ->
                minX = kotlin.math.min(minX, point.x)
                minY = kotlin.math.min(minY, point.y)
                maxX = kotlin.math.max(maxX, point.x)
                maxY = kotlin.math.max(maxY, point.y)
            }

            val border = 20
            val viewport = imageViewportSize.value
            val viewportWidth = viewport.width.takeIf { it > 0 } ?: screenshot?.width ?: 0
            val viewportHeight = viewport.height.takeIf { it > 0 } ?: screenshot?.height ?: 0
            val rect = android.graphics.Rect(
                (minX - border).toInt().coerceAtLeast(0),
                (minY - border).toInt().coerceAtLeast(0),
                (maxX + border).toInt().coerceAtMost(viewportWidth),
                (maxY + border).toInt().coerceAtMost(viewportHeight),
            )
            if (rect.width() > 10 && rect.height() > 10) {
                selectionRect = rect
                committedSelectionRect = Rect(rect)
                isSelectionReady = false
                updateSelectionBracketPaths(selectionBracketPaths, rect)
                updateSelectionHolePath(selectionHolePath, rect)
                currentPathPoints.clear()
                if (screenshot != null) {
                    updateSelectionCrop(rect)
                    requestRegionTextRefinement(rect)
                }
                scope.launch {
                    selectionAnim.animateTo(1f, tween(600))
                    isSelectionReady = true
                }
            }
        }
        currentPathPoints.clear()
        selectionTrailPath.reset()
    }

    val backgroundTouchSlop = with(renderDensity) { 8.dp.toPx() }
    fun handleBackgroundTouch(action: Int, x: Float, y: Float) {
        val position = Offset(x, y)
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                selectionRuntime.backgroundGestureStart = position
                selectionRuntime.isBackgroundGestureDragging = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val start = selectionRuntime.backgroundGestureStart ?: return
                if (
                    !selectionRuntime.isBackgroundGestureDragging &&
                    (position - start).getDistance() >= backgroundTouchSlop
                ) {
                    selectionRuntime.isBackgroundGestureDragging = true
                    beginRegionGesture(start)
                }
                if (selectionRuntime.isBackgroundGestureDragging) updateRegionGesture(position)
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (selectionRuntime.isBackgroundGestureDragging) {
                    finishRegionGesture()
                } else {
                    isUIVisible = !isUIVisible
                }
                selectionRuntime.backgroundGestureStart = null
                selectionRuntime.isBackgroundGestureDragging = false
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (selectionRuntime.isBackgroundGestureDragging) cancelRegionGesture()
                selectionRuntime.backgroundGestureStart = null
                selectionRuntime.isBackgroundGestureDragging = false
            }
        }
    }
    
    // Lifecycle reset: When screenshot changes, reset selection and modes
    LaunchedEffect(screenshot) {
        if (screenshot != null) {
            selectionCropGeneration.incrementAndGet()
            selectionRuntime.cropJob?.cancel()
            selectionTextGeneration.incrementAndGet()
            selectionRuntime.textJob?.cancel()
            isRefiningSelectionText = false
            selectionRect = null
            committedSelectionRect = null
            pendingSelectionTextRect = null
            isSelectionReady = false
            updateSelectionBracketPaths(selectionBracketPaths, null)
            updateSelectionHolePath(selectionHolePath, null)
            selectedBitmap = null
            isSearching = false
            currentPathPoints.clear()
            selectionTrailPath.reset()
            selectionAnim.snapTo(0f)
        }
    }

    DisposableEffect(screenshot) {
        onDispose {
            selectionBitmapOwnerActive.set(false)
            selectionCropGeneration.incrementAndGet()
            selectionRuntime.cropJob?.cancel()
            selectionTextGeneration.incrementAndGet()
            selectionRuntime.textJob?.cancel()
            selectionRuntime.qrJob?.cancel()
            selectedBitmap?.let(::recycleSelectionBitmapIfIdle)
        }
    }
    
    
    // searchEngines moved to top
    // val searchEngines = SearchEngine.values()

    // Auto-scan the screenshot for text first. OCR owns the largest native and
    // pixel buffers, so QR enrichment is coordinated in a separate effect below.
    LaunchedEffect(screenshot) {
        val generation = scanGeneration.incrementAndGet()
        val source = screenshot
        if (source == null) {
            fullTextScanRunning.set(false)
            isTextAnalysisComplete = false
            textPipelineReadyForQr = false
            detectedQrCodes = emptyList()
            detectedTextEntities = emptyList()
            ocrTextNodes = emptyList()
            isAnalyzingText = false
            return@LaunchedEffect
        }

        if (preparedTextNodes != null) {
            ocrTextNodes = preparedTextNodes
            detectedTextEntities = withContext(Dispatchers.Default) {
                com.akslabs.circletosearch.ocr.PaddleOcrEngine.extractSmartEntities(preparedTextNodes)
            }
            fullTextScanRunning.set(false)
            isAnalyzingText = false
            isTextAnalysisComplete = true
            textPipelineReadyForQr = true
            return@LaunchedEffect
        }

        fullTextScanRunning.set(true)
        isTextAnalysisComplete = false
        textPipelineReadyForQr = false
        isAnalyzingText = true
        detectedQrCodes = emptyList()
        detectedTextEntities = emptyList()
        ocrTextNodes = emptyList()
        var reachedTerminalState = false
        try {
            val extractionResult = withTimeout(FULL_SCREEN_OCR_TIMEOUT_MS) {
                com.akslabs.circletosearch.ocr.PaddleOcrEngine.extractText(
                    context.applicationContext,
                    source,
                    includeQrCodes = false,
                )
            }
            currentCoroutineContext().ensureActive()
            if (scanGeneration.get() == generation) {
                ocrTextNodes = extractionResult.textNodes
                detectedTextEntities = extractionResult.smartEntities
            }
            reachedTerminalState = true
        } catch (error: TimeoutCancellationException) {
            reachedTerminalState = true
            android.util.Log.w(
                "CircleToSearch",
                "Full-screen OCR exceeded ${FULL_SCREEN_OCR_TIMEOUT_MS}ms",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reachedTerminalState = true
            android.util.Log.e("CircleToSearch", "OCR scan failed", error)
        } finally {
            if (scanGeneration.get() == generation) {
                isAnalyzingText = false
                if (reachedTerminalState) {
                    isTextAnalysisComplete = true
                }
            }
            fullTextScanRunning.set(false)
            if (scanGeneration.get() == generation && screenshot === source) {
                runPendingSelectionRefinement()
            }
        }

        // Reaching here means all full-screen PaddleOCR buffers were released.
        if (scanGeneration.get() == generation && screenshot === source) {
            textPipelineReadyForQr = true
        }
    }

    // QR is restartable background enrichment. A later user-requested ROI cancels
    // and joins this job first, preventing ZXing and PaddleOCR full-frame buffers
    // from overlapping; this effect then restarts after the ROI becomes stable.
    LaunchedEffect(screenshot, textPipelineReadyForQr, qrRestartGeneration) {
        val source = screenshot
        if (source == null || !textPipelineReadyForQr) return@LaunchedEffect
        val generation = scanGeneration.get()
        val thisQrJob = currentCoroutineContext()[Job] ?: return@LaunchedEffect
        selectionRuntime.qrJob = thisQrJob
        try {
            while (true) {
                val activeRegionJob = selectionRuntime.textJob ?: break
                activeRegionJob.join()
                if (selectionRuntime.textJob === activeRegionJob) break
            }
            currentCoroutineContext().ensureActive()

            var latestCodes = emptyList<QrResultWithBounds>()
            QrScanner.scanBitmapAll(source)
                .flowOn(Dispatchers.Default)
                .collect { found ->
                    currentCoroutineContext().ensureActive()
                    latestCodes = found
                    if (scanGeneration.get() == generation && screenshot === source) {
                        detectedQrCodes = found
                    }
                }
            if (scanGeneration.get() == generation && screenshot === source) {
                detectedQrCodes = latestCodes
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.e("CircleToSearch", "QR scan failed", error)
        } finally {
            if (selectionRuntime.qrJob === thisQrJob) {
                selectionRuntime.qrJob = null
            }
        }
    }

    // Helper to create and configure WebView
    fun createWebView(ctx: android.content.Context, engine: SearchEngine): WebView {
        return WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            // Caching & Performance - must be set on WebView directly
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Search pages use remote URLs; local image handoff happens outside WebView.
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                setAllowFileAccessFromFileURLs(false)
                @Suppress("DEPRECATION")
                setAllowUniversalAccessFromFileURLs(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                
                // Caching for Speed
                cacheMode = WebSettings.LOAD_DEFAULT // Was LOAD_CACHE_ELSE_NETWORK - caused refresh issues
                
                // Zoom support
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                
                useWideViewPort = true
                loadWithOverviewMode = true
                
                userAgentString = if (isDesktop(engine)) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }
                
                // Dark Mode - Use CSS injection for universal compatibility
                // Algorithmic darkening alone doesn't work on many websites
                android.util.Log.d("CircleToSearch", "Dark mode enabled: $isDarkMode")
            }
            
            // UI Tweaks
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            // Keep normal site cookies, without cross-site cookies in embedded search pages.
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject dark mode CSS if enabled
                    if (isDarkMode) {
                        val darkModeCSS = """
                            javascript:(function() {
                                var style = document.createElement('style');
                                style.innerHTML = `
                                    html { filter: invert(1) hue-rotate(180deg) !important; background: #000 !important; }
                                    img, video, [style*="background-image"] { filter: invert(1) hue-rotate(180deg) !important; }
                                `;
                                document.head.appendChild(style);
                            })()
                        """.trimIndent()
                        view?.loadUrl(darkModeCSS)
                    }
                }
            }
            isNestedScrollingEnabled = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }
    }

    // Back Handler Logic
    LaunchedEffect(scaffoldState.bottomSheetState) {
        var sheetWasVisible =
            scaffoldState.bottomSheetState.currentValue != androidx.compose.material3.SheetValue.Hidden
        snapshotFlow { scaffoldState.bottomSheetState.currentValue }.collect { sheetValue ->
            if (sheetValue == androidx.compose.material3.SheetValue.Hidden && sheetWasVisible) {
                isSearching = false
                isLoading = false
            }
            sheetWasVisible = sheetValue != androidx.compose.material3.SheetValue.Hidden
        }
    }

    BackHandler(enabled = true) {
        val sheetValue = scaffoldState.bottomSheetState.currentValue
        val currentWebView = webViews[selectedEngine]
        if (sheetValue != androidx.compose.material3.SheetValue.Hidden && currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else if (sheetValue == androidx.compose.material3.SheetValue.Expanded) {
             scope.launch { scaffoldState.bottomSheetState.partialExpand() }
        } else if (sheetValue == androidx.compose.material3.SheetValue.PartiallyExpanded) {
             isSearching = false
             scope.launch { scaffoldState.bottomSheetState.hide() }
        } else {
            onClose()
        }
    }

    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        androidx.compose.material3.BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.55f), // Dynamic 55% peek
            containerColor = Color.Transparent,
            sheetContainerColor = Color.Transparent,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
            sheetDragHandle = { BottomSheetDefaults.DragHandle() },
            sheetSwipeEnabled = true,
            sheetContent = {
                // Bottom Sheet Content (Results)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(800.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                ) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Tabs - Polished UI
                ScrollableTabRow(
                    selectedTabIndex = searchEngines.indexOf(selectedEngine),
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = {},
                    indicator = {}
                ) {
                    searchEngines.forEach { engine ->
                        val selected = selectedEngine == engine
                        val transition = androidx.compose.animation.core.updateTransition(targetState = selected, label = "TabSelect")
                        val scale by transition.animateFloat(label = "Scale") { if (it) 1.05f else 1f }
                        val alpha by transition.animateFloat(label = "Alpha") { if (it) 1f else 0.7f }

                        Tab(
                            selected = selected,
                            onClick = { selectedEngine = engine },
                            modifier = Modifier.graphicsLayer { 
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            },
                            text = {
                                Text(
                                    engine.name,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    modifier = Modifier
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .border(
                                             width = 1.dp,
                                             color = if(selected) MaterialTheme.colorScheme.primary.copy(alpha=0.5f) else Color.Transparent,
                                             shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                // Reset everything when bitmap changes (new area selected)
                LaunchedEffect(selectedBitmap) {
                    isSearching = false
                    hostedImageUrl = null
                    searchUrl = null
                    preloadedUrls.clear()
                    initializedEngines.clear() // Reset smart loading
                    // Do NOT destroy webviews here to keep them cached if possible? 
                    // PROBABLY safer to destroy to avoid stale state from previous searches.
                    webViews.values.forEach { 
                        it.stopLoading()
                        it.clearHistory()
                        it.loadUrl("about:blank")
                        (it.parent as? ViewGroup)?.removeView(it)
                        it.removeAllViews()
                        it.destroy() 
                    }
                    webViews.clear()
                }

                LaunchedEffect(selectedBitmap, isSearching) {
                    val searchBitmap = selectedBitmap
                    if (!isSearching || searchBitmap == null) {
                        isLoading = false
                    } else if (!retainSelectionBitmap(searchBitmap)) {
                        isLoading = false
                        isSearching = false
                    } else {
                        try {
                            isLoading = true
                        
                        val effectiveLensOnly = searchModeOverride ?: isGoogleLensOnly
                        
                        // 1. Google Lens Only Mode Check
                        // Use the reactive state (not a direct pref read) so we always
                        // get the value that was last committed, even from another activity.
                        if (effectiveLensOnly) {
                            // Save to cache and launch Lens
                            val path = try {
                                withContext(Dispatchers.IO) {
                                    ImageUtils.saveShareBitmap(context, searchBitmap, prefix = "lens")
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                android.util.Log.e("CircleToSearch", "Could not prepare Lens image", error)
                                isLoading = false
                                isSearching = false
                                android.widget.Toast.makeText(
                                    context,
                                    "Could not prepare image search",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                return@LaunchedEffect
                            }
                            val uri = android.net.Uri.fromFile(java.io.File(path))
                            
                            val result = searchWithGoogleLens(uri, context)
                            when (result) {
                                com.akslabs.circletosearch.ui.components.LensLaunchResult.LAUNCHED_DIRECTLY -> {
                                    // Keep the overlay alive behind Lens so its granted cache URI
                                    // cannot be deleted before the receiving app opens the image.
                                    isLoading = false
                                    isSearching = false
                                    return@LaunchedEffect
                                }
                                com.akslabs.circletosearch.ui.components.LensLaunchResult.FAILED -> {
                                    // Fallback to multi-search
                                    android.util.Log.e("CircleToSearch", "Google Lens launch failed, falling back to multi-search")
                                }
                            }
                        }

                        // 2. Multi-Search Mode (Expanded UI)
                        launch { scaffoldState.bottomSheetState.expand() }

                        // 3. Upload to host if needed (Multi-Search Mode)
                        val imageUrl = hostedImageUrl ?: ImageSearchUploader
                            .uploadToImageHost(searchBitmap)
                            ?.also { hostedImageUrl = it }
                        if (imageUrl == null) {
                            isLoading = false
                            isSearching = false
                            android.widget.Toast.makeText(
                                context,
                                "Could not start image search",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            return@LaunchedEffect
                        }

                        // 2. Generate URLs for ALL engines (lightweight string op)
                        searchEngines.forEach { engine ->
                            if (!preloadedUrls.containsKey(engine)) {
                                val url = when (engine) {
                                    // Use the web-friendly Google image search URL in the in-app tab.
                                    SearchEngine.Google -> ImageSearchUploader.getGoogleImagesUrl(imageUrl)
                                    SearchEngine.Bing -> ImageSearchUploader.getBingUrl(imageUrl)
                                    SearchEngine.Yandex -> ImageSearchUploader.getYandexUrl(imageUrl)
                                    SearchEngine.TinEye -> ImageSearchUploader.getTinEyeUrl(imageUrl)
                                    else -> null
                                }
                                if (url != null) preloadedUrls[engine] = url
                            }
                        }

                        // 3. Set initial URL
                        if (preloadedUrls.containsKey(selectedEngine)) {
                             searchUrl = preloadedUrls[selectedEngine]
                        }
                        
                        // Initialize only the visible engine. Previously all
                        // four WebViews were started in the background, keeping
                        // JavaScript, renderers and network requests alive even
                        // when the user never opened those tabs.
                        if (!initializedEngines.contains(selectedEngine)) {
                            initializedEngines.add(selectedEngine)
                        }
                        
                            isLoading = false
                        } finally {
                            releaseSelectionBitmap(searchBitmap)
                        }
                    }
                }

                LaunchedEffect(selectedEngine, isSearching, hostedImageUrl) {
                    if (
                        isSearching &&
                        preloadedUrls.containsKey(selectedEngine) &&
                        !initializedEngines.contains(selectedEngine)
                    ) {
                        initializedEngines.add(selectedEngine)
                    }
                }
                
                // Update searchUrl when engine changes
                LaunchedEffect(selectedEngine, preloadedUrls) {
                    if (preloadedUrls.containsKey(selectedEngine)) {
                        searchUrl = preloadedUrls[selectedEngine]
                    }
                }

                // Memory Optimization: REMOVED Aggressive Cleanup
                // User Requirement: "dont refresh tabs when user switch tabs keep them in background"
                // We keep them alive.
                /* 
                LaunchedEffect(selectedEngine) {
                     // ... (Cleanup logic removed)
                }
                */

                Box(modifier = Modifier.fillMaxSize()) {
                    // Show loading only if the SELECTED engine isn't ready or just starting
                    if (isLoading || (preloadedUrls.containsKey(selectedEngine) && !webViews.containsKey(selectedEngine))) {
                         // We delay showing the loader slightly to avoid flicker if WebView attaches instantly
                        var showLoader by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(100)
                            showLoader = true
                        }
                        
                        if (showLoader || isLoading) {
                             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ContainedLoadingIndicatorSample()
                             }
                        }
                    }

                    // Dynamic Settings Update (User Agent etc) - Now handled at top level
                    // Cleanup on Dispose
                    DisposableEffect(Unit) {
                        onDispose {
                            webViews.values.forEach { 
                                it.stopLoading()
                                it.clearHistory()
                                it.loadUrl("about:blank")
                                (it.parent as? ViewGroup)?.removeView(it)
                                it.removeAllViews()
                                it.destroy() 
                            }
                            webViews.clear()
                        }
                    }

                    // Keep each visited WebView instance (so tab state/history is
                    // preserved), but attach only the selected one. Alpha-zero
                    // AndroidViews still own full-screen Surface/layer/layout work.
                    val engine = selectedEngine
                    if (
                        initializedEngines.contains(engine) &&
                        preloadedUrls.containsKey(engine)
                    ) {
                        val url = preloadedUrls.getValue(engine)

                        DisposableEffect(engine) {
                            onDispose {
                                webViews[engine]?.let { webView ->
                                    webView.onPause()
                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                }
                            }
                        }

                        androidx.compose.runtime.key(engine) {
                            AndroidView(
                                factory = { ctx ->
                                    val swipeRefresh = SwipeRefreshLayout(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        )
                                    }
                                    val webView = webViews[engine] ?: createWebView(ctx, engine).also {
                                        if (isDesktop(engine)) {
                                            it.settings.userAgentString =
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                                "Chrome/120.0.0.0 Safari/537.36"
                                        }
                                        webViews[engine] = it
                                        it.loadUrl(url)
                                    }
                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                    swipeRefresh.addView(webView)
                                    swipeRefresh.setOnRefreshListener {
                                        webView.reload()
                                        swipeRefresh.isRefreshing = false
                                    }
                                    swipeRefresh
                                },
                                update = { swipeRefresh ->
                                    var webView: WebView? = null
                                    for (childIndex in 0 until swipeRefresh.childCount) {
                                        val child = swipeRefresh.getChildAt(childIndex)
                                        if (child is WebView) {
                                            webView = child
                                            break
                                        }
                                    }
                                    if (webView != null) {
                                        if (webView.url != url && url != webView.originalUrl) {
                                            webView.loadUrl(url)
                                        }
                                        if (scaffoldState.bottomSheetState.currentValue == SheetValue.Hidden) {
                                            webView.onPause()
                                        } else {
                                            webView.onResume()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    ) { _ ->
        // Root Box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> imageViewportSize.value = size }
                .background(Color.Transparent) // Changed from Black to Transparent
        ) {
            // Close button for Copy Mode (Top Left)



            // 1. Screenshot Layer
            if (screenshot != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Image(
                        bitmap = screenshot.asImageBitmap(),
                        contentDescription = "Screenshot",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )


                    // Tint Overlay (Punch-out style)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dimAlpha = 0.15f

                        fun drawTint() {
                            drawRect(Color.Black.copy(alpha = dimAlpha))
                            drawRect(brush = tintOverlayBrush)
                        }

                        // Clip the tint itself instead of rendering the entire
                        // screenshot into an offscreen layer and clearing pixels.
                        // This preserves the frozen screenshot in the selection
                        // and avoids a full-screen GPU buffer on every frame.
                        if (selectionRect != null && selectionAnim.value > 0f) {
                            clipPath(selectionHolePath, clipOp = ClipOp.Difference) {
                                drawTint()
                            }
                        } else {
                            drawTint()
                        }
                    }
                }
            }

            // 2. Gradient Border Layer (Overlaying screenshot, clipped to rounded corners)
            if (showGradientBorder) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isUIVisible,
                    enter = androidx.compose.animation.fadeIn(animationSpec = tween(700))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 8.dp,
                                brush = Brush.verticalGradient(
                                    colors = OverlayGradientColors.map { it.copy(alpha = 0.5f) }
                                ),
                                shape = RoundedCornerShape(24.dp) // Rounded corners for device
                            )
                            .clip(RoundedCornerShape(24.dp))
                    )
                }
            }

            // 3. Drawing Canvas (Interactive Layer)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                ) {
                    // Draw current path (Real-time)
                    if (currentPathPoints.size > 1) {
                        // BlurMaskFilter lives on a retained Paint, producing a soft
                        // neon halo without allocating render objects per frame.
                        drawContext.canvas.drawPath(selectionTrailPath, selectionTrailGlowPaint)
                        drawPath(
                            path = selectionTrailPath,
                            brush = selectionTrailBrush,
                            style = selectionTrailCoreStroke,
                            alpha = 0.96f,
                        )
                        drawPath(
                            path = selectionTrailPath,
                            color = Color.White,
                            style = selectionTrailHighlightStroke,
                            alpha = 0.88f,
                        )
                    }

                    // Draw Lens Animation (Rounded Corner Brackets)
                    if (selectionRect != null && selectionAnim.value > 0f) {
                        val rect = selectionRect!!
                        val progress = selectionAnim.value
                        val left = rect.left.toFloat()
                        val top = rect.top.toFloat()
                        val right = rect.right.toFloat()
                        val bottom = rect.bottom.toFloat()
                        
                        val width = right - left
                        val height = bottom - top
                        selectionBracketPaths.forEach { bracketPath ->
                            drawPath(
                                bracketPath,
                                Color.White,
                                style = selectionBracketStroke,
                                alpha = progress,
                            )
                        }

                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(48f),
                            style = selectionFlashStroke,
                            alpha = (1f - progress) * 0.5f,
                        )
                    }
                }

            // 4. Header (Top)
            androidx.compose.animation.AnimatedVisibility(
                visible = isUIVisible,
                enter = androidx.compose.animation.slideInVertically(
                    initialOffsetY = { -it }, // Commence au-dessus de l'écran (-100%)
                    animationSpec = tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ),
                exit = androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(300)
                ),
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2000f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick =
                            {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                onClose()
                            },
                        modifier = Modifier
                            .background(Color.Gray.copy(alpha = 0.5f), CircleShape)
                            .size(40.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (selectedEngine.name == "Google") {
                        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFE2E2E2), // Light gray
                                Color(0xFFFFFFFF), // Pure white
                                Color(0xFFA5C8FF)  // Soft subtle blue accent
                            )
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = com.akslabs.circletosearch.R.string.translate_by_google),
                            modifier = Modifier.graphicsLayer(alpha = 0.99f),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                brush = brush,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        )
                    } else {
                        // Si c'est Bing, Yandex, etc., on garde le texte stylé d'origine
                        Text(
                            text = selectedEngine.name,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    // Action Button (Menu)
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                            .size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            showMenu = true
                        }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = Color.White
                            )
                        }

                        androidx.compose.material3.DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                            tonalElevation = 6.dp
                        ) {
                            val isDesktop = isDesktop(selectedEngine)
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (isDesktop) "Mobile Mode" else "Desktop Mode") },
                                leadingIcon = {
                                    Icon(
                                        if (isDesktop) Icons.Default.Smartphone else Icons.Default.DesktopWindows,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    val newSet = desktopModeEngines.toMutableSet()
                                    if (newSet.contains(selectedEngine)) {
                                        newSet.remove(selectedEngine)
                                    } else {
                                        newSet.add(selectedEngine)
                                    }
                                    desktopModeEngines = newSet
                                    showMenu = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (isDarkMode) "Light Mode" else "Dark Mode") },
                                leadingIcon = {
                                    Icon(
                                        if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    isDarkMode = !isDarkMode
                                    showMenu = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (showGradientBorder) "Hide Border" else "Show Border") },
                                leadingIcon = {
                                    Icon(Icons.Default.BorderOuter, contentDescription = null)
                                },
                                onClick = {
                                    showGradientBorder = !showGradientBorder
                                    showMenu = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Refresh") },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    webViews[selectedEngine]?.reload()
                                    showMenu = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Copy URL") },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                },
                                onClick = {
                                    if (searchUrl != null) {
                                        val clipboard =
                                            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText(
                                            "Search URL",
                                            searchUrl
                                        )
                                        clipboard.setPrimaryClip(clip)
                                    }
                                    showMenu = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Open in Browser") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                },
                                onClick = {
                                    val currentUrl = webViews[selectedEngine]?.url ?: searchUrl
                                    if (currentUrl != null) {
                                        try {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(currentUrl)
                                            )
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            android.util.Log.e(
                                                "CircleToSearch",
                                                "Failed to open browser",
                                                e
                                            )
                                        }
                                    }
                                    showMenu = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Translation Target") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Translate, contentDescription = null)
                                    },
                                    onClick = {
                                        showTranslationLangDialog = true
                                        showMenu = false
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    showSettingsScreen = true
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
            // 5. Bottom Bar — Material 3 Expressive two-row card

            androidx.compose.animation.AnimatedVisibility(
                visible = isUIVisible,
                enter = slideInVertically(
                    initialOffsetY = { it }, // slides up from below
                    animationSpec = tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f))
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutVertically(
                    targetOffsetY = { it }, // slides back down
                    animationSpec = tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 1f, 1f))
                ) + fadeOut(animationSpec = tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(2000f)
            ) {
                // ── Outer card container ──────────────────────────────────────────
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // ── ROW 1: Search Pill + Instant Actions (Song, Translate) ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Main Search Pill
                            androidx.compose.material3.Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(60.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 2.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // App logo
                                    Image(
                                        painter = painterResource(id = com.akslabs.circletosearch.R.drawable.circletosearch),
                                        contentDescription = "Logo",
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clickable {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                scope.launch { scaffoldState.bottomSheetState.expand() }
                                            }
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Mic Button
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            try {
                                                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {}
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = "Voice Search")
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    // Lens Button
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            val bitmap = screenshot ?: return@IconButton
                                            scope.launch {
                                                try {
                                                    val path = withContext(Dispatchers.IO) {
                                                        ImageUtils.saveShareBitmap(context, bitmap, prefix = "lens")
                                                    }
                                                    val uri = android.net.Uri.fromFile(java.io.File(path))
                                                    val lensResult = searchWithGoogleLens(uri, context)
                                                    if (lensResult == com.akslabs.circletosearch.ui.components.LensLaunchResult.FAILED) {
                                                        isSearching = true
                                                    }
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (error: Exception) {
                                                    android.util.Log.e("CircleToSearch", "Lens launch failed", error)
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Could not open image search",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(painterResource(id = com.akslabs.circletosearch.R.drawable.circletosearch), contentDescription = "Lens", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                                    }
                                }
                            }

                            // Circular Button: Translate (Unified)
                            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = {
                                            try { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) } catch (e: Exception) {}
                                            onTranslate()
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            showTranslationLangDialog = true
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = "Translate", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        // ── ROW 2: Action buttons ───────────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 0.dp), // Maximize space for labels
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            @Composable
                            fun BottomBarButton(
                                label: String, 
                                icon: @Composable () -> Unit, 
                                enabled: Boolean = true,
                                onClick: () -> Unit
                            ) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        FilledTonalIconButton(
                                            onClick = { 
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                onClick() 
                                            },
                                            enabled = enabled,
                                            modifier = Modifier.size(48.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            )
                                        ) { icon() }
                                        
                                        Spacer(modifier = Modifier.height(4.dp))
                                        
                                        Text(
                                            text = label, 
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.5.sp, 
                                                lineHeight = 12.sp,
                                                letterSpacing = (-0.4).sp,
                                                fontWeight = FontWeight.Medium
                                            ), 
                                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f), 
                                            textAlign = TextAlign.Center, 
                                            maxLines = 1,
                                            overflow = TextOverflow.Visible,
                                            modifier = Modifier.padding(horizontal = 0.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Seamless Text Selection Overlay integration (Active when in copy mode)
            if (copyTextManager != null) {
                key(copyTextManager) {
                    AndroidView(
                        factory = {
                            copyTextManager.getOverlayView(
                                onDismiss = {
                                    onClose()
                                },
                                onBackgroundTouch = { action, x, y ->
                                    handleBackgroundTouch(action, x, y)
                                },
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(150f),
                    )
                }
            }

            // 4. Selection actions — positioned last so they render above the crop overlay.
            if (selectionRect != null && isSelectionReady && !isSearching) {
                val rect = selectionRect!!
                val density = androidx.compose.ui.platform.LocalDensity.current
                val leftPx = rect.left.toFloat()
                val topPx = rect.top.toFloat()
                val rightPx = rect.right.toFloat()
                val bottomPx = rect.bottom.toFloat()
                
                val leftDp = with(density) { leftPx.toDp() }
                val topDp = with(density) { topPx.toDp() }
                val rightDp = with(density) { rightPx.toDp() }
                val bottomDp = with(density) { bottomPx.toDp() }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2000f)
                ) {
                    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                    val centerX = (leftDp + rightDp) / 2
                    val showTextAction =
                        selectedRegionText.isNotBlank() ||
                            isAnalyzingText ||
                            isRefiningSelectionText
                    val actionBarWidth = minOf(400.dp, (screenWidth - 16.dp).coerceAtLeast(280.dp))
                    val maximumX = (screenWidth - actionBarWidth - 8.dp).coerceAtLeast(8.dp)
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (centerX - actionBarWidth / 2).coerceIn(8.dp, maximumX),
                                y = if (topPx > 200f) (topDp - 72.dp).coerceAtLeast(16.dp) else (bottomDp + 16.dp).coerceAtMost(screenHeight - 80.dp)
                            )
                            .width(actionBarWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape,
                            tonalElevation = 4.dp,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (showTextAction) {
                                    androidx.compose.material3.FilledTonalButton(
                                        onClick = {
                                            val text = selectedRegionText
                                            if (text.isNotBlank()) {
                                                val clipboard = context.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE,
                                                ) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(
                                                    android.content.ClipData.newPlainText(
                                                        "Selected text",
                                                        text,
                                                    ),
                                                )
                                                haptic.performHapticFeedback(
                                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                                )
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Text copied",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                        enabled = selectedRegionText.isNotBlank(),
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = CircleShape,
                                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            disabledContainerColor = Color.Transparent,
                                        ),
                                        elevation = null,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            if (selectedRegionText.isNotBlank()) "Copy" else "Text…",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    }

                                    androidx.compose.material3.VerticalDivider(
                                        modifier = Modifier.height(24.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }

                                androidx.compose.material3.FilledTonalButton(
                                    onClick = {
                                        val bitmap = selectedBitmap
                                        if (bitmap != null && retainSelectionBitmap(bitmap)) scope.launch {
                                            try {
                                                val path = withContext(Dispatchers.IO) {
                                                    ImageUtils.saveShareBitmap(
                                                        context = context,
                                                        bitmap = bitmap,
                                                        prefix = "selection",
                                                    )
                                                }
                                                val file = java.io.File(path)
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file,
                                                )
                                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "image/png"
                                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                    clipData = android.content.ClipData.newRawUri("Selection", uri)
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(
                                                    android.content.Intent.createChooser(shareIntent, "Share selection")
                                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (e: Exception) {
                                                android.util.Log.e("CircleToSearch", "Failed to share selection", e)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Could not share selection",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            } finally {
                                                releaseSelectionBitmap(bitmap)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = CircleShape,
                                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.Transparent,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    elevation = null,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }

                                androidx.compose.material3.VerticalDivider(
                                    modifier = Modifier.height(24.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )

                                androidx.compose.material3.FilledTonalButton(
                                    onClick = {
                                        val bitmap = selectedBitmap
                                        if (bitmap != null && retainSelectionBitmap(bitmap)) scope.launch {
                                            try {
                                                val success = withContext(Dispatchers.IO) {
                                                    ImageUtils.saveToGallery(context.applicationContext, bitmap)
                                                }
                                                if (success) {
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (success) "Saved to Gallery" else "Could not save selection",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            } finally {
                                                releaseSelectionBitmap(bitmap)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = CircleShape,
                                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.Transparent,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    elevation = null,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }

                                androidx.compose.material3.VerticalDivider(
                                    modifier = Modifier.height(24.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )

                                androidx.compose.material3.FilledTonalButton(
                                    onClick = {
                                        if (selectedBitmap != null) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            isSearching = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = CircleShape,
                                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.Transparent,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    elevation = null,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Search",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        // Intersecting corner-to-corner aurora waves communicate background analysis.
        // The delay prevents flicker on warm scans.
        val isAnalysisFeedbackActive = isAnalyzingText
        var analysisDelayElapsed by remember { mutableStateOf(false) }
        LaunchedEffect(isAnalysisFeedbackActive) {
            analysisDelayElapsed = false
            if (isAnalysisFeedbackActive) {
                delay(140)
                analysisDelayElapsed = true
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = isAnalysisFeedbackActive && analysisDelayElapsed,
            enter = androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.fadeOut(tween(280)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(3500f),
        ) {
            val analysisTransition = rememberInfiniteTransition(label = "screen analysis")
            val primaryFlow = analysisTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 4_400,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "primary light field",
            )
            val secondaryFlow = analysisTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 3_250,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "secondary light field",
            )
            val tertiaryFlow = analysisTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 5_100,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "tertiary light field",
            )
            val sheenFlow = analysisTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 2_750,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "white sheen",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scanningEdgeGlow(
                        primaryFlow = primaryFlow,
                        secondaryFlow = secondaryFlow,
                        tertiaryFlow = tertiaryFlow,
                        sheenFlow = sheenFlow,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(1.dp)
                        .semantics {
                            contentDescription = "Screen analysis in progress"
                            liveRegion = LiveRegionMode.Polite
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                )
            }
        }

        // --- NEW: Smart Entities (QR, Links, etc.) Overlay Chips ---
        if (screenshot != null && detectedEntities.isNotEmpty() && !showQrSheet) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(2600f)) {
                val screenWidth = maxWidth
                val screenHeight = maxHeight
                val bitmapWidth = screenshot.width.toFloat()
                val bitmapHeight = screenshot.height.toFloat()

                detectedEntities.forEach { entity ->
                    val chipX = (entity.bounds.centerX() / bitmapWidth) * screenWidth.value
                    val chipY = (entity.bounds.centerY() / bitmapHeight) * screenHeight.value
                    val qrEntity = entity as? SmartEntity.QrCode
                    val entityLabel = if (qrEntity != null) {
                        qrResultShortLabel(qrEntity.qrResult, qrEntity.format)
                    } else {
                        entity.text
                    }
                    val copiesCodeOnTap = qrEntity?.let { code ->
                        if (code.format != null) {
                            !QrScanner.isQrCode(code.format)
                        } else {
                            code.qrResult is QrResult.Product
                        }
                    } == true
                    val primaryActionLabel = when {
                        copiesCodeOnTap -> "Copy ${qrEntity?.typeName ?: "code"} value"
                        qrEntity?.qrResult is QrResult.Url -> "Open link"
                        qrEntity != null -> "Show code details"
                        entity is SmartEntity.Url -> "Open link"
                        entity is SmartEntity.Email -> "Compose email"
                        entity is SmartEntity.Phone -> "Dial phone number"
                        entity is SmartEntity.Upi -> "Open UPI link"
                        else -> "Open"
                    }

                    // The chip owns its gestures so drawing cannot start under it.
                    Box(
                        modifier = Modifier
                            .offset(x = chipX.dp - 24.dp, y = chipY.dp - 24.dp)
                            .size(48.dp)
                            .shadow(6.dp, CircleShape)
                            .background(Color.White, CircleShape)
                            .border(1.5.dp, entity.sourceColor.copy(alpha = 0.5f), CircleShape)
                            .semantics {
                                contentDescription = "${entity.typeName}: $entityLabel"
                            }
                            .combinedClickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = primaryActionLabel,
                                onLongClickLabel = if (qrEntity != null) {
                                    "Show ${qrEntity.typeName} details"
                                } else {
                                    null
                                },
                                onLongClick = if (qrEntity != null) {
                                    {
                                        selectedQrResult = QrResultWithBounds(
                                            result = qrEntity.qrResult,
                                            rawText = qrEntity.rawText,
                                            bounds = qrEntity.bounds,
                                            format = qrEntity.format,
                                        )
                                        showQrSheet = true
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    if (qrEntity != null) {
                                        when {
                                            copiesCodeOnTap -> {
                                                copyDetectedCode(
                                                    context = context,
                                                    rawText = qrEntity.rawText,
                                                    format = qrEntity.format,
                                                )
                                            }
                                            qrEntity.qrResult is QrResult.Url -> {
                                                val url = qrEntity.qrResult.url
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(
                                                        if (
                                                            url.startsWith("http://", ignoreCase = true) ||
                                                            url.startsWith("https://", ignoreCase = true)
                                                        ) {
                                                            url
                                                        } else {
                                                            "https://$url"
                                                        }
                                                    ),
                                                )
                                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                var opened = false
                                                try {
                                                    context.startActivity(intent)
                                                    opened = true
                                                } catch (e: Exception) {
                                                    android.util.Log.e(
                                                        "CircleToSearch",
                                                        "Unable to open decoded URL",
                                                        e,
                                                    )
                                                }
                                                if (opened) {
                                                    (context as? android.app.Activity)?.finish()
                                                }
                                            }
                                            else -> {
                                                selectedQrResult = QrResultWithBounds(
                                                    result = qrEntity.qrResult,
                                                    rawText = qrEntity.rawText,
                                                    bounds = qrEntity.bounds,
                                                    format = qrEntity.format,
                                                )
                                                showQrSheet = true
                                            }
                                        }
                                    } else {
                                        val intent = when (entity) {
                                            is SmartEntity.Url -> android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(
                                                    if (
                                                        entity.text.startsWith("http://", ignoreCase = true) ||
                                                        entity.text.startsWith("https://", ignoreCase = true)
                                                    ) {
                                                        entity.text
                                                    } else {
                                                        "https://${entity.text}"
                                                    }
                                                ),
                                            )
                                            is SmartEntity.Email -> android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${entity.text}"))
                                            is SmartEntity.Phone -> android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${entity.text}"))
                                            is SmartEntity.Upi -> android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(entity.text))
                                            else -> null
                                        }
                                        intent?.let {
                                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            try {
                                                context.startActivity(it)
                                                (context as? android.app.Activity)?.finish()
                                            } catch (error: Exception) {
                                                android.util.Log.e(
                                                    "CircleToSearch",
                                                    "Unable to open detected entity",
                                                    error,
                                                )
                                            }
                                        }
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            entity.icon, 
                            null, 
                            tint = entity.sourceColor, 
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .offset(x = chipX.dp - 100.dp, y = chipY.dp + 32.dp)
                            .width(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.95f),
                            shadowElevation = 4.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, entity.sourceColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = entityLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    color = Color.DarkGray
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Some ZXing formats can decode without exposing result points. Keep
        // those results discoverable without pretending they came from a
        // specific location in the screenshot.
        if (screenshot != null && unpositionedQrCodes.isNotEmpty() && !showQrSheet) {
            val soleResult = unpositionedQrCodes.singleOrNull()
            val quickCopyResult = soleResult?.takeIf { code ->
                if (code.format != null) {
                    !QrScanner.isQrCode(code.format)
                } else {
                    code.result is QrResult.Product
                }
            }
            val quickCopy = quickCopyResult != null
            val showUnpositionedResults = {
                selectedQrResult = soleResult
                showQrSheet = true
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .zIndex(2601f)
                    .combinedClickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = if (quickCopy) {
                            "Copy ${QrScanner.formatDisplayName(soleResult?.format)} value"
                        } else {
                            "Show detected code results"
                        },
                        onLongClickLabel = "Show detected code results",
                        onLongClick = showUnpositionedResults,
                        onClick = {
                            if (quickCopyResult != null) {
                                copyDetectedCode(
                                    context = context,
                                    rawText = quickCopyResult.rawText,
                                    format = quickCopyResult.format,
                                )
                            } else {
                                showUnpositionedResults()
                            }
                        },
                    ),
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color(0xFF1A73E8).copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = null,
                        tint = Color(0xFF1A73E8),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (soleResult != null) {
                            QrScanner.formatDisplayName(soleResult.format)
                        } else {
                            "${unpositionedQrCodes.size} codes detected"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.DarkGray,
                    )
                }
            }
        }

        // QR Code Result Sheet
        if (showQrSheet) {
            // Full-screen invisible overlay to catch "click outside"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2500f)
                    .background(Color.Black.copy(alpha = 0.01f)) // Almost invisible but catches taps
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { 
                        showQrSheet = false
                        selectedQrResult = null
                    }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .zIndex(3000f),
                contentAlignment = Alignment.BottomCenter
            ) {
                // Ensure BackHandler is active when sheet is shown
                androidx.activity.compose.BackHandler {
                    showQrSheet = false
                    selectedQrResult = null
                }

                QrCodeResultSheet(
                    context = context,
                    bitmap = qrScanBitmap,
                    onDismiss = { 
                        showQrSheet = false
                        selectedQrResult = null
                    },
                    initialResults = if (selectedQrResult != null) listOf(selectedQrResult!!) else detectedQrCodes,
                    initialPage = 0
                )
            }
        }

        if (showSettingsScreen) {
            SettingsScreen(
                uiPreferences = uiPreferences,
                onDismissRequest = { showSettingsScreen = false }
            )
        }

        if (showTranslationLangDialog) {
            val languages = listOf(
                null to "Auto (System Default)",
                "ar" to "Arabic",
                "zh" to "Chinese",
                "nl" to "Dutch",
                "en" to "English",
                "fr" to "French",
                "de" to "German",
                "hi" to "Hindi",
                "it" to "Italian",
                "ja" to "Japanese",
                "ko" to "Korean",
                "pl" to "Polish",
                "pt" to "Portuguese",
                "ru" to "Russian",
                "es" to "Spanish",
                "tr" to "Turkish",
                "uk" to "Ukrainian"
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTranslationLangDialog = false },
                title = { Text("Translate to...") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val currentSelection = uiPreferences.getTargetTranslateLang()
                        languages.forEach { (code, name) ->
                            val isSelected = currentSelection == code
                            Text(
                                text = name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        uiPreferences.setTargetTranslateLang(code)
                                        showTranslationLangDialog = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showTranslationLangDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
}
}

private fun DrawScope.drawSoftLightField(
    center: Offset,
    brush: Brush,
    radius: Float,
    scaleX: Float,
    scaleY: Float,
    alpha: Float,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    translate(left = center.x, top = center.y) {
        scale(scaleX = scaleX, scaleY = scaleY, pivot = Offset.Zero) {
            drawCircle(
                brush = brush,
                radius = radius,
                center = Offset.Zero,
                alpha = alpha,
                blendMode = blendMode,
            )
        }
    }
}

private fun Modifier.scanningEdgeGlow(
    primaryFlow: State<Float>,
    secondaryFlow: State<Float>,
    tertiaryFlow: State<Float>,
    sheenFlow: State<Float>,
): Modifier = drawWithCache {
    val fieldRadius = max(size.width, size.height) * 0.72f
    val blue = Color(0xFF4285F4)
    val red = Color(0xFFEA4335)
    val yellow = Color(0xFFFBBC05)
    val green = Color(0xFF34A853)
    val blueField = Brush.radialGradient(
        0f to blue.copy(alpha = 0.52f),
        0.42f to blue.copy(alpha = 0.25f),
        0.78f to blue.copy(alpha = 0.07f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = fieldRadius,
    )
    val redField = Brush.radialGradient(
        0f to red.copy(alpha = 0.58f),
        0.40f to red.copy(alpha = 0.30f),
        0.78f to red.copy(alpha = 0.08f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = fieldRadius,
    )
    val yellowField = Brush.radialGradient(
        0f to yellow.copy(alpha = 0.46f),
        0.43f to yellow.copy(alpha = 0.22f),
        0.80f to yellow.copy(alpha = 0.06f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = fieldRadius,
    )
    val greenField = Brush.radialGradient(
        0f to green.copy(alpha = 0.48f),
        0.42f to green.copy(alpha = 0.23f),
        0.80f to green.copy(alpha = 0.06f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = fieldRadius,
    )
    val whiteSheen = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0.62f),
        0.38f to Color.White.copy(alpha = 0.25f),
        0.76f to Color.White.copy(alpha = 0.06f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = fieldRadius,
    )

    onDrawBehind {
        val primary = primaryFlow.value
        val secondary = secondaryFlow.value
        val tertiary = tertiaryFlow.value
        val sheen = sheenFlow.value
        val width = size.width
        val height = size.height

        // The reference uses a translucent frosted veil beneath broad moving
        // color fields, not discrete particles or a narrow scan band.
        drawRect(Color.White, alpha = 0.055f + sheen * 0.035f)

        drawSoftLightField(
            center = Offset(
                x = width * (-0.12f + secondary * 0.62f),
                y = height * (-0.04f + primary * 0.38f),
            ),
            brush = blueField,
            radius = fieldRadius,
            scaleX = 0.78f + tertiary * 0.16f,
            scaleY = 0.62f + secondary * 0.12f,
            alpha = 0.34f,
        )
        drawSoftLightField(
            center = Offset(
                x = width * (0.02f + tertiary * 0.62f),
                y = height * (0.16f + secondary * 0.52f),
            ),
            brush = greenField,
            radius = fieldRadius,
            scaleX = 0.80f + primary * 0.14f,
            scaleY = 0.68f + tertiary * 0.13f,
            alpha = 0.36f,
        )
        drawSoftLightField(
            center = Offset(
                x = width * (0.28f + primary * 0.62f),
                y = height * (0.78f - tertiary * 0.36f),
            ),
            brush = yellowField,
            radius = fieldRadius,
            scaleX = 0.82f + secondary * 0.17f,
            scaleY = 0.63f + primary * 0.11f,
            alpha = 0.30f,
        )
        drawSoftLightField(
            center = Offset(
                x = width * (0.06f + primary * 1.02f),
                y = height * (0.82f - secondary * 0.56f),
            ),
            brush = redField,
            radius = fieldRadius,
            scaleX = 0.98f + tertiary * 0.20f,
            scaleY = 0.72f + primary * 0.15f,
            alpha = 0.43f,
        )

        // A second, offset red layer creates the soft rolling pink wave visible
        // across the middle and lower half of the reference animation.
        drawSoftLightField(
            center = Offset(
                x = width * (0.86f - tertiary * 0.54f),
                y = height * (0.42f + primary * 0.34f),
            ),
            brush = redField,
            radius = fieldRadius,
            scaleX = 0.72f + secondary * 0.15f,
            scaleY = 0.58f + tertiary * 0.10f,
            alpha = 0.21f,
        )
        drawSoftLightField(
            center = Offset(
                x = width * (0.18f + sheen * 0.74f),
                y = height * (0.30f + (1f - sheen) * 0.45f),
            ),
            brush = whiteSheen,
            radius = fieldRadius,
            scaleX = 0.78f + tertiary * 0.22f,
            scaleY = 0.64f + secondary * 0.14f,
            alpha = 0.31f,
            blendMode = BlendMode.Screen,
        )
    }
}

private fun updateSelectionBracketPaths(paths: List<Path>, rect: Rect?) {
    paths.forEach(Path::reset)
    if (paths.size < 4 || rect == null || rect.width() <= 0 || rect.height() <= 0) return
    val left = rect.left.toFloat()
    val top = rect.top.toFloat()
    val right = rect.right.toFloat()
    val bottom = rect.bottom.toFloat()
    val minimumDimension = min(right - left, bottom - top)
    val cornerRadius = min(48f, minimumDimension * 0.25f)
    val armLength = (minimumDimension * 0.18f)
        .coerceAtLeast(cornerRadius)
        .coerceAtMost(minimumDimension * 0.45f)

    paths[0].apply {
        moveTo(left, top + armLength)
        lineTo(left, top + cornerRadius)
        arcTo(
            rect = ComposeRect(left, top, left + 2 * cornerRadius, top + 2 * cornerRadius),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        lineTo(left + armLength, top)
    }
    paths[1].apply {
        moveTo(right - armLength, top)
        lineTo(right - cornerRadius, top)
        arcTo(
            rect = ComposeRect(right - 2 * cornerRadius, top, right, top + 2 * cornerRadius),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        lineTo(right, top + armLength)
    }
    paths[2].apply {
        moveTo(right, bottom - armLength)
        lineTo(right, bottom - cornerRadius)
        arcTo(
            rect = ComposeRect(
                right - 2 * cornerRadius,
                bottom - 2 * cornerRadius,
                right,
                bottom,
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        lineTo(right - armLength, bottom)
    }
    paths[3].apply {
        moveTo(left + armLength, bottom)
        lineTo(left + cornerRadius, bottom)
        arcTo(
            rect = ComposeRect(left, bottom - 2 * cornerRadius, left + 2 * cornerRadius, bottom),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        lineTo(left, bottom - armLength)
    }
}

private fun updateSelectionHolePath(path: Path, rect: Rect?) {
    path.reset()
    if (rect == null || rect.width() <= 0 || rect.height() <= 0) return
    path.addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            rect = ComposeRect(
                left = rect.left.toFloat(),
                top = rect.top.toFloat(),
                right = rect.right.toFloat(),
                bottom = rect.bottom.toFloat(),
            ),
            cornerRadius = CornerRadius(48f),
        ),
    )
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@androidx.compose.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
fun ContainedLoadingIndicatorSample() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.ContainedLoadingIndicator()
    }
}
