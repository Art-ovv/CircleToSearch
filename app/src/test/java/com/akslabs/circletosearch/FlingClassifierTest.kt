package com.akslabs.circletosearch

import com.akslabs.circletosearch.data.ActionType
import com.akslabs.circletosearch.data.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlingClassifierTest {
    @Test
    fun classifiesAllCardinalDirections() {
        assertEquals(listOf(GestureType.SWIPE_RIGHT), classify(200f, 0f, 300f, 0f))
        assertEquals(listOf(GestureType.SWIPE_LEFT), classify(-200f, 0f, -300f, 0f))
        assertEquals(listOf(GestureType.SWIPE_DOWN), classify(0f, 200f, 0f, 300f))
        assertEquals(listOf(GestureType.SWIPE_UP), classify(0f, -200f, 0f, -300f))
    }

    @Test
    fun exactDiagonalUsesStableHorizontalTieBreak() {
        assertEquals(
            listOf(GestureType.SWIPE_LEFT, GestureType.SWIPE_UP),
            classify(-200f, -200f, -300f, -300f),
        )
    }

    @Test
    fun diagonalReturnsBothCandidatesInDominantOrder() {
        assertEquals(
            listOf(GestureType.SWIPE_UP, GestureType.SWIPE_LEFT),
            classify(-160f, -200f, -300f, -300f),
        )
    }

    @Test
    fun distanceAndVelocityThresholdsAreExclusive() {
        assertTrue(classify(100f, 0f, 101f, 0f).isEmpty())
        assertTrue(classify(101f, 0f, 100f, 0f).isEmpty())
        assertEquals(listOf(GestureType.SWIPE_RIGHT), classify(101f, 0f, 101f, 0f))

        assertTrue(classify(0f, 50f, 0f, 101f).isEmpty())
        assertTrue(classify(0f, 51f, 0f, 100f).isEmpty())
        assertEquals(listOf(GestureType.SWIPE_DOWN), classify(0f, 51f, 0f, 101f))
    }

    @Test
    fun configuredSecondaryActionWinsBeforeDownwardDefault() {
        val candidates = listOf(GestureType.SWIPE_DOWN, GestureType.SWIPE_RIGHT)

        assertEquals(
            ActionType.BACK,
            FlingClassifier.firstConfiguredAction(
                candidates,
                mapOf(
                    GestureType.SWIPE_DOWN to ActionType.NONE,
                    GestureType.SWIPE_RIGHT to ActionType.BACK,
                ),
            ),
        )
    }

    private fun classify(
        diffX: Float,
        diffY: Float,
        velocityX: Float,
        velocityY: Float,
    ) = FlingClassifier.candidates(
        diffX = diffX,
        diffY = diffY,
        velocityX = velocityX,
        velocityY = velocityY,
        horizontalDistanceThreshold = 100f,
        verticalDistanceThreshold = 50f,
        minimumVelocity = 100f,
    )
}
