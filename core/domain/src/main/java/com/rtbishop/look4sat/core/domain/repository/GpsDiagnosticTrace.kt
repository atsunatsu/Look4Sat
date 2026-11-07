package com.rtbishop.look4sat.core.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Temporary support trace for the Settings > GPS diagnostic build.
 * Only records provider/permission events, never coordinates or station data.
 */
object GpsDiagnosticTrace {
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun reset() { _events.value = emptyList() }

    fun record(event: String) {
        _events.update { (it + event).takeLast(30) }
    }
}
