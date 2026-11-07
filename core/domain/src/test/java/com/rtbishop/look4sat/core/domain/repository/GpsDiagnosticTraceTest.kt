package com.rtbishop.look4sat.core.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDiagnosticTraceTest {
    @Test fun traceIsResetAndBounded() {
        GpsDiagnosticTrace.reset()
        repeat(35) { GpsDiagnosticTrace.record("event-$it") }
        assertEquals(30, GpsDiagnosticTrace.events.value.size)
        assertEquals("event-5", GpsDiagnosticTrace.events.value.first())
        GpsDiagnosticTrace.reset()
        assertEquals(emptyList<String>(), GpsDiagnosticTrace.events.value)
    }
}
