package com.paddle.ocr.engine

/** Owns crops from creation through inference, including partially prepared batches. */
internal inline fun <T> withRecognitionCropBatch(
    order: List<Int>,
    start: Int,
    batchSize: Int,
    cancellationCheck: () -> Unit,
    createCrop: (Int) -> T,
    isUsable: (T) -> Boolean,
    release: (T) -> Unit,
    recognize: (List<T>, List<Int>) -> Unit,
): Int {
    require(batchSize > 0)
    val owned = mutableListOf<T>()
    val crops = mutableListOf<T>()
    val indices = mutableListOf<Int>()
    var next = start
    try {
        while (next < order.size && crops.size < batchSize) {
            cancellationCheck()
            val index = order[next]
            val crop = createCrop(index)
            owned.add(crop)
            if (isUsable(crop)) {
                crops.add(crop)
                indices.add(index)
            }
            next++
        }
        cancellationCheck()
        recognize(crops, indices)
        return next
    } finally {
        owned.forEach { release(it) }
    }
}
