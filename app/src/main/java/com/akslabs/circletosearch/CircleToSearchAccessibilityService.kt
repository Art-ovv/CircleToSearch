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

package com.akslabs.circletosearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.akslabs.circletosearch.data.ActionType
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.data.GestureType
import com.akslabs.circletosearch.data.OverlayConfigurationManager
import com.akslabs.circletosearch.data.OverlaySegment
import com.akslabs.circletosearch.ui.components.CopyTextOverlayManager
import com.akslabs.circletosearch.utils.ImageUtils
import com.akslabs.circletosearch.utils.StorageUtils
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class CaptureStartResult {
    STARTED,
    BUSY,
    UNAVAILABLE,
    UNSUPPORTED,
}

internal sealed interface AssistantCaptureResult {
    data class Success(val bitmap: Bitmap) : AssistantCaptureResult
    data class Failure(val errorCode: Int?) : AssistantCaptureResult
    data object Cancelled : AssistantCaptureResult
}

private const val ACCESSIBILITY_CAPTURE_TIMEOUT_MS = 3_000L

private class ActiveCaptureRequest(
    val id: Long,
    val assistantOwnerId: Long?,
    val assistantCallback: ((AssistantCaptureResult) -> Unit)?,
) {
    val timeoutGeneration = AtomicLong(0L)
    var warmUpJob: kotlinx.coroutines.Job? = null
}

class CircleToSearchAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private val overlayViews = mutableListOf<View>() // Track all added segment views
    private val pinnedOverlayViews = mutableSetOf<View>()
    private val pinnedActionMenus = mutableSetOf<View>()
    private val pinnedImageBudget = PinnedImageBudget()
    private val pinnedImageLeases = mutableMapOf<View, PinnedImageBudget.Lease>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var configManager: OverlayConfigurationManager
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // The owner token prevents a late callback from an expired request from
    // unlocking or publishing into a newer capture. The timeout also recovers
    // if Android never calls either takeScreenshot callback.
    private val captureRequestSequence = AtomicLong(0L)
    private val activeCaptureRequest = AtomicReference<ActiveCaptureRequest?>(null)
    private var accessibilityButtonRegistered = false
    private val accessibilityButtonCallback =
        object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                android.util.Log.d("CircleToSearch", "Accessibility shortcut clicked")
                performCapture()
            }
        }
    
    /** Kept by companion so scroll events can re-scan copy-text nodes. */
    internal var copyTextManager: CopyTextOverlayManager? = null
    
    // Bubble related - Keeping existing logic but refactoring slightly if needed
    // For now, keeping bubble separate as requested in prompt "statusbar overlay customization... but it should work normally like now"
    // The prompt asks to disable statusbar overlay in landscape but keep it working normally.
    
    private var bubbleView: View? = null
    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private val overlayPrefs by lazy { getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE) } // Watch overlay prefs too
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bubble_enabled") {
            updateBubbleState()
        }
    }
    
    private val overlayPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // On any overlay config change, rebuild the overlay
        updateOverlay()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configManager = OverlayConfigurationManager(this)
        
        // node info from other windows without touching AndroidManifest.xml.
        // Also enable enhanced web accessibility for better WebView coverage.
        val info = serviceInfo
        info.flags = info.flags or 
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        serviceInfo = info
        instanceReference = WeakReference(this)

        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                accessibilityButtonCallback,
                mainHandler,
            )
            accessibilityButtonRegistered = true
        } catch (error: RuntimeException) {
            android.util.Log.w(
                "CircleToSearch",
                "Unable to register the accessibility shortcut",
                error,
            )
        }
        
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        overlayPrefs.registerOnSharedPreferenceChangeListener(overlayPrefsListener)
        
        updateBubbleState()
        updateOverlay()
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlay()
        bubbleView?.let { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@let
            val safeLayout = safeOverlayLayout(
                width = params.width,
                height = params.height,
                x = params.x,
                y = params.y,
            )
            params.width = safeLayout.width
            params.height = safeLayout.height
            params.x = safeLayout.x
            params.y = safeLayout.y
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (_: Exception) {
                // The bubble may have been removed while configuration changed.
            }
        }
        pinnedOverlayViews.toList().forEach { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            val safeLayout = safeOverlayLayout(
                width = params.width,
                height = params.height,
                x = params.x,
                y = params.y,
            )
            params.width = safeLayout.width
            params.height = safeLayout.height
            params.x = safeLayout.x
            params.y = safeLayout.y
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Keep ownership until the pin is explicitly removed.
            }
        }
        pinnedActionMenus.toList().forEach { menu ->
            val params = menu.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            val safeLayout = safeOverlayLayout(
                width = params.width,
                height = params.height,
                x = params.x,
                y = params.y,
            )
            params.width = safeLayout.width
            params.height = safeLayout.height
            params.x = safeLayout.x
            params.y = safeLayout.y
            try {
                windowManager?.updateViewLayout(menu, params)
            } catch (_: Exception) {
                // Keep ownership until the menu is explicitly removed.
            }
        }
    }

    private fun updateBubbleState() {
        if (prefs.getBoolean("bubble_enabled", false)) {
            showBubble()
        } else {
            hideBubble()
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return // Already shown

        val initialLayout = safeOverlayLayout(
            width = 100,
            height = 100,
            x = 0,
            y = 200,
        )
        val params = WindowManager.LayoutParams(
            initialLayout.width, initialLayout.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = initialLayout.x
        params.y = initialLayout.y

        bubbleView = View(this).apply {
            setBackgroundResource(R.mipmap.ic_launcher)
            elevation = 10f
            systemGestureExclusionRects = emptyList()
            
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val safeLayout = safeOverlayLayout(
                            width = params.width,
                            height = params.height,
                            x = initialX + (event.rawX - initialTouchX).toInt(),
                            y = initialY + (event.rawY - initialTouchY).toInt(),
                        )
                        params.x = safeLayout.x
                        params.y = safeLayout.y
                        windowManager?.updateViewLayout(this, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(event.rawX - initialTouchX) < 10 && Math.abs(event.rawY - initialTouchY) < 10) {
                            performCapture()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager?.addView(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideBubble() {
        if (bubbleView != null) {
            try {
                windowManager?.removeView(bubbleView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bubbleView = null
        }
    }

    private data class SafeOverlayLayout(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
    )

    /** Keeps touchable accessibility windows away from Pixel's corner gesture strip. */
    private fun safeOverlayLayout(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): SafeOverlayLayout {
        val displayWidth: Int
        val displayHeight: Int
        val mandatoryBottomInset: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            val bounds = metrics?.bounds
            displayWidth = bounds?.width() ?: resources.displayMetrics.widthPixels
            displayHeight = bounds?.height() ?: resources.displayMetrics.heightPixels
            mandatoryBottomInset = metrics?.windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures())
                ?.bottom
                ?: 0
        } else {
            displayWidth = resources.displayMetrics.widthPixels
            displayHeight = resources.displayMetrics.heightPixels
            mandatoryBottomInset = 0
        }

        // Some vendor builds report a very small mandatory inset even though
        // the assistant recognizer starts slightly above the physical corner.
        val minimumCornerClearance = (48f * resources.displayMetrics.density).toInt()
        val safeBottom = (
            displayHeight - maxOf(mandatoryBottomInset, minimumCornerClearance)
            ).coerceAtLeast(1)
        val safeWidth = width.coerceIn(1, displayWidth.coerceAtLeast(1))
        val safeHeight = height.coerceIn(1, safeBottom)

        return SafeOverlayLayout(
            width = safeWidth,
            height = safeHeight,
            x = x.coerceIn(0, (displayWidth - safeWidth).coerceAtLeast(0)),
            y = y.coerceIn(0, (safeBottom - safeHeight).coerceAtLeast(0)),
        )
    }

    private fun updateOverlay() {
        val config = configManager.getConfig()
        
        if (!config.isEnabled) {
            overlayViews.forEach { 
                try { windowManager?.removeView(it) } catch(e: Exception) {} 
            }
            overlayViews.clear()
            return
        }

        // --- Resolution of Colors ---
        val primaryColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(android.R.color.system_accent1_500)
        } else {
            Color.parseColor("#FF6200EE") // Default Material Primary
        }

        val themePalette = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.R.color.system_accent1_200,
                android.R.color.system_accent2_200,
                android.R.color.system_accent3_200,
                android.R.color.system_neutral1_200,
                android.R.color.system_neutral2_200
            ).map { 
                val color = getColor(it)
                // Set alpha to 40% (approx 102/255) for subtle blending
                Color.argb(102, Color.red(color), Color.green(color), Color.blue(color))
            }
        } else {
            // Legacy fallbacks with 40% opacity
            listOf(
                Color.parseColor("#66FF0000"), // Red
                Color.parseColor("#6600FF00"), // Green
                Color.parseColor("#660000FF"), // Blue
                Color.parseColor("#66FFFF00"), // Yellow
                Color.parseColor("#66FF00FF")  // Magenta
            )
        }
        
        // Landscape check
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE && !config.isEnabledInLandscape) {
             overlayViews.forEach { 
                try { windowManager?.removeView(it) } catch(e: Exception) {} 
            }
            overlayViews.clear()
            return
        }

        // --- OPTIMIZATION: Diff Update to prevent flashing ---
        // If the number of segments matches, we try to update existing views' LayoutParams
        // If not, we rebuild.
        
        if (overlayViews.size == config.segments.size) {
            // Update mode
            config.segments.forEachIndexed { index, segment ->
                val view = overlayViews[index]
                val params = view.layoutParams as WindowManager.LayoutParams
                val safeLayout = safeOverlayLayout(
                    width = segment.width,
                    height = segment.height,
                    x = segment.xOffset,
                    y = segment.yOffset,
                )
                
                // Update params
                var changed = false
                if (params.width != safeLayout.width) { params.width = safeLayout.width; changed = true }
                if (params.height != safeLayout.height) { params.height = safeLayout.height; changed = true }
                if (params.x != safeLayout.x) { params.x = safeLayout.x; changed = true }
                if (params.y != safeLayout.y) { params.y = safeLayout.y; changed = true }
                
                if (changed) {
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // Fallback implies view might be detached, shouldn't happen commonly
                    }
                }
                
                // Update Color (Debug)
                if (config.isVisible) {
                    val color = themePalette[index % themePalette.size]
                    val drawable = GradientDrawable().apply {
                        setColor(color)
                        // Only show solid primary border for the expanded/active overlay
                        if (index == config.activeSegmentIndex) {
                            setStroke(3, primaryColor)
                        }
                    }
                    view.background = drawable
                    view.elevation = 0f
                } else {
                    view.background = null
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.elevation = 0f
                }
                
                // Update gesture listener
                // Since we created the detector in the loop, we can't easily "update" its inner logic if it closes over the *old* segment.
                // WE MUST re-attach the listener or make the listener dynamic.
                // The cleanest way is to just attach a NEW listener wrapper that reads the LATEST segment config.
                // But `segment` here is from the new config.
                // Creating a new detector is cheap.
                attachTouchListener(view, segment, index)
            }
        } else {
            // Rebuild mode (Count changed)
            overlayViews.forEach { 
                try { windowManager?.removeView(it) } catch(e: Exception) {} 
            }
            overlayViews.clear()
            
            config.segments.forEachIndexed { index, segment ->
                val view = View(this)
                val safeLayout = safeOverlayLayout(
                    width = segment.width,
                    height = segment.height,
                    x = segment.xOffset,
                    y = segment.yOffset,
                )
                val params = WindowManager.LayoutParams(
                    safeLayout.width,
                    safeLayout.height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                
                params.gravity = Gravity.TOP or Gravity.START
                params.x = safeLayout.x
                params.y = safeLayout.y
                view.systemGestureExclusionRects = emptyList()
                
                 if (config.isVisible) {
                    val color = themePalette[index % themePalette.size]
                    val drawable = GradientDrawable().apply {
                        setColor(color)
                        // Only show solid primary border for the expanded/active overlay
                        if (index == config.activeSegmentIndex) {
                            setStroke(3, primaryColor)
                        }
                    }
                    view.background = drawable
                    view.elevation = 0f
                } else {
                    view.background = null
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.elevation = 0f
                }

                attachTouchListener(view, segment, index)
                
                try {
                    windowManager?.addView(view, params)
                    overlayViews.add(view)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(view: View, segment: OverlaySegment, segmentIndex: Int) {
        val viewConfiguration = ViewConfiguration.get(this)
        val horizontalDistanceThreshold = viewConfiguration.scaledTouchSlop * 4f
        val verticalDistanceThreshold = viewConfiguration.scaledTouchSlop * 2f
        val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val action = segment.gestures[GestureType.DOUBLE_TAP] ?: ActionType.NONE
                if (action != ActionType.NONE) { performAction(action, segment); return true }
                return false
            }
            
            override fun onLongPress(e: MotionEvent) {
                val action = segment.gestures[GestureType.LONG_PRESS] ?: ActionType.NONE
                if (action != ActionType.NONE) performAction(action, segment)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                 // User wants "buttons behind to be clickable" 
                 // We temporarily disable touch on our window and dispatch the click through.
                 propagateSingleTap(view, e.rawX, e.rawY)
                 return false
            }
            
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                val candidates = FlingClassifier.candidates(
                    diffX = diffX,
                    diffY = diffY,
                    velocityX = velocityX,
                    velocityY = velocityY,
                    horizontalDistanceThreshold = horizontalDistanceThreshold,
                    verticalDistanceThreshold = verticalDistanceThreshold,
                    minimumVelocity = minimumFlingVelocity,
                )

                // Resolve every configured direction before applying a default action.
                // This prevents a vertical default from consuming a diagonal fling that
                // has an explicitly configured horizontal action.
                FlingClassifier.firstConfiguredAction(candidates, segment.gestures)?.let { action ->
                    performAction(action, segment)
                    return true
                }

                return GestureType.SWIPE_DOWN in candidates &&
                    handleDefaultSwipeDown(segment, segmentIndex, e1.rawX)
            }
        })
        
        var lastTapTime: Long = 0
        var tapCount = 0
        
        view.setOnTouchListener { _, event ->
             if (event.action == MotionEvent.ACTION_DOWN) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < 400) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = currentTime
                
                if (tapCount == 3) {
                     val action = segment.gestures[GestureType.TRIPLE_TAP] ?: ActionType.NONE
                     if (action != ActionType.NONE) {
                         performAction(action, segment)
                         tapCount = 0 
                         return@setOnTouchListener true
                     }
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }
    }
    
    private fun handleDefaultSwipeDown(
        segment: OverlaySegment,
        segmentIndex: Int,
        touchX: Float,
    ): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val isFirstFullWidthOverlay = segmentIndex == 0 && segment.width >= screenWidth

        if (isFirstFullWidthOverlay) {
            if (touchX < screenWidth / 2f) {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            } else {
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            }
        } else {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    private fun performAction(action: ActionType, segment: OverlaySegment) {
        if (action == ActionType.NONE) return

        val captureResult = when (action) {
            ActionType.CTS_AUTO -> performCapture(null)
            ActionType.CTS_LENS -> performCapture(true)
            ActionType.CTS_MULTI -> performCapture(false)
            else -> null
        }
        if (captureResult != null) {
            if (captureResult == CaptureStartResult.STARTED) {
                vibrateAction()
            } else {
                android.util.Log.w(
                    "CircleToSearch",
                    "Overlay capture gesture could not start: $captureResult",
                )
            }
            return
        }

        vibrateAction()

        when(action) {
            ActionType.SCREENSHOT -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            ActionType.FLASHLIGHT -> toggleFlashlight()
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
            ActionType.OPEN_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.OPEN_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.OPEN_APP -> {
                // Open App Logic
                val packageName = segment.gestureData[findGestureForAction(segment, ActionType.OPEN_APP)]
                if (!packageName.isNullOrEmpty()) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                }
            }
            ActionType.CTS_AUTO,
            ActionType.CTS_LENS,
            ActionType.CTS_MULTI -> Unit // Handled before haptic feedback.
            ActionType.SPLIT_SCREEN -> {
                val success = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                if (!success) {
                    android.widget.Toast.makeText(this, "Split Screen not supported or failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ActionType.SCROLL_TOP -> performScroll(true)
            ActionType.SCROLL_BOTTOM -> performScroll(false)
            ActionType.SCREEN_OFF -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                     android.widget.Toast.makeText(this, "Screen Off requires Android 9+", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ActionType.TOGGLE_AUTO_ROTATE -> toggleAutoRotate()
            ActionType.MEDIA_PLAY_PAUSE -> injectMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.MEDIA_NEXT -> injectMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionType.MEDIA_PREVIOUS -> injectMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ActionType.NONE -> Unit
        }
    }

    private fun vibrateAction() {
        // Haptic feedback for action trigger
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
             @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }
    
    // Helpers for new actions
    
    private fun performScroll(toTop: Boolean) {
        android.util.Log.d("CTS_Scroll", "performScroll called - toTop=$toTop")
        
        // We simulate multiple quick swipes instead of one long one
        // This is more reliable and less likely to be cancelled
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        
        // Perform 3 quick swipes with delays
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        for (i in 0..2) {
            handler.postDelayed({
                // Scroll To Top = Swipe DOWN (drag content down, revealing top)
                // Scroll To Bottom = Swipe UP (drag content up, revealing bottom)
                val startY = if (toTop) displayMetrics.heightPixels * 0.3f else displayMetrics.heightPixels * 0.7f
                val endY = if (toTop) displayMetrics.heightPixels * 0.7f else displayMetrics.heightPixels * 0.3f
                
                android.util.Log.d("CTS_Scroll", "Scroll swipe #${i+1} - toTop=$toTop, centerX=$centerX, startY=$startY, endY=$endY")
                
                val path = android.graphics.Path().apply {
                    moveTo(centerX, startY)
                    lineTo(centerX, endY)
                }
                // Shorter, faster swipes (200ms each)
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                
                val success = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        android.util.Log.d("CTS_Scroll", "Scroll swipe #${i+1} COMPLETED")
                    }
                    
                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        android.util.Log.e("CTS_Scroll", "Scroll swipe #${i+1} CANCELLED")
                    }
                }, null)
                
                android.util.Log.d("CTS_Scroll", "Scroll swipe #${i+1} dispatched: $success")
            }, i * 250L) // 250ms delay between each swipe
        }
    }
    
    private fun injectMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val eventTime = android.os.SystemClock.uptimeMillis()
        
        val downEvent = android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_UP, keyCode, 0)
        
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }
    
    private fun toggleAutoRotate() {
        if (android.provider.Settings.System.canWrite(this)) {
            val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
            val next = if (current == 1) 0 else 1
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, next)
            android.widget.Toast.makeText(this, "Auto Rotate: ${if (next == 1) "ON" else "OFF"}", android.widget.Toast.LENGTH_SHORT).show()
        } else {
             android.widget.Toast.makeText(this, "Permission required for Auto Rotate", android.widget.Toast.LENGTH_SHORT).show()
             val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                 data = android.net.Uri.parse("package:$packageName")
                 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
             }
             startActivity(intent)
        }
    }
    
    private fun findGestureForAction(segment: OverlaySegment, action: ActionType): GestureType {
        return segment.gestures.entries.firstOrNull { it.value == action }?.key ?: GestureType.DOUBLE_TAP
    }
    
    // Pass-through Logic for Single Tap
    // We must temporarily make the window UNTOUCHABLE so the injected gesture falls through to the app below.
    // Otherwise, the injected tap hits our own overlay (loop/blocked).
    private fun propagateSingleTap(view: View, x: Float, y: Float) {
        val params = view.layoutParams as WindowManager.LayoutParams
        val originalFlags = params.flags
        
        // Make untouchable
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager?.updateViewLayout(view, params)
        
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50) // 50ms tap duration (more standard)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        
        // Wait for WindowManager to update input focus before dispatching
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    restoreFlags()
                }
    
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    restoreFlags()
                }
                
                fun restoreFlags() {
                    // Restore original flags (Touchable) using main thread to be safe with UI
                    handler.post {
                        params.flags = originalFlags
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // View might be removed
                        }
                    }
                }
            }, null)
        }, 100) // 100ms Delay to ensure 'untouchable' takes effect solidly
    }
    
    private fun toggleFlashlight() {
         try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            // This is tricky because we don't know current state easily without callback.
            // For now, let's assume valid flash.
            // A robust implementation needs a callback to track state.
            // We'll just try to turn it on for a second for testing or we need a tracked state.
            // Let's implement a simple tracking using static var or prefs?
            // Or just ignore toggle for now and just turn ON? No, user expects toggle.
            // Let's use a static state?
            if (isFlashlightOn) {
                cameraManager.setTorchMode(cameraId, false)
                isFlashlightOn = false
            } else {
                cameraManager.setTorchMode(cameraId, true)
                isFlashlightOn = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performCapture(
        searchModeOverride: Boolean? = null,
        translateScreen: Boolean = false,
        assistantOwnerId: Long? = null,
        assistantCallback: ((AssistantCaptureResult) -> Unit)? = null,
    ): CaptureStartResult {
        android.util.Log.d("CircleToSearch", "performCapture called. hasWindowManager=${windowManager != null}")

        val request = ActiveCaptureRequest(
            id = captureRequestSequence.incrementAndGet(),
            assistantOwnerId = assistantOwnerId,
            assistantCallback = assistantCallback,
        )
        if (!reserveCaptureRequest(request)) {
            android.util.Log.d("CircleToSearch", "Capture already in progress, skipping")
            return CaptureStartResult.BUSY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activeCaptureRequest.compareAndSet(request, null)
            if (request.assistantCallback == null) {
                android.widget.Toast.makeText(
                    this,
                    "Circle to Search capture requires Android 11 or newer",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return CaptureStartResult.UNSUPPORTED
        }

        // A confirmed invocation starts model loading alongside screen capture, never on service connection.
        request.warmUpJob = serviceScope.launch {
            try {
                com.akslabs.circletosearch.ocr.PaddleOcrEngine.warmUp(applicationContext)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Actual OCR reports failure and can establish a fresh worker connection.
            }
        }

        armCaptureTimeout(request, ACCESSIBILITY_CAPTURE_TIMEOUT_MS)

        return try {
            attemptScreenshot(
                request = request,
                searchModeOverride = searchModeOverride,
                translateScreen = translateScreen,
                retriesLeft = 2,
            )
            CaptureStartResult.STARTED
        } catch (error: Exception) {
            android.util.Log.e("CircleToSearch", "Unable to start screenshot capture", error)
            finishCaptureFailure(request = request, errorCode = null)
            CaptureStartResult.UNSUPPORTED
        }
    }

    private fun reserveCaptureRequest(request: ActiveCaptureRequest): Boolean {
        while (true) {
            val current = activeCaptureRequest.get()
            if (current == null) {
                if (activeCaptureRequest.compareAndSet(null, request)) return true
                continue
            }

            val replacesOlderCapture =
                request.assistantCallback != null &&
                    request.assistantOwnerId != current.assistantOwnerId
            if (!replacesOlderCapture) return false
            if (!activeCaptureRequest.compareAndSet(current, null)) continue

            current.warmUpJob?.cancel()
            current.timeoutGeneration.incrementAndGet()
            try {
                current.assistantCallback?.invoke(AssistantCaptureResult.Cancelled)
            } catch (error: Exception) {
                android.util.Log.e(
                    "CircleToSearch",
                    "Failed to supersede stale assistant capture",
                    error,
                )
            }
        }
    }

    private fun armCaptureTimeout(request: ActiveCaptureRequest, timeoutMillis: Long) {
        val timeoutGeneration = request.timeoutGeneration.incrementAndGet()
        mainHandler.postDelayed(
            {
                if (request.timeoutGeneration.get() == timeoutGeneration) {
                    android.util.Log.e(
                        "CircleToSearch",
                        "Capture request ${request.id} timed out",
                    )
                    finishCaptureFailure(request = request, errorCode = null)
                }
            },
            timeoutMillis,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun attemptScreenshot(
        request: ActiveCaptureRequest,
        searchModeOverride: Boolean?,
        translateScreen: Boolean,
        retriesLeft: Int,
    ) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (activeCaptureRequest.get() !== request) {
                        screenshot.hardwareBuffer.close()
                        return
                    }

                    var copy: Bitmap? = null
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        var hardwareBitmap: Bitmap? = null
                        try {
                            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (hardwareBitmap == null) {
                                finishCaptureFailure(request = request, errorCode = null)
                                return
                            }
                            copy = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hardwareBitmap?.takeUnless { it.isRecycled }?.recycle()
                            hardwareBuffer.close()
                        }

                        if (copy == null) {
                            finishCaptureFailure(request = request, errorCode = null)
                            return
                        }

                        if (request.assistantCallback != null) {
                            val capturedBitmap = checkNotNull(copy)
                            copy = null
                            if (!activeCaptureRequest.compareAndSet(request, null)) {
                                capturedBitmap.takeUnless { it.isRecycled }?.recycle()
                                return
                            }
                            request.timeoutGeneration.incrementAndGet()
                            try {
                                request.assistantCallback.invoke(
                                    AssistantCaptureResult.Success(capturedBitmap),
                                )
                            } catch (callbackError: Exception) {
                                request.warmUpJob?.cancel()
                                capturedBitmap.takeUnless { it.isRecycled }?.recycle()
                                android.util.Log.e(
                                    "CircleToSearch",
                                    "Assistant capture callback failed",
                                    callbackError,
                                )
                            }
                            return
                        }

                        if (translateScreen) {
                            val sourceBitmap = checkNotNull(copy)
                            copy = null
                            // Translation can legitimately take longer than the
                            // screenshot callback. Invalidate the short capture
                            // watchdog and retain owner identity for this phase.
                            armCaptureTimeout(request, 60_000L)
                            serviceScope.launch {
                                var translatedBitmap: Bitmap? = null
                                try {
                                    val outcome = ScreenTranslator(applicationContext).use { translator ->
                                        translator.translateScreen(sourceBitmap, textNodes = null)
                                    }
                                    when (outcome) {
                                        is ScreenTranslationOutcome.Translated -> {
                                            translatedBitmap = outcome.bitmap
                                            sourceBitmap.recycle()
                                        }
                                        is ScreenTranslationOutcome.Unchanged -> {
                                            translatedBitmap = sourceBitmap
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        val completedBitmap = checkNotNull(translatedBitmap)
                                        if (activeCaptureRequest.get() === request) {
                                            outcome.userMessage()?.let { message ->
                                                android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        publishCapturedBitmap(
                                            request = request,
                                            bitmap = completedBitmap,
                                            searchModeOverride = searchModeOverride,
                                        )
                                        translatedBitmap = null
                                    }
                                } catch (error: CancellationException) {
                                    sourceBitmap.takeUnless { it.isRecycled }?.recycle()
                                    translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                                    activeCaptureRequest.compareAndSet(request, null)
                                    request.warmUpJob?.cancel()
                                    throw error
                                } catch (e: Exception) {
                                    android.util.Log.e("CircleToSearch", "Translation pipeline failed", e)
                                    translatedBitmap?.takeUnless { it.isRecycled }?.recycle()
                                    withContext(Dispatchers.Main) {
                                        if (activeCaptureRequest.get() === request) {
                                            android.widget.Toast.makeText(applicationContext,
                                                "Translation failed. Try again", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        if (!sourceBitmap.isRecycled) {
                                            publishCapturedBitmap(
                                                request = request,
                                                bitmap = sourceBitmap,
                                                searchModeOverride = searchModeOverride,
                                            )
                                        } else {
                                            finishCaptureFailure(
                                                request = request,
                                                errorCode = null,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val capturedBitmap = checkNotNull(copy)
                            copy = null
                            val posted = mainHandler.post {
                                publishCapturedBitmap(
                                    request = request,
                                    bitmap = capturedBitmap,
                                    searchModeOverride = searchModeOverride,
                                )
                            }
                            if (!posted) {
                                capturedBitmap.takeUnless { it.isRecycled }?.recycle()
                                finishCaptureFailure(request = request, errorCode = null)
                            }
                        }

                    } catch (e: Exception) {
                        copy?.takeUnless { it.isRecycled }?.recycle()
                        android.util.Log.e("CircleToSearch", "Screenshot conversion failed", e)
                        finishCaptureFailure(request = request, errorCode = null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (activeCaptureRequest.get() !== request) return
                    android.util.Log.e("CircleToSearch", "Screenshot failed with error code: $errorCode")

                    // Android enforces a per-service screenshot interval. A
                    // too-soon call returns INTERVAL_TIME_SHORT; retry after the
                    // throttle window instead of failing the user's tap silently.
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retriesLeft > 0) {
                        val posted = mainHandler.postDelayed(
                            {
                                if (activeCaptureRequest.get() !== request) return@postDelayed
                                try {
                                    attemptScreenshot(
                                        request = request,
                                        searchModeOverride = searchModeOverride,
                                        translateScreen = translateScreen,
                                        retriesLeft = retriesLeft - 1,
                                    )
                                } catch (error: Exception) {
                                    android.util.Log.e("CircleToSearch", "Screenshot retry failed", error)
                                    finishCaptureFailure(request = request, errorCode = null)
                                }
                            },
                            400L,
                        )
                        if (!posted) {
                            finishCaptureFailure(request = request, errorCode = null)
                        }
                        return
                    }

                    finishCaptureFailure(request = request, errorCode = errorCode)
                }
            }
        )
    }

    private fun finishCaptureFailure(
        request: ActiveCaptureRequest,
        errorCode: Int?,
    ) {
        if (!activeCaptureRequest.compareAndSet(request, null)) return
        request.warmUpJob?.cancel()
        request.timeoutGeneration.incrementAndGet()
        val assistantCallback = request.assistantCallback
        if (assistantCallback != null) {
            try {
                assistantCallback(AssistantCaptureResult.Failure(errorCode))
            } catch (callbackError: Exception) {
                android.util.Log.e("CircleToSearch", "Assistant failure callback failed", callbackError)
            }
            return
        }

        mainHandler.post {
            android.widget.Toast.makeText(
                this@CircleToSearchAccessibilityService,
                "Couldn't capture the screen, try again",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun publishCapturedBitmap(
        request: ActiveCaptureRequest,
        bitmap: Bitmap,
        searchModeOverride: Boolean?,
    ) {
        if (!activeCaptureRequest.compareAndSet(request, null)) {
            bitmap.takeUnless { it.isRecycled }?.recycle()
            return
        }
        request.timeoutGeneration.incrementAndGet()
        try {
            val captureId = BitmapRepository.setScreenshot(bitmap)
            launchOverlay(searchModeOverride, captureId)
        } catch (error: Exception) {
            BitmapRepository.clearIfSame(bitmap)
            bitmap.takeUnless { it.isRecycled }?.recycle()
            request.warmUpJob?.cancel()
            android.util.Log.e("CircleToSearch", "Failed to publish captured bitmap", error)
            android.widget.Toast.makeText(
                this,
                "Couldn't open Circle to Search",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun launchOverlay(
        searchModeOverride: Boolean? = null,
        captureId: Long = BitmapRepository.getSnapshot()?.captureId
            ?: BitmapRepository.NO_CAPTURE_ID,
    ) {
        android.util.Log.d("CircleToSearchAccess", "AccessibilityService launching OverlayActivity")
        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // Disable animation for faster feel
            searchModeOverride?.let { putExtra("EXTRA_SEARCH_MODE_OVERRIDE", it) }
            if (captureId != BitmapRepository.NO_CAPTURE_ID) {
                putExtra(OverlayActivity.EXTRA_CAPTURE_ID, captureId)
            }
        }
        startActivity(intent)
    }

    // Custom ImageView that clips to rounded corners on the Canvas level
    // This is much more robust than OutlineProvider for rapid movements and scaling.
    @SuppressLint("AppCompatCustomView")
    private class RoundedImageView(context: android.content.Context) : android.widget.ImageView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val matrix = Matrix()
        private val radius = 12f * context.resources.displayMetrics.density
        private var cachedBitmap: Bitmap? = null
        private var cachedShader: BitmapShader? = null
        var onRelease: (() -> Unit)? = null

        override fun setImageBitmap(bm: Bitmap?) {
            // The source can also be used by a pending save/share operation.
            cachedBitmap = bm
            cachedShader = bm?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            paint.shader = cachedShader
            super.setImageBitmap(bm)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val bitmap = cachedBitmap ?: return
            if (bitmap.isRecycled) return

            // Adjust shader to current view bounds
            matrix.reset()
            matrix.setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            cachedShader!!.setLocalMatrix(matrix)

            paint.shader = cachedShader
            rect.set(0f, 0f, width.toFloat(), height.toFloat())

            // This never "looses roundness" because it's rendering at the pixel level on each frame
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        fun releaseResources() {
            val release = onRelease
            onRelease = null
            release?.invoke()
            animate().cancel()
            setOnTouchListener(null)
            setImageBitmap(null)
        }
    }

    private fun removePinnedMenu(menu: View) {
        try { windowManager?.removeView(menu) } catch (_: IllegalArgumentException) {}
        pinnedActionMenus.remove(menu)
        (menu as? android.view.ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) group.getChildAt(i).setOnClickListener(null)
        }
    }

    private fun removePinnedView(view: View) {
        try { windowManager?.removeView(view) } catch (_: IllegalArgumentException) {}
        (view as? RoundedImageView)?.releaseResources()
        pinnedOverlayViews.remove(view)
        pinnedImageLeases.remove(view)?.close()
    }

    private fun showPinnedArea(bitmap: Bitmap, rect: android.graphics.Rect) {
        val manager = windowManager ?: return
        if (bitmap.isRecycled) return
        val lease = pinnedImageBudget.reserve(bitmap.allocationByteCount.toLong()) ?: run {
            android.widget.Toast.makeText(this, R.string.pinned_image_memory_limit, android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val displayMetrics = resources.displayMetrics
        var screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        var safeScreenHeight = safeOverlayLayout(
            width = screenWidth,
            height = screenHeight,
            x = 0,
            y = 0,
        ).height

        // Initial Position: Center of the selection
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        
        // --- Phase 32: 1:1 Scaling ---
        // Use natural dimensions of the cropped bitmap (match what user saw in lens)
        val width = bitmap.width
        val height = bitmap.height

        val initialLayout = safeOverlayLayout(
            width = width,
            height = height,
            x = centerX - width / 2,
            y = centerY - height / 2,
        )
        val params = WindowManager.LayoutParams(
            initialLayout.width, initialLayout.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = initialLayout.x
        params.y = initialLayout.y

        val pinnedView = RoundedImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            elevation = 0f
            clipToOutline = true
            systemGestureExclusionRects = emptyList()
            
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false
            var isScaling = false
            var currentMenu: View? = null
            
            // --- Phase 45: Sticker Physics ---
            var velocityTracker: android.view.VelocityTracker? = null
            var flingAnimator: android.animation.ValueAnimator? = null
            
            val stopFling = {
                flingAnimator?.cancel()
                flingAnimator = null
            }

            // --- Phase 33: ScaleGestureDetector for pinch zoom ---
            val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    val scaleFactor = detector.scaleFactor
                    
                    val newWidth = (params.width * scaleFactor).toInt()
                    val newHeight = (params.height * scaleFactor).toInt()
                    
                    // Constraints: 15% to 95% of screen
                    val minDim = (screenWidth * 0.15f).toInt()
                    val maxDim = (screenWidth * 0.95f).toInt()
                    
                    if (
                        newWidth in minDim..maxDim &&
                        newHeight in minDim..maxDim &&
                        newWidth <= screenWidth &&
                        newHeight <= safeScreenHeight
                    ) {
                        // Adjust position to scale from center of pinch
                        val focusX = detector.focusX
                        val focusY = detector.focusY
                        
                        params.x -= ((newWidth - params.width) * (focusX / this@apply.width)).toInt()
                        params.y -= ((newHeight - params.height) * (focusY / this@apply.height)).toInt()
                        
                        params.width = newWidth
                        params.height = newHeight
                        params.x = params.x.coerceIn(
                            0,
                            (screenWidth - params.width).coerceAtLeast(0),
                        )
                        params.y = params.y.coerceIn(
                            0,
                            (safeScreenHeight - params.height).coerceAtLeast(0),
                        )
                        windowManager?.updateViewLayout(this@apply, params)
                    }
                    return true
                }
            })

            // --- Phase 31: GestureDetector for reliable long-press ---
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (!isDragging) {
                        // Show actions
                        if (currentMenu?.isAttachedToWindow != true) {
                            showPinnedActions(this@apply, bitmap, params) { menu ->
                                currentMenu = menu
                            }
                        }
                    }
                }
            })

            onRelease = {
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = null
                currentMenu?.let(::removePinnedMenu)
                currentMenu = null
            }

            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { v, event ->
                // Feed both detectors
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        screenWidth = resources.displayMetrics.widthPixels
                        safeScreenHeight = safeOverlayLayout(
                            width = screenWidth,
                            height = resources.displayMetrics.heightPixels,
                            x = 0,
                            y = 0,
                        ).height
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isScaling = false
                        stopFling()
                        velocityTracker?.recycle()
                        velocityTracker = android.view.VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        isScaling = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isScaling) return@setOnTouchListener true
                        
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            // If dragging, dismiss menu
                            currentMenu?.let { menu ->
                                removePinnedMenu(menu)
                            }
                            currentMenu = null
                        }
                        
                        params.x = (initialX + dx).coerceIn(
                            0,
                            (screenWidth - params.width).coerceAtLeast(0),
                        )
                        params.y = (initialY + dy).coerceIn(
                            0,
                            (safeScreenHeight - params.height).coerceAtLeast(0),
                        )
                        windowManager?.updateViewLayout(v, params)
                        velocityTracker?.addMovement(event)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isScaling = false
                        if (isDragging) {
                            velocityTracker?.computeCurrentVelocity(1000)
                            val vx = velocityTracker?.xVelocity ?: 0f
                            val vy = velocityTracker?.yVelocity ?: 0f
                            
                            if (Math.abs(vx) > 300 || Math.abs(vy) > 300) {
                                // Start physics fling
                                flingAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = 2000
                                    interpolator = android.view.animation.LinearInterpolator()
                                    var lastTime = 0f
                                    var currVx = vx * 2.2f
                                    var currVy = vy * 2.2f
                                    
                                    addUpdateListener { anim ->
                                        val faction = anim.animatedFraction
                                        val dt = faction - lastTime
                                        lastTime = faction
                                        
                                        params.x += (currVx * dt * 0.95f).toInt()
                                        params.y += (currVy * dt * 0.95f).toInt()
                                        
                                        // Bounce off edges (Bouncy!)
                                        if (params.x < 0 || params.x + params.width > screenWidth) {
                                            currVx = -currVx * 0.9f 
                                            params.x = params.x.coerceIn(
                                                0,
                                                (screenWidth - params.width).coerceAtLeast(0),
                                            )
                                        }
                                        if (params.y < 0 || params.y + params.height > safeScreenHeight) {
                                            currVy = -currVy * 0.9f
                                            params.y = params.y.coerceIn(
                                                0,
                                                (safeScreenHeight - params.height).coerceAtLeast(0),
                                            )
                                        }
                                        
                                        try { 
                                            windowManager?.updateViewLayout(v, params)
                                            // v.invalidateOutline() // No longer needed with canvas clipping
                                        } catch(e: Exception) { 
                                            anim.cancel()
                                            flingAnimator = null
                                        }
                                        
                                        // Lower friction for fun play
                                        currVx *= 0.992f
                                        currVy *= 0.992f
                                        
                                        // Safety check: if velocity is zero or view is gone, stop
                                        if (Math.abs(currVx) < 10 && Math.abs(currVy) < 10) {
                                            anim.cancel()
                                            flingAnimator = null
                                        }
                                    }
                                    addListener(object : android.animation.AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: android.animation.Animator) {
                                            flingAnimator = null
                                        }
                                        override fun onAnimationCancel(animation: android.animation.Animator) {
                                            flingAnimator = null
                                        }
                                    })
                                    start()
                                }
                            }
                        }
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            manager.addView(pinnedView, params)
            pinnedOverlayViews += pinnedView
            pinnedImageLeases[pinnedView] = lease
            
            // --- Beautiful Pin Animation ---
            pinnedView.scaleX = 0f
            pinnedView.scaleY = 0f
            pinnedView.rotation = -15f
            pinnedView.alpha = 0f
            pinnedView.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .rotation(0f)
                .alpha(1f)
                .setDuration(450)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .withEndAction {
                    pinnedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()
                }
                .start()
                
        } catch (e: Exception) {
            removePinnedView(pinnedView)
            lease.close()
            android.util.Log.e("CircleToSearch", "Failed to add pinned view", e)
        }
    }

    private fun showPinnedActions(anchorView: View, bitmap: Bitmap, anchorParams: WindowManager.LayoutParams, onMenuCreated: (View) -> Unit) {
        if (anchorView !in pinnedOverlayViews) return
        val displayMetrics = resources.displayMetrics
        val iconSize = (44 * displayMetrics.density).toInt()
        val btnPadding = (8 * displayMetrics.density).toInt()
        val menuPadding = (10 * displayMetrics.density).toInt()
        val cornerRadius = 32f * displayMetrics.density

        // --- Phase 40: CopyText-style Text Toolbar ---
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        
        // Match exactly CopyTextOverlayManager's palette mapping
        val toolbarBgColor = try { getColor(android.R.color.system_surface_container_light) } catch(e: Exception) { if (isNight) Color.parseColor("#FF1C1C1C") else Color.parseColor("#FFF3EDF7") }
        val primaryColor = try { getColor(android.R.color.system_accent1_600) } catch(e: Exception) { if (isNight) Color.parseColor("#FFD0BCFF") else Color.parseColor("#FF6750A4") }
        val contentColor = Color.WHITE
        val borderColor = if (isNight) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")

        val menuLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(menuPadding, menuPadding, menuPadding, menuPadding)
            elevation = 24f
            
            val background = GradientDrawable().apply {
                setColor(toolbarBgColor)
                setCornerRadius(cornerRadius)
                setStroke((1 * displayMetrics.density).toInt(), borderColor)
            }
            setBackground(background)
            
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        }

        fun createTextActionButton(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
            text = label
            setTextColor(contentColor)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 12f // Roughly 30f in Paint logic
            
            // Professional pill background (Solid Primary)
            val btnDrawable = GradientDrawable().apply {
                setColor(primaryColor)
                setCornerRadius(20 * displayMetrics.density)
            }
            background = btnDrawable
            
            setPadding((16 * displayMetrics.density).toInt(), 0, (16 * displayMetrics.density).toInt(), 0)
            
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * displayMetrics.density).toInt()
            )
            setOnClickListener { onClick() }
        }

        // --- Action: Share ---
        menuLayout.addView(createTextActionButton("SHARE") {
            val operation = pinnedImageLeases[anchorView]?.retain() ?: return@createTextActionButton
            removePinnedMenu(menuLayout)

            serviceScope.launch {
                try {
                    val path = withContext(Dispatchers.IO) {
                        ImageUtils.saveShareBitmap(
                            context = this@CircleToSearchAccessibilityService,
                            bitmap = bitmap,
                            prefix = "pinned",
                        )
                    }
                    val file = java.io.File(path)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@CircleToSearchAccessibilityService,
                        "com.akslabs.circletosearch.fileprovider",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Pinned selection", uri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        startActivity(
                            Intent.createChooser(shareIntent, "Share Pin").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.e("CircleToSearch", "Failed to share pinned image", error)
                    withContext(Dispatchers.Main.immediate) {
                        android.widget.Toast.makeText(
                            this@CircleToSearchAccessibilityService,
                            "Could not share selection",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }.invokeOnCompletion { operation.close() }
        })

        // --- Action: Delete ---
        menuLayout.addView(createTextActionButton("DELETE") {
            removePinnedView(anchorView)
        })

        // --- Action: Save ---
        val saveBtn = createTextActionButton("SAVE") {
            val operation = pinnedImageLeases[anchorView]?.retain() ?: return@createTextActionButton
            removePinnedMenu(menuLayout)
            serviceScope.launch {
                val success = withContext(Dispatchers.IO) {
                    ImageUtils.saveToGallery(this@CircleToSearchAccessibilityService, bitmap)
                }
                withContext(Dispatchers.Main.immediate) {
                    android.widget.Toast.makeText(this@CircleToSearchAccessibilityService, if (success) "Saved to Gallery" else "Save failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.invokeOnCompletion { operation.close() }
        }
        // Add spacing only if needed (not on the last item)
        menuLayout.addView(saveBtn)
        
        // Ensure buttons have proper spacing between them but not after the last one
        for (i in 0 until menuLayout.childCount - 1) {
            (menuLayout.getChildAt(i).layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd = (8 * displayMetrics.density).toInt()
        }

        // Measure properly and clamp to screen bounds to avoid cutoff
        menuLayout.measure(View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val measuredMenuWidth = menuLayout.measuredWidth
        val measuredMenuHeight = menuLayout.measuredHeight

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        menuParams.gravity = Gravity.TOP or Gravity.START
        
        // Perfectly center the menu above the sticker
        // anchorParams.x is start of sticker, add stickerWidth/2 to get center, then subtract menuWidth/2
        var targetX = anchorParams.x + (anchorParams.width / 2) - (measuredMenuWidth / 2)
        
        // Clamp to screen edges to prevent cutoff on left/right
        if (targetX < menuPadding) targetX = (menuPadding).toInt()
        if (targetX + measuredMenuWidth > displayMetrics.widthPixels - menuPadding) {
            targetX = (displayMetrics.widthPixels - measuredMenuWidth - menuPadding).toInt()
        }
        menuParams.x = targetX.toInt()
        
        val yPadding = (12 * displayMetrics.density).toInt()
        menuParams.y = if (anchorParams.y > measuredMenuHeight + yPadding) {
            anchorParams.y - measuredMenuHeight - yPadding
        } else {
            anchorParams.y + anchorParams.height + yPadding
        }
        val safeMenuLayout = safeOverlayLayout(
            width = measuredMenuWidth,
            height = measuredMenuHeight,
            x = menuParams.x,
            y = menuParams.y,
        )
        menuParams.x = safeMenuLayout.x
        menuParams.y = safeMenuLayout.y
        menuParams.width = safeMenuLayout.width
        menuParams.height = safeMenuLayout.height
        menuLayout.systemGestureExclusionRects = emptyList()

        try {
            windowManager?.addView(menuLayout, menuParams)
            pinnedActionMenus += menuLayout
            onMenuCreated(menuLayout)
        } catch (e: Exception) {
            android.util.Log.e("CircleToSearch", "Failed to add menu view", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Forward scroll events to the Copy Text overlay for live re-scan
        // Only if it's a scroll event and the copy manager is active
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            copyTextManager?.rescanNodes()
        }
    }

    override fun onInterrupt() {}

    companion object {
        private var instanceReference = WeakReference<CircleToSearchAccessibilityService>(null)
        private val instance: CircleToSearchAccessibilityService?
            get() = instanceReference.get()
            
        private var isFlashlightOn = false // Simple static state tracking
        
        fun setCopyTextManager(manager: CopyTextOverlayManager?) {
            instance?.copyTextManager = manager
        }

        internal fun triggerCapture(
            assistantOwnerId: Long? = null,
            assistantCallback: ((AssistantCaptureResult) -> Unit)? = null,
        ): CaptureStartResult {
            android.util.Log.d("CircleToSearch", "triggerCapture static called. instance=${instance != null}")
            return instance?.performCapture(
                searchModeOverride = null,
                assistantOwnerId = assistantOwnerId,
                assistantCallback = assistantCallback,
            ) ?: CaptureStartResult.UNAVAILABLE
        }

        internal fun cancelAssistantCapture(assistantOwnerId: Long) {
            val service = instance ?: return
            val request = service.activeCaptureRequest.get() ?: return
            val callback = request.assistantCallback ?: return
            if (
                request.assistantOwnerId != assistantOwnerId ||
                !service.activeCaptureRequest.compareAndSet(request, null)
            ) {
                return
            }
            request.warmUpJob?.cancel()
            request.timeoutGeneration.incrementAndGet()
            try {
                callback(AssistantCaptureResult.Cancelled)
            } catch (error: Exception) {
                android.util.Log.e(
                    "CircleToSearch",
                    "Failed to cancel losing assistant capture",
                    error,
                )
            }
        }

        internal fun relaunchAssistantOverlay(
            lease: AssistantInvocationGate.Lease,
            assistToken: String?,
            captureId: Long,
        ): Boolean {
            val service = instance ?: return false
            return try {
                val intent = Intent(service, OverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("triggered_by", "assistant_recovery")
                    putExtra(OverlayActivity.EXTRA_ASSIST_INVOCATION_ID, lease.sessionId)
                    putExtra(OverlayActivity.EXTRA_ASSIST_INVOCATION_GENERATION, lease.generation)
                    putExtra(OverlayActivity.EXTRA_CAPTURE_ID, captureId)
                    assistToken?.let { putExtra(OverlayActivity.EXTRA_ASSIST_TOKEN, it) }
                }
                service.startActivity(intent)
                true
            } catch (error: Exception) {
                android.util.Log.e(
                    "CircleToSearch",
                    "Unable to relaunch assistant overlay ${lease.sessionId}",
                    error,
                )
                false
            }
        }
        
        fun triggerTranslateCapture() {
            android.util.Log.d("CircleToSearch", "triggerTranslateCapture static called. instance=${instance != null}")
            instance?.performCapture(null, translateScreen = true)
        }

        fun pinArea(bitmap: Bitmap, rect: android.graphics.Rect) {
            android.util.Log.d("CircleToSearch", "pinArea static called. instance=${instance != null}")
            instance?.showPinnedArea(bitmap, rect)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch(Dispatchers.IO) {
            try {
                StorageUtils.pruneTransientImageCache(
                    this@CircleToSearchAccessibilityService,
                )
            } catch (error: Exception) {
                android.util.Log.w(
                    "CircleToSearch",
                    "Unable to prune transient image cache",
                    error,
                )
            }
        }
        // configManager init moved to onServiceConnected or safe lazy? 
        // WindowManager is needed for views which happens in onServiceConnected mostly.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instanceReference.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instanceReference.clear()
        if (accessibilityButtonRegistered) {
            try {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(
                    accessibilityButtonCallback,
                )
            } catch (error: RuntimeException) {
                android.util.Log.w(
                    "CircleToSearch",
                    "Unable to unregister the accessibility shortcut",
                    error,
                )
            }
            accessibilityButtonRegistered = false
        }
        activeCaptureRequest.getAndSet(null)?.let { request ->
            request.timeoutGeneration.incrementAndGet()
            request.assistantCallback?.let { callback ->
                try {
                    callback(AssistantCaptureResult.Cancelled)
                } catch (error: Exception) {
                    android.util.Log.e(
                        "CircleToSearch",
                        "Failed to close active capture during service shutdown",
                        error,
                    )
                }
            }
        }
        executor.shutdownNow()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        overlayPrefs.unregisterOnSharedPreferenceChangeListener(overlayPrefsListener)
        serviceJob.cancel()
        copyTextManager = null
        
        overlayViews.forEach { view ->
             try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        pinnedActionMenus.toList().forEach(::removePinnedMenu)
        pinnedOverlayViews.toList().forEach(::removePinnedView)
        hideBubble()
        com.akslabs.circletosearch.ocr.PaddleOcrEngine.requestReleaseIfIdle()
        super.onDestroy()
    }
}
