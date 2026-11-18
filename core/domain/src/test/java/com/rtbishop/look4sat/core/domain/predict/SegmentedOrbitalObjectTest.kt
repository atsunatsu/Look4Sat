/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 Look4Sat contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.domain.predict

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

class SegmentedOrbitalObjectTest {
    private val station = GeoPos(22.27, 113.57)
    private val base = OrbitalData(
        name = "ISS (ZARYA)",
        epoch = 26320.5,
        meanmo = 15.49,
        eccn = 0.0007,
        incl = 51.63,
        raan = 266.0,
        argper = 31.0,
        meanan = 350.0,
        catnum = 25544,
        bstar = 0.0001
    )
    private val first = base.copy(epoch = 26320.5, meanan = 10.0)
    private val second = base.copy(epoch = 26320.75, meanan = 110.0)
    private val expectedFallback = base.getObject()
    private val orbit = SegmentedOrbitalObject(expectedFallback, listOf(second, first))

    private fun millis(epoch: OrbitalData): Long =
        (315446400000.0 + epoch.epochDaynum * 86_400_000.0).roundToLong()

    private fun assertSamePosition(expected: OrbitalObject, actual: OrbitalObject, time: Long) {
        val a = expected.getPosition(station, time)
        val b = actual.getPosition(station, time)
        assertEquals(a.azimuth, b.azimuth, 1e-10)
        assertEquals(a.elevation, b.elevation, 1e-10)
        assertEquals(a.distance, b.distance, 1e-6)
        assertEquals(a.distanceRate, b.distanceRate, 1e-9)
    }

    @Test
    fun `uses each segment at its own epoch rather than always the newest`() {
        assertSamePosition(first.getObject(), orbit, millis(first) + 60_000L)
        assertSamePosition(second.getObject(), orbit, millis(second) + 60_000L)
    }

    @Test
    fun `falls back to normal GP before and after supplemental coverage`() {
        assertSamePosition(expectedFallback, orbit, millis(first) - 60_000L)
        assertSamePosition(expectedFallback, orbit, millis(second) + 7 * 60 * 60_000L)
    }

    @Test
    fun `full position and elevation use the same segment as ordinary position`() {
        val whenObserved = millis(second) + 20 * 60_000L
        assertEquals(
            second.getObject().getElevation(station, whenObserved),
            orbit.getElevation(station, whenObserved),
            1e-10
        )
        val expected = second.getObject().getFullPosition(station, whenObserved)
        val actual = orbit.getFullPosition(station, whenObserved)
        assertEquals(expected.latitude, actual.latitude, 1e-10)
        assertEquals(expected.longitude, actual.longitude, 1e-10)
    }

    @Test
    fun `ignores supplemental elements from another NORAD object`() {
        val invalid = base.copy(catnum = 12345, meanan = 220.0)
        val filtered = SegmentedOrbitalObject(base.getObject(), listOf(invalid))
        assertSamePosition(base.getObject(), filtered, millis(first) + 60_000L)
    }
}
