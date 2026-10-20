package com.rtbishop.look4sat.core.domain.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDiagnosticsTest {

    @Test
    fun keepsBoundedOrderedEventsAndExportsSingleLineMessages() {
        var now = 1_000L
        val diagnostics = ControlDiagnosticsBuffer(capacity = 2, nowMillis = { now++ })
        diagnostics.record(ControlDiagnosticSource.RADIO, "connect", "starting")
        diagnostics.record(ControlDiagnosticSource.RADIO, "write", "line one\nline two")
        diagnostics.record(
            ControlDiagnosticSource.RADIO,
            "failure",
            "not acknowledged",
            ControlDiagnosticSeverity.ERROR
        )

        val events = diagnostics.events.value
        assertEquals(listOf(2L, 3L), events.map(ControlDiagnosticEvent::sequence))
        assertEquals("line one line two", events.first().message)
        val exported = diagnostics.exportText()
        assertFalse(exported.contains("starting"))
        assertTrue(exported.contains("1002|3|RADIO|ERROR|failure|not acknowledged"))
    }

    @Test
    fun clearPublishesAnEmptySnapshot() {
        val diagnostics = ControlDiagnosticsBuffer()
        diagnostics.record(ControlDiagnosticSource.RADIO, "tracking", "ready")
        diagnostics.clear()
        assertTrue(diagnostics.events.value.isEmpty())
    }

    @Test
    fun redactsNetworkAndBluetoothEndpoints() {
        val diagnostics = ControlDiagnosticsBuffer()
        diagnostics.record(
            ControlDiagnosticSource.RADIO,
            "connect",
            "failed 192.0.2.10 at rig.local:4533 using 00:11:22:33:44:55"
        )
        val text = diagnostics.exportText()
        assertFalse(text.contains("192.0.2.10"))
        assertFalse(text.contains("rig.local"))
        assertFalse(text.contains("00:11:22:33:44:55"))
        assertTrue(text.contains("[redacted-host]"))
        assertTrue(text.contains("[redacted-endpoint]"))
        assertTrue(text.contains("[redacted-device]"))
    }
}
