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
 *
 * Contains code ported from OrbitDeckiOS (https://github.com/prstoetzer/OrbitDeckiOS),
 * Copyright (c) 2025 Paul Stoetzer, N8HM, licensed under the MIT License.
 * See THIRD_PARTY_NOTICES.md for the full MIT license text.
 */
package com.rtbishop.look4sat.core.domain.utility

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round

/**
 * Grid-line / grid-corner geometry for the 4-character Maidenhead squares that
 * VUCC credits, used by the Grid Finder field tool.
 *
 * A 4-character square is 1 degree tall (boundaries at integer latitudes) and
 * 2 degrees wide (boundaries at even offsets from the -180 degree antimeridian),
 * so a station's distance to the nearest VUCC line and corner is a matter of
 * simple planar geometry at the station's latitude.
 *
 * Geometry ported from OrbitDeck for iOS (MIT License, (c) 2026 Paul Stoetzer,
 * N8HM) - `GridGeometry` in `Views/HomeView.swift` and `vuccGrids` in
 * `Engine/FeatureEngine.swift`. Golden values are pinned by GridGeometryTest.
 */

/** ARRL VUCC "20 ft rule": a fix within this distance of a boundary counts as on it. */
const val VUCC_BOUNDARY_TOLERANCE_METERS = 20.0 * 0.3048

/** One degree of latitude in metres; 4-character squares are 1 degree tall. */
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

/** Nudge used to name the square on the far side of a boundary (display only). */
private const val NEIGHBOUR_NUDGE_DEGREES = 0.0005

/** Nudge used by [vuccClaimableGrids] so the neighbour lands squarely across. */
private const val CLAIM_NEIGHBOUR_NUDGE_DEGREES = 0.001

private const val DEG_TO_RAD = 0.017453292519943295
private const val RAD_TO_DEG = 57.29577951308232

/** Where the nearest 4-character boundary lies relative to the fix. */
enum class GridBoundaryDirection { NORTH, SOUTH, EAST, WEST }

/** ARRL VUCC standing of a fix, derived from how many squares it may claim. */
enum class GridFixStatus { INSIDE_GRID, ON_GRID_LINE, ON_GRID_CORNER }

/**
 * Distance and bearing to the nearest 4-character grid corner and grid line,
 * plus the locators of the squares that meet there. All distances are metres
 * computed on a flat approximation local to the fix (the same approximation
 * the source implementation uses; over the <=100 km distances involved the
 * error is far below a GPS fix's own accuracy).
 */
data class GridGeometry(
    val grid4: String,
    val grid6: String,
    val grid8: String,
    val latLineMeters: Double,
    val latLineDirection: GridBoundaryDirection,
    val lonLineMeters: Double,
    val lonLineDirection: GridBoundaryDirection,
    val cornerMeters: Double,
    val cornerBearingDegrees: Double,
    val cornerGrids: List<String>,
    val nearestLineMeters: Double,
    val nearestLineBearingDegrees: Double,
    val nearestLineIsLatitude: Boolean,
    val nearestLineGrids: List<String>
)

/**
 * The geometry of the VUCC (4-character) grid around a position, or null when
 * the position is out of range.
 */
fun gridGeometry(latitude: Double, longitude: Double): GridGeometry? {
    val grid4 = positionToGrid4(latitude, longitude) ?: return null
    val grid6 = positionToQth(latitude, longitude) ?: return null
    val grid8 = positionToGrid8(latitude, longitude) ?: return null

    // 4-character boundaries: integer latitudes, even-degree longitudes.
    val latBoundary = round(latitude)
    val lonBoundary = round((longitude + 180.0) / 2.0) * 2.0 - 180.0

    val metersPerDegreeLon = METERS_PER_DEGREE_LATITUDE * cos(latitude * DEG_TO_RAD)
    val dNorth = (latBoundary - latitude) * METERS_PER_DEGREE_LATITUDE
    val dEast = (lonBoundary - longitude) * metersPerDegreeLon

    val otherLat = latBoundary - if (latitude >= latBoundary) NEIGHBOUR_NUDGE_DEGREES else -NEIGHBOUR_NUDGE_DEGREES
    val otherLon = lonBoundary - if (longitude >= lonBoundary) NEIGHBOUR_NUDGE_DEGREES else -NEIGHBOUR_NUDGE_DEGREES

    val cornerGrids = setOfNotNull(
        positionToGrid4(latitude, longitude),
        positionToGrid4(otherLat, longitude),
        positionToGrid4(latitude, otherLon),
        positionToGrid4(otherLat, otherLon)
    ).sorted()

    val onLatitude = abs(dNorth) <= abs(dEast)
    val nearestLineGrids = if (onLatitude) {
        setOfNotNull(grid4, positionToGrid4(otherLat, longitude)).sorted()
    } else {
        setOfNotNull(grid4, positionToGrid4(latitude, otherLon)).sorted()
    }

    return GridGeometry(
        grid4 = grid4,
        grid6 = grid6,
        grid8 = grid8,
        latLineMeters = abs(dNorth),
        latLineDirection = if (dNorth >= 0) GridBoundaryDirection.NORTH else GridBoundaryDirection.SOUTH,
        lonLineMeters = abs(dEast),
        lonLineDirection = if (dEast >= 0) GridBoundaryDirection.EAST else GridBoundaryDirection.WEST,
        cornerMeters = hypot(dNorth, dEast),
        cornerBearingDegrees = bearingDegrees(dNorth, dEast),
        cornerGrids = cornerGrids,
        nearestLineMeters = if (onLatitude) abs(dNorth) else abs(dEast),
        nearestLineBearingDegrees = if (onLatitude) {
            if (dNorth >= 0) 0.0 else 180.0
        } else {
            if (dEast >= 0) 90.0 else 270.0
        },
        nearestLineIsLatitude = onLatitude,
        nearestLineGrids = nearestLineGrids
    )
}

/**
 * The 4-character squares a station at this position may claim under ARRL VUCC
 * rules: normally one, but two when the fix is on the line between two squares
 * and four when it is on the corner where four squares meet. A fix within
 * [toleranceMeters] of a boundary is considered to be on it (the 20 ft rule).
 */
fun vuccClaimableGrids(
    latitude: Double,
    longitude: Double,
    toleranceMeters: Double = VUCC_BOUNDARY_TOLERANCE_METERS
): List<String> {
    val own = positionToGrid4(latitude, longitude) ?: return emptyList()
    val grids = mutableSetOf(own)

    val latBoundary = round(latitude)
    val lonBoundary = round((longitude + 180.0) / 2.0) * 2.0 - 180.0
    val metersPerDegreeLon = METERS_PER_DEGREE_LATITUDE * cos(latitude * DEG_TO_RAD)

    val onLatitude = abs(latitude - latBoundary) * METERS_PER_DEGREE_LATITUDE <= toleranceMeters
    val onLongitude = abs(longitude - lonBoundary) * metersPerDegreeLon <= toleranceMeters
    if (!onLatitude && !onLongitude) return grids.sorted()

    // Step just past the boundary so the neighbour's locator is unambiguous.
    val otherLat = latBoundary - if (latitude >= latBoundary) CLAIM_NEIGHBOUR_NUDGE_DEGREES else -CLAIM_NEIGHBOUR_NUDGE_DEGREES
    val otherLon = lonBoundary - if (longitude >= lonBoundary) CLAIM_NEIGHBOUR_NUDGE_DEGREES else -CLAIM_NEIGHBOUR_NUDGE_DEGREES

    if (onLatitude) {
        positionToGrid4(otherLat, longitude)?.let(grids::add)
    }
    if (onLongitude) {
        positionToGrid4(latitude, otherLon)?.let(grids::add)
    }
    if (onLatitude && onLongitude) {
        positionToGrid4(otherLat, otherLon)?.let(grids::add)
    }
    return grids.sorted()
}

/** VUCC standing implied by the number of squares a fix may claim. */
fun gridFixStatus(claimableGrids: List<String>): GridFixStatus = when {
    claimableGrids.size >= 4 -> GridFixStatus.ON_GRID_CORNER
    claimableGrids.size >= 2 -> GridFixStatus.ON_GRID_LINE
    else -> GridFixStatus.INSIDE_GRID
}

private fun bearingDegrees(dNorth: Double, dEast: Double): Double {
    val bearing = atan2(dEast, dNorth) * RAD_TO_DEG
    return if (bearing < 0.0) bearing + 360.0 else bearing
}

/**
 * Why an own-station grid set cannot describe a VUCC boundary / four-way intersection.
 *
 * LoTW station-location rules allow exactly one grid, two grids sharing an edge, or four grids
 * sharing a corner — never three, and never a set that merely happens to have the right count.
 */
enum class StationGridSetIssue {
    /** Not 1, 2 or 4 grids. */
    COUNT,

    /** Two grids that do not share an edge, or four that do not share a single corner. */
    NOT_ADJACENT
}

/**
 * Checks the grid set serialized into the TQ8 station record's comma-separated `GRIDSQUARE` field.
 *
 * This is the upload-side gate only: LoTW itself validates the format of these fields (4/6-char
 * locator syntax) but never their geometry, so a single mistyped field letter reaches the server,
 * gets stored verbatim, and only surfaces later as a station location 1700 km from the other
 * grids. Entries are compared as their 4-character squares (VUCC only ever credits those), which
 * also makes a 6-character entry fold onto its square. Unparseable entries are ignored here — the
 * station-field format check runs first and owns those.
 *
 * @return null when the set can be signed, otherwise the first thing wrong with it.
 */
fun stationGridSetIssue(grids: List<String>): StationGridSetIssue? {
    val cells = grids.mapNotNull(::cellOf).distinct()
    return when (cells.size) {
        0, 1 -> null
        2 -> if (shareEdge(cells[0], cells[1])) null else StationGridSetIssue.NOT_ADJACENT
        4 -> if (shareCorner(cells)) null else StationGridSetIssue.NOT_ADJACENT
        else -> StationGridSetIssue.COUNT
    }
}

/** A 4-character square as an index pair: `lon` in 0..179 (wraps at the antimeridian), `lat` in 0..179. */
private data class GridCell(val lon: Int, val lat: Int)

private const val LON_CELLS = 180

/** 4-character square of a locator, or null when the value is not a usable 4/6/8-character grid. */
private fun cellOf(grid: String): GridCell? {
    val g = grid.trim().uppercase(java.util.Locale.US)
    if (g.length < 4) return null
    val fieldLon = g[0] - 'A'
    val fieldLat = g[1] - 'A'
    val squareLon = g[2] - '0'
    val squareLat = g[3] - '0'
    if (fieldLon !in 0..17 || fieldLat !in 0..17 || squareLon !in 0..9 || squareLat !in 0..9) return null
    return GridCell(fieldLon * 10 + squareLon, fieldLat * 10 + squareLat)
}

/** Shortest distance between two longitude indices, so the R/A join is one step, not 179. */
private fun lonSteps(a: Int, b: Int): Int = abs(a - b).let { minOf(it, LON_CELLS - it) }

private fun shareEdge(a: GridCell, b: GridCell): Boolean = lonSteps(a.lon, b.lon) + abs(a.lat - b.lat) == 1

/** True when the four squares meet at one corner: {L,P}, {L+1,P}, {L,P+1}, {L+1,P+1}. */
private fun shareCorner(cells: List<GridCell>): Boolean {
    val set = cells.toSet()
    return cells.any { anchor ->
        val nextLon = (anchor.lon + 1) % LON_CELLS
        set == setOf(
            anchor,
            GridCell(nextLon, anchor.lat),
            GridCell(anchor.lon, anchor.lat + 1),
            GridCell(nextLon, anchor.lat + 1)
        )
    }
}
