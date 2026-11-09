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
package com.rtbishop.look4sat.core.domain.model

/**
 * Internal, non-geographic bucket for confirmed satellite QSOs without an
 * opposite-station GRIDSQUARE/VUCC_GRIDS. Kept in the QSO store so DXCC and
 * STATE-based awards can use these contacts, but NEVER counted as a VUCC grid.
 */
const val GRIDLESS_QSO_BUCKET = "@GRIDLESS@"

/**
 * One confirmed satellite QSO as reported by LoTW, kept per worked gridsquare
 * so the map can show, for any worked grid, which callsigns were worked there
 * and when.
 *
 * @param call      opposite station callsign (as logged by the opposite op)
 * @param epochMs   QSO date+time in UTC milliseconds
 * @param satName   satellite name (e.g. "FO-29")
 * @param mode      ADIF mode (FM / CW / SSB ...)
 * @param bandUp    uplink band, the band of the transmitted frequency (ADIF BAND:
 *                  "2M", "70CM", "10M"...). Measured against the live report of the
 *                  user's own uploads: SO-50 arrives as BAND=2M/FREQ=145.85 with
 *                  BAND_RX=70CM/FREQ_RX=436.8, i.e. BAND carries the uplink.
 * @param bandDown  downlink band (ADIF BAND_RX) — may be empty when LoTW did not
 *                  include it
 * @param dxcc      ARRL DXCC entity code of the opposite station (from ADIF
 *                  <DXCC>), or null when LoTW omitted it
 * @param country   DXCC entity name (ADIF <COUNTRY>), or null
 * @param cqz       CQ zone of the opposite station (ADIF <CQZ>), or null
 * @param state     Primary administrative subdivision of the opposite station
 *                  (ADIF <STATE>). Interpretation depends on the DXCC entity:
 *                  China -> province code ("GD"), Japan -> prefecture number
 *                  ("34"), USA -> state abbreviation ("CA"). Null when absent.
 */
data class GridQso(
    val call: String,
    val epochMs: Long,
    val satName: String,
    val mode: String,
    val bandUp: String,
    val bandDown: String,
    val dxcc: Int? = null,
    val country: String? = null,
    val cqz: Int? = null,
    val state: String? = null,
    /** 4-char grid the station itself operated from (ADIF MY_GRIDSQUARE).
     *  Kept as the first entry of [myGrids] for backward compatibility with
     *  consumers that predate multi-grid station locations. */
    val myGrid: String? = null,
    /** Every 4-char grid the station operated from for this QSO — ADIF
     *  <MY_GRIDSQUARE> plus every field of <MY_VUCC_GRIDS> (a comma-separated
     *  list LoTW emits when one station location roams across several grid
     *  squares; MY_GRIDSQUARE may be absent on those records). A station
     *  location (台址) can legitimately span multiple grids, and the map's
     *  stripes and the operated-grid selector must show all of them. */
    val myGrids: Set<String> = emptySet(),
    /** Callsign of the station location this QSO was logged under (ADIF
     *  <STATION_CALLSIGN>; e.g. "BH6RJD", "BH6RJD/P"). NOT the 台址 identity
     *  on its own — one callsign can have several station locations. */
    val myCallsign: String? = null,
    /** Stable key of the station location (台址) this QSO was logged under,
     *  derived from the MY_* station fields (STATION_CALLSIGN + MY_DXCC +
     *  MY_STATE + MY_CQ_ZONE + MY_ITU_ZONE + MY_IOTA + MY_COUNTRY), so the
     *  operated-grid selector can group QSOs per 台址 and show each 台址 with
     *  its full grid set. Null for data synced before this field existed. */
    val stationKey: String? = null,
    /** Every 4-char grid of the OPPOSITE station for this QSO — ADIF
     *  <GRIDSQUARE> plus every field of <VUCC_GRIDS> (comma-separated when
     *  the contact spans several squares; up to four in one QSO). The logbook
     *  shows the set in the QSL slot of a confirmed row; [myGrids] is the
     *  same thing for the user's own side. */
    val theirGrids: List<String> = emptyList(),
    /** Opposite-station locators with all available precision (6/8 chars),
     * never truncated for persistence. Four-character VUCC grouping remains
     * in theirGrids and the map keys. */
    val theirFineGrids: List<String> = emptyList(),
    /** IOTA reference of the other station, e.g. OC-177, when reported. */
    val iota: String? = null,
    /** Full-precision MY_GRIDSQUARE / MY_VUCC_GRIDS locators, independent of
     * the 4-char station selection/grid overlay used for VUCC. */
    val myFineGrids: List<String> = emptyList(),
    /** Own station's IOTA reference from MY_IOTA, independent of opponent IOTA. */
    val myIota: String? = null,
    /** LoTW ADIF FREQ (transmit/uplink), expressed in Hz when available. */
    val txFrequencyHz: Long? = null,
    /** LoTW ADIF FREQ_RX (receive/downlink), expressed in Hz when available. */
    val rxFrequencyHz: Long? = null
) {
    /** Short uplink/downlink band label ("U/V", "V/A"), or "" when unknown. */
    val bandLabel: String
        get() {
            val up = bandAbbrev(bandUp)
            val down = bandAbbrev(bandDown)
            return when {
                up.isNotEmpty() && down.isNotEmpty() -> "$up/$down"
                up.isNotEmpty() -> up
                else -> ""
            }
        }

    private fun bandAbbrev(band: String): String = when (band.trim().uppercase()) {
        "70CM" -> "U"
        "2M" -> "V"
        "10M" -> "A"
        "6M" -> "V"
        "1.2G" -> "L"
        "2.4G" -> "S"
        "23CM" -> "L"
        "13CM" -> "S"
        else -> band.trim().uppercase()
    }
}

/**
 * Every MY-side grid of this QSO: [myGrids] (MY_GRIDSQUARE + MY_VUCC_GRIDS)
 * with a single-grid fallback to [myGrid] for records synced before
 * multi-grid support. Empty when the record carries no MY-side grid at all.
 */
fun GridQso.myStationGrids(): Set<String> =
    myGrids.ifEmpty { myGrid?.let { setOf(it) }.orEmpty() }

/**
 * Station-location (台址) grouping key: the QSO's MY grid set, sorted and
 * comma-joined ("OL62", "OM60,PM01"). Records covering the same grid set
 * belong to the same 台址 — they are VUCC-equivalent. Null when the record
 * carries no MY-side grid. Backs both the map's operated-grid selector and
 * its per-台址 grid-detail filtering, so the two scope by one identity.
 */
fun GridQso.stationGridSetKey(): String? =
    myStationGrids().takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")

/**
 * Scopes a per-grid QSO list to the operated-grid selector's choice with
 * COVERING semantics (2026-10-05): [stationId] is a grid-set key such as
 * "OL62" or "OL62,OL63", and a record stays when its own [myStationGrids]
 * COVER that key (record grid set ⊇ key set). So a multi-grid 台址 that
 * covers a single-grid one (OL62/63 covers OL62) contributes its records to
 * the single-grid scope's statistics — map fills, dialog lists and first-call
 * labels all derive from this one rule. The reverse does NOT hold: records
 * under a narrower 台址 never leak into a wider scope (single OL62 records
 * stay out of the OL62/63 scope). Records without a MY-side grid belong to
 * no 台址 and are dropped under any specific scope.
 */
fun List<GridQso>.scopedToStation(stationId: String?): List<GridQso> =
    if (stationId == null) this
    else {
        val scope = stationId.split(',').toSet()
        filter { it.myStationGrids().containsAll(scope) }
    }

/**
 * Scope to any selected operated-station grid-set. This is a UNION of the
 * existing covering scopes, not an exact-match comparison: selecting OL62
 * still includes QSO records from an OL62/OL63 location. A QSO matching two
 * selections is returned once. An empty selection represents All.
 */
fun List<GridQso>.scopedToStations(stationIds: Set<String>): List<GridQso> {
    if (stationIds.isEmpty()) return this
    val scopes = stationIds.map { it.split(',').toSet() }
    return filter { qso ->
        val grids = qso.myStationGrids()
        scopes.any { grids.containsAll(it) }
    }
}

/** 4-char grids worked within the selected operated-station scope (empty keys excluded). */
fun Map<String, List<GridQso>>.workedGridsForStations(stationIds: Set<String>): Set<String> =
    entries.asSequence()
        .filter { (grid, qsos) ->
            grid != GRIDLESS_QSO_BUCKET && qsos.scopedToStations(stationIds).isNotEmpty()
        }
        .map { it.key }
        .toSet()

/** Earliest QSO per grid within a multi-station selection (empty means All). */
fun Map<String, List<GridQso>>.firstCallsByGrid(stationIds: Set<String>): Map<String, String> =
    mapNotNull { (grid, qsos) ->
        if (grid == GRIDLESS_QSO_BUCKET) null
        else qsos.scopedToStations(stationIds).minByOrNull { it.epochMs }?.let { grid to it.call }
    }.toMap()

/**
 * For every grid in the store, the callsign of the earliest QSO — scoped to the
 * operated-grid selector's choice first, so a specific 台址 labels each worked
 * cell with ITS first contact; null ("All") keeps the global first call.
 */
fun Map<String, List<GridQso>>.firstCallsByGrid(stationId: String?): Map<String, String> =
    mapNotNull { (grid, qsos) ->
        if (grid == GRIDLESS_QSO_BUCKET) null
        else qsos.scopedToStation(stationId).minByOrNull { it.epochMs }?.let { grid to it.call }
    }.toMap()
