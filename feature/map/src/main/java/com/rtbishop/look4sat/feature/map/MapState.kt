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
package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.model.MapSource

data class MapState(
    val mapData: MapData? = null,
    val isLightUi: Boolean = false,
    val isUtc: Boolean = false,
    val mapSource: String = MapSource.OSM,
    val tiandituKey: String = "",
    val isGridMode: Boolean = false,
    /** Grid mode: label worked (green) cells with the first callsign worked in
     *  that grid instead of the grid code; non-worked cells get no label. */
    val showFirstCallLabels: Boolean = false,
    val workedGrids: Set<String> = emptySet(),
    val workedGridQsos: Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>> = emptyMap(),
    /** 4-char gridsquares the station operated from (LoTW MY_GRIDSQUARE) — blue stripes. */
    val roamedGrids: Set<String> = emptySet(),
    /** Stations the user marked in unworked gridsquares (grid -> ordered list;
     *  first = pinned/top) — red fill. */
    val markedGrids: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>> = emptyMap(),
    val stationPosition: GeoPos? = null,
    val orbitalPass: OrbitalPass,
    val track: List<List<GeoPos>>? = null,
    val footprint: OrbitalPos? = null,
    val positions: Map<OrbitalObject, GeoPos>? = null,
    val sunLatDeg: Double = 0.0,
    val sunLonDeg: Double = 0.0,
    val moonLatDeg: Double = 0.0,
    val moonLonDeg: Double = 0.0,
    /** Satellite-mode time scrub: milliseconds ahead of "now" (0 = live tracking). */
    val timeOffsetMs: Long = 0L,
    /** The instant the map is currently rendering (now + [timeOffsetMs]); the time readout
     *  above the slider shows this, so it stays in step with the markers. */
    val mapTimeMs: Long = 0L,
    /** True while the time slider is under the finger; the prediction loop stands down then. */
    val isScrubbing: Boolean = false,
    /**
     * Incremented on every scrub-driven state update. The map follows the satellite for each new
     * value, which is what keeps a *tap* on the slider tracking too: a tap starts and finishes
     * inside one frame, so [isScrubbing] is never observed true for it.
     */
    val scrubSerial: Long = 0L
)

sealed interface MapAction {
    data object SelectPrev : MapAction
    data object SelectNext : MapAction
    data class SelectItem(val item: OrbitalObject) : MapAction
    data class SelectDefaultItem(val catnum: Int) : MapAction
    data class ToggleGridMode(val value: Boolean) : MapAction
    data class ToggleFirstCallLabels(val value: Boolean) : MapAction
    data class SetMarkedStation(
        val grid: String,
        val call: String,
        val stationIds: Set<String> = emptySet()
    ) : MapAction
    data class RemoveMarkedStation(
        val grid: String,
        val call: String,
        val stationIds: Set<String> = emptySet()
    ) : MapAction
    data class PinMarkedStation(
        val grid: String,
        val call: String,
        val stationIds: Set<String> = emptySet()
    ) : MapAction
    data class SetVisible(val isVisible: Boolean) : MapAction
    /** Time scrub: [offsetMs] ahead of now; the ViewModel clamps it at 0. */
    data class SetTimeOffset(val offsetMs: Long) : MapAction
    /** Fixed absolute time on the radar pass map, clamped to AOS–LOS. */
    data class SetPassTime(val timeMs: Long) : MapAction
    /** Back to live tracking (the "Now" button). */
    data object ResetTimeOffset : MapAction
    /** Finger down/up on the time slider. */
    data class SetScrubbing(val isScrubbing: Boolean) : MapAction
}

data class MapData(
    val catNum: Int,
    val name: String,
    val aosTime: String,
    val isTimeAos: Boolean,
    val azimuth: Double,
    val elevation: Double,
    val range: Double,
    val altitude: Double,
    val velocity: Double,
    val qthLoc: String,
    val osmPos: GeoPos,
    val period: Double,
    val phase: Double,
    val eclipsed: Boolean
)

/** Reset a dedicated pass timeline to live time during the pass, or to AOS otherwise. */
internal fun radarPassResetTime(nowMs: Long, aosTime: Long, losTime: Long): Long {
    require(losTime > aosTime) { "LOS must be later than AOS" }
    return if (nowMs in aosTime..losTime) nowMs else aosTime
}
