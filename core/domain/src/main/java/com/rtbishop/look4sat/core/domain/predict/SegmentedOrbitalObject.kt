/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 Look4Sat contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.predict

import com.rtbishop.look4sat.core.domain.source.Sources
import kotlin.math.roundToLong

/**
 * Uses the NASA-OEM-derived SupGP fit valid at the *requested* time.
 * A single "freshest epoch" TLE cannot represent the segmented ISS prediction.
 *
 * The ordinary GP orbit remains the fallback outside the fit windows, in gaps,
 * or when the supplemental feed is unavailable. No other satellite is affected.
 */
class SegmentedOrbitalObject(
    private val fallback: OrbitalObject,
    elements: List<OrbitalData>
) : OrbitalObject(fallback.data) {

    private data class Segment(val startMillis: Long, val orbit: OrbitalObject)

    private val segments: List<Segment> = elements
        .filter { it.catnum == Sources.ISS_CATNUM && it.meanmo > 0.0 && it.epochDaynum.isFinite() }
        .distinctBy { it.epochDaynum }
        .sortedBy { it.epochDaynum }
        .map { element ->
            Segment(
                startMillis = (EPOCH_BASE_MILLIS + element.epochDaynum * MILLIS_PER_DAY).roundToLong(),
                orbit = element.getObject()
            )
        }

    private fun orbitFor(time: Long): OrbitalObject {
        // Rightmost segment with a start epoch at or before the requested time.
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (segments[mid].startMillis <= time) low = mid + 1 else high = mid - 1
        }
        val segment = segments.getOrNull(high) ?: return fallback
        // Fits are six hours long. A small tolerance covers maneuver-shifted epochs;
        // do not propagate a SupGP segment arbitrarily beyond its useful window.
        return if (time - segment.startMillis < SEGMENT_WINDOW_MILLIS) segment.orbit else fallback
    }

    @Synchronized
    override fun getPosition(pos: GeoPos, time: Long): OrbitalPos =
        orbitFor(time).getPosition(pos, time)

    @Synchronized
    override fun getElevation(pos: GeoPos, time: Long): Double =
        orbitFor(time).getElevation(pos, time)

    @Synchronized
    override fun getFullPosition(pos: GeoPos, time: Long): OrbitalPos =
        orbitFor(time).getFullPosition(pos, time)

    private companion object {
        const val EPOCH_BASE_MILLIS = 315446400000L // 1979-12-31 00:00 UTC
        const val MILLIS_PER_DAY = 86_400_000.0
        const val SEGMENT_WINDOW_MILLIS = (6 * 60 + 10) * 60_000L
    }
}
