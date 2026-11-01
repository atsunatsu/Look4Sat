package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.SatItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteSelectionReloadTest {
    @Test fun preservePendingChangesWhileRefreshingNamesAndAddingEntries() {
        val existing = listOf(SatItem(1, "Old A", true), SatItem(2, "Old B", false))
        val reloaded = listOf(SatItem(1, "Updated A"), SatItem(2, "Updated B"), SatItem(3, "Added C"))
        val result = mergeReloadedSatellites(existing, reloaded, setOf(2, 3))
        assertEquals(listOf(true, false, true), result.map { it.isSelected })
        assertEquals("Updated A", result.first().name)
    }

    @Test fun firstLoadUsesSavedIds() {
        val result = mergeReloadedSatellites(emptyList(), listOf(SatItem(1,"A"), SatItem(2,"B")), setOf(2))
        assertEquals(listOf(false, true), result.map { it.isSelected })
    }
}
