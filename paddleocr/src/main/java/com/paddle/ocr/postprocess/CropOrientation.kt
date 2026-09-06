package com.paddle.ocr.postprocess

/** Orientation chosen from the actual integer dimensions passed to warpPerspective. */
internal class CropOrientation(width: Int, height: Int) {
    val rotatedCounterClockwise = height.toDouble() / width >= 1.5

    /** Source corners corresponding to recognition-image TL, TR, BR, BL. */
    fun <T> recognitionCorners(corners: List<T>): List<T> {
        require(corners.size == 4)
        return if (rotatedCounterClockwise) {
            listOf(corners[1], corners[2], corners[3], corners[0])
        } else {
            corners.toList()
        }
    }
}
