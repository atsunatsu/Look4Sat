/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.MutualPassData

/**
 * A mutual pass covers only the time when both stations see the satellite.
 * The local radar still needs the entire local above-horizon track, including
 * the portions before the second station's AOS and after its LOS.
 *
 * The supplied mutual window is guaranteed to be above the local horizon in
 * the normal case. Find the local 0° crossings on either side, refining to
 * within one second. Keep the original window when visibility cannot be
 * established rather than crossing into an unrelated orbit.
 */
internal fun expandMatchedRadarLocalVisibilityWindow(
    mutualStart: Long,
    mutualEnd: Long,
    isAboveHorizon: (Long) -> Boolean
): Pair<Long, Long> {
    if (mutualEnd <= mutualStart ||
        !isAboveHorizon(mutualStart) || !isAboveHorizon(mutualEnd)
    ) return mutualStart to mutualEnd

    val stepMs = 15_000L
    val maxSteps = 60 * 60_000L / stepMs

    fun findEdge(anchor: Long, direction: Long): Long {
        var inside = anchor
        repeat(maxSteps.toInt()) {
            val outside = inside + direction * stepMs
            if (!isAboveHorizon(outside)) {
                var lower = minOf(inside, outside)
                var upper = maxOf(inside, outside)
                while (upper - lower > 1_000L) {
                    val mid = lower + (upper - lower) / 2L
                    if (isAboveHorizon(mid) == (direction < 0L)) {
                        upper = mid
                    } else {
                        lower = mid
                    }
                }
                return if (direction < 0L) upper else lower
            }
            inside = outside
        }
        return anchor
    }

    return findEdge(mutualStart, -1L) to findEdge(mutualEnd, 1L)
}

/** Only the position inside this station's own above-horizon interval is live. */
internal fun isWithinMatchedRadarWindow(time: Long, window: Pair<Long, Long>?): Boolean =
    window != null && time >= window.first && time < window.second

/** Use the station A coordinates that generated this match, if supplied. */
internal fun matchedRadarObserverPosition(session: MutualPassData, fallback: GeoPos): GeoPos {
    val lat = session.stationALatitude ?: return fallback
    val lon = session.stationALongitude ?: return fallback
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
        return fallback
    }
    return GeoPos(lat, lon)
}
