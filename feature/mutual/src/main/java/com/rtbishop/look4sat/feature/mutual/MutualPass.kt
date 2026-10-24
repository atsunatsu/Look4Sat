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
package com.rtbishop.look4sat.feature.mutual

/**
 * A mutual pass where a satellite is visible from two stations simultaneously.
 *
 * @param catNum Satellite catalog number
 * @param name Satellite name
 * @param startTime Common AOS time (millis)
 * @param endTime Common LOS time (millis)
 * @param maxElevationA Peak elevation for station A
 * @param maxElevationB Peak elevation for station B
 * @param elevationSamples List of (time, (elevationA, elevationB)) pairs
 * @param trackSamples List of radar-track samples (azimuth/elevation for both stations, degrees)
 */
data class MutualPass(
    val catNum: Int,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val maxElevationA: Double,
    val maxElevationB: Double,
    val elevationSamples: List<Pair<Long, Pair<Double, Double>>>,
    val trackSamples: List<TrackSample> = emptyList()
)

/**
 * One radar-track sample: satellite position as seen from both stations.
 * All angles are in degrees.
 */
data class TrackSample(
    val time: Long,
    val azimuthA: Double,
    val elevationA: Double,
    val azimuthB: Double,
    val elevationB: Double
)

/**
 * Find the first continuous interval of simultaneous visibility within the
 * radar page's selected pass. Sampling never crosses into a later pass.
 */
internal fun radarMutualVisibilityWindow(
    radarAosTime: Long,
    radarLosTime: Long,
    bothVisibleAt: (Long) -> Boolean
): Pair<Long, Long>? {
    if (radarLosTime <= radarAosTime) return null
    val interval = 5_000L
    var firstVisible: Long? = null
    var lastVisible: Long? = null
    var time = radarAosTime
    while (true) {
        if (bothVisibleAt(time)) {
            if (firstVisible == null) firstVisible = time
            lastVisible = time
        } else if (firstVisible != null) {
            break
        }
        if (time >= radarLosTime) break
        time = minOf(time + interval, radarLosTime)
    }
    val start = firstVisible ?: return null
    val end = lastVisible ?: return null
    return if (end > start) start to end else null
}

/**
 * Applies the optional swipe-to-focus filter while preserving each pass's index
 * in the unfiltered result list (used to keep expanded-card selection stable).
 */
internal fun visibleMutualPasses(
    passes: List<MutualPass>,
    focusedCatNum: Int?
): List<Pair<Int, MutualPass>> =
    passes.mapIndexed { index, pass -> index to pass }
        .filter { (_, pass) -> focusedCatNum == null || pass.catNum == focusedCatNum }