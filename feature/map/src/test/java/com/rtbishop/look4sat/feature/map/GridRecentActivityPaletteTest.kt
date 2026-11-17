package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridRecentActivityPaletteTest {

    private val today = requireNotNull(parseGridMasterUtcDate("2026-11-16"))

    @Test
    fun mapsEveryExactDayBoundaryToTheRequestedBand() {
        assertEquals(0, recentActivityTimeBand("2026-11-16", today))
        assertEquals(0, recentActivityTimeBand("2026-11-09", today))
        assertEquals(1, recentActivityTimeBand("2026-11-08", today))
        assertEquals(1, recentActivityTimeBand("2026-10-17", today))
        assertEquals(2, recentActivityTimeBand("2026-10-16", today))
        assertEquals(2, recentActivityTimeBand("2026-08-18", today))
        assertEquals(3, recentActivityTimeBand("2026-08-17", today))
        assertEquals(3, recentActivityTimeBand("2026-05-20", today))
        assertEquals(4, recentActivityTimeBand("2026-05-19", today))
        assertEquals(4, recentActivityTimeBand("2025-11-16", today))
        assertEquals(5, recentActivityTimeBand("2025-11-15", today))
    }

    @Test
    fun mapsThreeAndTenYearBoundariesUsingCalendarYears() {
        assertEquals(5, recentActivityTimeBand("2023-11-16", today))
        assertEquals(6, recentActivityTimeBand("2023-11-15", today))
        assertEquals(6, recentActivityTimeBand("2016-11-16", today))
        assertEquals(7, recentActivityTimeBand("2016-11-15", today))
    }

    @Test
    fun ignoresMissingMalformedAndFutureDates() {
        assertNull(recentActivityTimeBand(null, today))
        assertNull(recentActivityTimeBand("", today))
        assertNull(recentActivityTimeBand("2026-13-40", today))
        assertNull(recentActivityTimeBand("2026-11-17", today))
    }

    @Test
    fun parsesLeapDaysAndRejectsImpossibleDates() {
        assertEquals(5, recentActivityTimeBand("2024-11-16", today))
        assertNull(parseGridMasterUtcDate("2025-02-29"))
        assertNull(parseGridMasterUtcDate("2026-04-31"))
    }

    @Test
    fun paletteMatchesTheEightRequestedHexColors() {
        assertEquals(0xFFF44336.toInt(), recentActivityTimePaletteColor(0))
        assertEquals(0xFFFF7043.toInt(), recentActivityTimePaletteColor(1))
        assertEquals(0xFFFFB74D.toInt(), recentActivityTimePaletteColor(2))
        assertEquals(0xFFC6DBEF.toInt(), recentActivityTimePaletteColor(3))
        assertEquals(0xFF9ECAE1.toInt(), recentActivityTimePaletteColor(4))
        assertEquals(0xFF6BAED6.toInt(), recentActivityTimePaletteColor(5))
        assertEquals(0xFF3182BD.toInt(), recentActivityTimePaletteColor(6))
        assertEquals(0xFF08519C.toInt(), recentActivityTimePaletteColor(7))
    }

    @Test
    fun mapFillOpacityMatchesExistingGridActivityOverlay() {
        assertEquals(110, GRID_ACTIVITY_FILL_ALPHA)
    }
}
