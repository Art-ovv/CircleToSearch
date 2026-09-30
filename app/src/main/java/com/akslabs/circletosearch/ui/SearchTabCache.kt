package com.akslabs.circletosearch.ui

/** UI-thread cache: keep recent live tabs and restore evicted tabs only when selected again. */
internal class SearchTabCache<K : Any, V : Any, S : Any>(
    private val capacity: Int,
    private val save: (V) -> S,
    private val destroy: (V) -> Unit,
) {
    init { require(capacity > 0) }

    private val live = LinkedHashMap<K, V>()
    private val saved = mutableMapOf<K, S>()
    val values: List<V> get() = live.values.toList()
    operator fun get(key: K): V? = live[key]
    fun containsKey(key: K): Boolean = live.containsKey(key)
    fun forEach(action: (Map.Entry<K, V>) -> Unit) = live.entries.toList().forEach(action)

    fun acquire(key: K, create: (S?) -> V): V {
        live.remove(key)?.let { value ->
            live[key] = value
            return value
        }
        while (live.size >= capacity) evict(live.keys.first())
        val value = create(saved[key])
        saved.remove(key)
        live[key] = value
        return value
    }

    fun keepOnly(key: K) {
        live.keys.filter { it != key }.forEach(::evict)
    }

    private fun evict(key: K) {
        val value = live[key] ?: return
        saved[key] = save(value)
        live.remove(key)
        destroy(value)
    }

    fun clear() {
        val old = values
        live.clear()
        saved.clear()
        old.forEach(destroy)
    }
}
