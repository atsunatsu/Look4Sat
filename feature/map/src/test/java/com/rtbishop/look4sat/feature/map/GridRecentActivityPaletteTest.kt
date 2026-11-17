package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GridRecentActivityPaletteTest {

    private val today = LocalDate.of(2026, 11, 16)

    @Test
    fun mapsEveryExactDayBoundaryToTheRequestedBand() {
        assertEquals(0, recentActivityTimeBand(today.toString(), today))
        assertEquals(0, recentActivityTimeBand(today.minusDays(7).toString(), today))
        assertEquals(1, recentActivityTimeBand(today.minusDays(8).toString(), today))
        assertEquals(1, recentActivityTimeBand(today.minusDays(30).toString(), today))
        assertEquals(2, recentActivityTimeBand(today.minusDays(31).toString(), today))
        assertEquals(2, recentActivityTimeBand(today.minusDays(90).toString(), today))
        assertEquals(3, recentActivityTimeBand(today.minusDays(91).toString(), today))
        assertEquals(3, recentActivityTimeBand(today.minusDays(180).toString(), today))
        assertEquals(4, recentActivityTimeBand(today.minusDays(181).toString(), today))
        assertEquals(4, recentActivityTimeBand(today.minusDays(365).toString(), today))
        assertEquals(5, recentActivityTimeBand(today.minusDays(366).toString(), today))
    }

    @Test
    fun mapsThreeAndTenYearBoundariesUsingCalendarYears() {
        assertEquals(5, recentActivityTimeBand(today.minusYears(3).toString(), today))
        assertEquals(6, recentActivityTimeBand(today.minusYears(3).minusDays(1).toString(), today))
        assertEquals(6, recentActivityTimeBand(today.minusYears(10).toString(), today))
        assertEquals(7, recentActivityTimeBand(today.minusYears(10).minusDays(1).toString(), today))
    }

    @Test
    fun ignoresMissingMalformedAndFutureDates() {
        assertNull(recentActivityTimeBand(null, today))
        assertNull(recentActivityTimeBand("", today))
        assertNull(recentActivityTimeBand("2026-13-40", today))
        assertNull(recentActivityTimeBand(today.plusDays(1).toString(), today))
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
