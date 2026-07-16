package com.akslabs.circletosearch

import com.akslabs.circletosearch.data.GestureType
import com.akslabs.circletosearch.data.ActionType
import kotlin.math.abs

internal object FlingClassifier {
    fun firstConfiguredAction(
        candidates: List<GestureType>,
        actions: Map<GestureType, ActionType>,
    ): ActionType? = candidates
        .asSequence()
        .map { actions[it] ?: ActionType.NONE }
        .firstOrNull { it != ActionType.NONE }

    fun candidates(
        diffX: Float,
        diffY: Float,
        velocityX: Float,
        velocityY: Float,
        horizontalDistanceThreshold: Float,
        verticalDistanceThreshold: Float,
        minimumVelocity: Float,
        diagonalRatio: Float = 2f,
    ): List<GestureType> {
        val absX = abs(diffX)
        val absY = abs(diffY)
        val horizontal = absX > horizontalDistanceThreshold && abs(velocityX) > minimumVelocity
        val vertical = absY > verticalDistanceThreshold && abs(velocityY) > minimumVelocity

        if (!horizontal && !vertical) return emptyList()

        val horizontalDirection = if (diffX > 0f) {
            GestureType.SWIPE_RIGHT
        } else {
            GestureType.SWIPE_LEFT
        }
        val verticalDirection = if (diffY > 0f) {
            GestureType.SWIPE_DOWN
        } else {
            GestureType.SWIPE_UP
        }

        if (horizontal && vertical) {
            val smaller = minOf(absX, absY)
            val isDiagonal = smaller > 0f && maxOf(absX, absY) / smaller < diagonalRatio
            if (isDiagonal) {
                return if (absX >= absY) {
                    listOf(horizontalDirection, verticalDirection)
                } else {
                    listOf(verticalDirection, horizontalDirection)
                }
            }
        }

        return when {
            horizontal && (!vertical || absX >= absY) -> listOf(horizontalDirection)
            vertical -> listOf(verticalDirection)
            else -> listOf(horizontalDirection)
        }
    }
}
