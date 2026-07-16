package com.akslabs.circletosearch.data

import com.akslabs.circletosearch.ui.components.TextNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistSnapshot(
    val token: String? = null,
    val nodes: List<TextNode> = emptyList(),
    val ready: Boolean = false,
    val coordinateWidth: Int = 0,
    val coordinateHeight: Int = 0,
)

object AssistDataRepository {
    private val _snapshot = MutableStateFlow(AssistSnapshot())
    val snapshot: StateFlow<AssistSnapshot> = _snapshot.asStateFlow()

    fun begin(token: String) {
        _snapshot.value = AssistSnapshot(token = token)
    }

    fun publish(
        token: String,
        nodes: List<TextNode>,
        coordinateWidth: Int,
        coordinateHeight: Int,
    ): Boolean {
        while (true) {
            val current = _snapshot.value
            if (current.token != token) return false
            val next = current.copy(
                nodes = nodes,
                ready = true,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
            )
            if (_snapshot.compareAndSet(current, next)) return true
        }
    }

    fun nodesFor(token: String?): List<TextNode> {
        if (token == null) return emptyList()
        return _snapshot.value.takeIf { it.token == token && it.ready }?.nodes.orEmpty()
    }

    fun clear(token: String) {
        while (true) {
            val current = _snapshot.value
            if (current.token != token) return
            if (_snapshot.compareAndSet(current, AssistSnapshot())) return
        }
    }

    fun clearAll() {
        _snapshot.value = AssistSnapshot()
    }
}
