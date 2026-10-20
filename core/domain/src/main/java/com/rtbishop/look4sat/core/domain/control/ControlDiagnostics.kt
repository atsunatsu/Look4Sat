/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ControlDiagnosticSource {
    RADIO
}

enum class ControlDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

data class ControlDiagnosticEvent(
    val sequence: Long,
    val timestampMillis: Long,
    val source: ControlDiagnosticSource,
    val severity: ControlDiagnosticSeverity,
    val stage: String,
    val message: String
)

interface IControlDiagnostics {
    val events: StateFlow<List<ControlDiagnosticEvent>>

    fun record(
        source: ControlDiagnosticSource,
        stage: String,
        message: String,
        severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO
    )

    fun clear()

    fun exportText(): String
}

class ControlDiagnosticsBuffer(
    private val capacity: Int = 256,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : IControlDiagnostics {
    private val lock = Any()
    private val mutableEvents = MutableStateFlow<List<ControlDiagnosticEvent>>(emptyList())
    private var nextSequence = 1L

    init {
        require(capacity > 0)
    }

    override val events: StateFlow<List<ControlDiagnosticEvent>> = mutableEvents.asStateFlow()

    override fun record(
        source: ControlDiagnosticSource,
        stage: String,
        message: String,
        severity: ControlDiagnosticSeverity
    ) {
        val normalizedStage = stage.trim().take(MAX_STAGE_LENGTH).ifBlank { "unknown" }
        val normalizedMessage = message.replace('\r', ' ').replace('\n', ' ')
            .replace(MAC_ADDRESS, "[redacted-device]")
            .replace(IPV4_ADDRESS, "[redacted-host]")
            .replace(HOST_AND_PORT, "[redacted-endpoint]")
            .trim().take(MAX_MESSAGE_LENGTH).ifBlank { "no detail" }
        synchronized(lock) {
            val event = ControlDiagnosticEvent(
                sequence = nextSequence++,
                timestampMillis = nowMillis(),
                source = source,
                severity = severity,
                stage = normalizedStage,
                message = normalizedMessage
            )
            mutableEvents.value = (mutableEvents.value + event).takeLast(capacity)
        }
    }

    override fun clear() {
        synchronized(lock) { mutableEvents.value = emptyList() }
    }

    override fun exportText(): String = buildString {
        appendLine("Look4Sat control diagnostics")
        appendLine("timestamp_ms|sequence|source|severity|stage|message")
        events.value.forEach { event ->
            append(event.timestampMillis)
            append('|').append(event.sequence)
            append('|').append(event.source)
            append('|').append(event.severity)
            append('|').append(event.stage)
            append('|').append(event.message)
            appendLine()
        }
    }

    private companion object {
        const val MAX_STAGE_LENGTH = 64
        const val MAX_MESSAGE_LENGTH = 512
        val MAC_ADDRESS = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
        val IPV4_ADDRESS = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        val HOST_AND_PORT = Regex("(?i)\\b[a-z0-9][a-z0-9.-]*:[0-9]{1,5}\\b")
    }
}

object NoOpControlDiagnostics : IControlDiagnostics {
    private val emptyEvents = MutableStateFlow<List<ControlDiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<ControlDiagnosticEvent>> = emptyEvents.asStateFlow()
    override fun record(
        source: ControlDiagnosticSource,
        stage: String,
        message: String,
        severity: ControlDiagnosticSeverity
    ) = Unit
    override fun clear() = Unit
    override fun exportText(): String = "Look4Sat control diagnostics\nNo events\n"
}
