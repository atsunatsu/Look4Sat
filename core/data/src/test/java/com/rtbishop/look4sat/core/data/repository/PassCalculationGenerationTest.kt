package com.rtbishop.look4sat.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassCalculationGenerationTest {
    @Test
    fun olderCalculationCannotPublishAfterNewerRequestStarts() {
        val gate = PassCalculationGeneration()
        val older = gate.begin()
        assertTrue(gate.isLatest(older))
        val newer = gate.begin()
        assertFalse(gate.isLatest(older))
        assertTrue(gate.isLatest(newer))
    }
}
