package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akslabs.circletosearch.ui.components.CopyTextOverlayManager
import com.akslabs.circletosearch.ui.components.TextNode
import com.akslabs.circletosearch.ui.components.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyTextOverlayManagerInstrumentedTest {
    @Test
    fun explicitUserDismissNotifiesOwnerExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            val manager = CopyTextOverlayManager(instrumentation.targetContext, bitmap)
            var dismissCount = 0
            manager.getOverlayView(onDismiss = { dismissCount++ })

            manager.dismiss()
            manager.dismiss()
            manager.disposeSilently()

            assertEquals(1, dismissCount)
            bitmap.recycle()
        }
    }

    @Test
    fun lifecycleDisposalNeverLooksLikeAUserDismiss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            val manager = CopyTextOverlayManager(instrumentation.targetContext, bitmap)
            var dismissCount = 0
            manager.getOverlayView(onDismiss = { dismissCount++ })

            manager.disposeSilently()
            manager.dismiss()

            assertEquals(0, dismissCount)
            bitmap.recycle()
        }
    }

    @Test
    fun repeatedEmptyTapsDoNotDisableLaterTextSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val manager = CopyTextOverlayManager(
                context = instrumentation.targetContext,
                screenshotBitmap = bitmap,
            )
            val backgroundActions = mutableListOf<Int>()
            val container = manager.getOverlayView(
                onDismiss = {},
                onBackgroundTouch = { action, _, _ -> backgroundActions += action },
            ) as FrameLayout
            val selectionView = container.getChildAt(0)
            assertEquals(View.LAYER_TYPE_NONE, selectionView.layerType)
            val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
            selectionView.measure(exact, exact)
            selectionView.layout(0, 0, 100, 100)
            manager.updateNodes(listOf(textNode()))

            repeat(10) {
                assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 90f, 90f)))
                assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 90f, 90f)))
            }
            assertEquals(
                List(10) { listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP) }.flatten(),
                backgroundActions,
            )

            assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 15f, 15f)))
            assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 15f, 15f)))
            assertEquals(20, backgroundActions.size)

            manager.dismiss()
            bitmap.recycle()
        }
    }

    @Test
    fun dragStartingOnRecognizedTextBecomesBackgroundCircleGesture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val manager = CopyTextOverlayManager(instrumentation.targetContext, bitmap)
            val backgroundActions = mutableListOf<Int>()
            val container = manager.getOverlayView(
                onDismiss = {},
                onBackgroundTouch = { action, _, _ -> backgroundActions += action },
            ) as FrameLayout
            val selectionView = container.getChildAt(0)
            val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
            selectionView.measure(exact, exact)
            selectionView.layout(0, 0, 100, 100)
            manager.updateNodes(listOf(textNode()))

            assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 15f, 15f)))
            assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 60f, 60f)))
            assertTrue(selectionView.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 70f, 70f)))

            assertEquals(
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
                backgroundActions,
            )
            manager.disposeSilently()
            bitmap.recycle()
        }
    }

    private fun textNode(): TextNode = TextNode(
        id = "word",
        fullText = "word",
        bounds = Rect(10, 10, 20, 20),
        words = listOf(
            Word(
                text = "word",
                index = 0,
                startIndex = 0,
                endIndex = 4,
                bounds = RectF(10f, 10f, 20f, 20f),
            ),
        ),
    )

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)
}
