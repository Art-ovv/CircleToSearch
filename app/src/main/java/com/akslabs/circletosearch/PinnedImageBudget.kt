package com.akslabs.circletosearch

/** Counts decoded pixels until the pin and all of its save/share operations have finished. */
internal class PinnedImageBudget(private val limitBytes: Long = 64L * 1024 * 1024) {
    private var usedBytes = 0L

    @Synchronized
    fun reserve(bytes: Long): Lease? {
        require(bytes > 0)
        if (bytes > limitBytes - usedBytes) return null
        usedBytes += bytes
        return Lease(Allocation(bytes))
    }

    internal class Allocation(val bytes: Long, var references: Int = 1)

    inner class Lease internal constructor(private val allocation: Allocation) : AutoCloseable {
        private var closed = false

        fun retain(): Lease? = synchronized(this@PinnedImageBudget) {
            if (closed) return null
            allocation.references++
            Lease(allocation)
        }

        override fun close() = synchronized(this@PinnedImageBudget) {
            if (!closed) {
                closed = true
                if (--allocation.references == 0) usedBytes -= allocation.bytes
            }
        }
    }
}
