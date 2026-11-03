/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.map

import java.util.Locale

/**
 * Offline WAIPA province lookup using the SAME 38 provincial polygons as the
 * map overlay. A QSO's Maidenhead locator represents its operating position,
 * unlike an Indonesian home callsign prefix.
 *
 * 6/8-char locators identify a small cell: sample its center and neighbouring
 * points, refusing province crossings. For legacy 4-char-only cached QSOs,
 * credit a province only when the square overlaps land in exactly ONE mapped
 * province, including coastal/island squares that contain mostly sea. This
 * prevents an old home callsign allocation from overriding known geography.
 *
 * Never use the touch hit-test's enlarged targets / archipelago envelopes for
 * geolocation: they are UI conveniences, not administrative boundaries.
 */
internal class WaipaGridResolver(regions: List<AwardRegion>) : (String) -> String? {
    private data class BoundedRegion(
        val region: AwardRegion,
        val minLon: Double,
        val maxLon: Double,
        val minLat: Double,
        val maxLat: Double
    )

    private data class GridCell(
        val lon: Double,
        val lat: Double,
        val width: Double,
        val height: Double,
        val precise: Boolean
    )

    private val bounded = regions.mapNotNull { region ->
        val points = region.rings.flatten()
        if (points.isEmpty()) null else BoundedRegion(
            region,
            points.minOf { it[0] }, points.maxOf { it[0] },
            points.minOf { it[1] }, points.maxOf { it[1] }
        )
    }
    private val cache = mutableMapOf<String, String?>()
    private val candidateCache = mutableMapOf<String, Set<String>>()

    override fun invoke(grid: String): String? {
        val normalized = grid.trim().uppercase(Locale.US)
        if (cache.containsKey(normalized)) return cache[normalized]
        val matched = decode(normalized)?.let(::province)
        cache[normalized] = matched
        return matched
    }

    /**
     * Every province whose actual land polygon has a positive-area intersection
     * with this locator cell. Unlike [invoke], this deliberately returns several
     * candidates when a 6/8-character cell crosses a provincial border.
     */
    fun candidateProvinces(grid: String): Set<String> {
        val normalized = grid.trim().uppercase(Locale.US)
        if (candidateCache.containsKey(normalized)) return candidateCache.getValue(normalized)
        val candidates = decode(normalized)?.let { cell ->
            bounded.asSequence()
                .filter {
                    it.maxLon > cell.lon && it.minLon < cell.lon + cell.width &&
                        it.maxLat > cell.lat && it.minLat < cell.lat + cell.height
                }
                .filter { boundedRegion ->
                    boundedRegion.region.rings.any { ring -> polygonOverlapsCell(ring, cell) }
                }
                .map { it.region.code }
                .toSet()
        }.orEmpty()
        candidateCache[normalized] = candidates
        return candidates
    }

    private fun decode(grid: String): GridCell? {
        if (grid.length !in setOf(4, 6, 8)) return null
        if (grid[0] !in 'A'..'R' || grid[1] !in 'A'..'R' ||
            grid[2] !in '0'..'9' || grid[3] !in '0'..'9'
        ) return null
        var lon = -180.0 + (grid[0] - 'A') * 20 + (grid[2] - '0') * 2
        var lat = -90.0 + (grid[1] - 'A') * 10 + (grid[3] - '0')
        if (grid.length == 4) return GridCell(lon, lat, 2.0, 1.0, precise = false)
        if (grid[4] !in 'A'..'X' || grid[5] !in 'A'..'X') return null
        lon += (grid[4] - 'A') / 12.0
        lat += (grid[5] - 'A') / 24.0
        if (grid.length == 6) return GridCell(lon, lat, 1.0 / 12.0, 1.0 / 24.0, precise = true)
        if (grid[6] !in '0'..'9' || grid[7] !in '0'..'9') return null
        lon += (grid[6] - '0') / 120.0
        lat += (grid[7] - '0') / 240.0
        return GridCell(lon, lat, 1.0 / 120.0, 1.0 / 240.0, precise = true)
    }

    private fun province(cell: GridCell): String? {
        if (!cell.precise) {
            // A 4-character square may cover mainly sea, e.g. PI59 contains
            // Sorong/Raja Ampat (Southwest Papua, PD). Nine-point sampling
            // misses the land and incorrectly falls back to YE9Rxx -> PB.
            // Instead, intersect the whole square with every province's
            // actual polygon. Credit only when ONE province has nonzero land
            // area inside it. OJ82 (KI + KU) remains ambiguous.
            val provinces = bounded.asSequence()
                .filter { it.maxLon > cell.lon && it.minLon < cell.lon + cell.width &&
                    it.maxLat > cell.lat && it.minLat < cell.lat + cell.height }
                .filter { boundedRegion ->
                    boundedRegion.region.rings.any { ring -> polygonOverlapsCell(ring, cell) }
                }
                .map { it.region.code }.distinct().take(2).toList()
            return provinces.singleOrNull()
        }

        val fractions = listOf(0.1, 0.5, 0.9)
        val hits = fractions.flatMap { yy ->
            fractions.map { xx ->
                val lon = cell.lon + cell.width * xx
                val lat = cell.lat + cell.height * yy
                bounded.asSequence()
                    .filter { lon in it.minLon..it.maxLon && lat in it.minLat..it.maxLat }
                    .filter { AwardHitTest.contains(it.region, lat, lon) }
                    .map { it.region.code }.toSet()
            }
        }
        val possible = hits.flatten().toSet()
        if (possible.size != 1) return null
        val code = possible.single()
        // Center-in-land is best. On island shores, at least 3 of 9
        // samples inside the same region allow a coastal-grid credit.
        if (code !in hits[4] && hits.count { code in it } < 3) return null
        return code
    }

    /** Clip against the grid rectangle. Only positive-area intersections
     * count, so merely touching a border does not identify a province. */
    private fun polygonOverlapsCell(ring: List<DoubleArray>, cell: GridCell): Boolean {
        if (ring.size < 3) return false
        var clipped = ring
        for (edge in 0..3) {
            if (clipped.size < 3) return false
            val dimension = if (edge < 2) 0 else 1
            val value = when (edge) {
                0 -> cell.lon
                1 -> cell.lon + cell.width
                2 -> cell.lat
                else -> cell.lat + cell.height
            }
            fun inside(point: DoubleArray): Boolean = when (edge) {
                0, 2 -> point[dimension] >= value
                else -> point[dimension] <= value
            }
            val output = mutableListOf<DoubleArray>()
            var previous = clipped.last()
            var wasInside = inside(previous)
            for (current in clipped) {
                val isInside = inside(current)
                if (wasInside != isInside) {
                    val t = (value - previous[dimension]) /
                        (current[dimension] - previous[dimension])
                    output.add(doubleArrayOf(
                        previous[0] + t * (current[0] - previous[0]),
                        previous[1] + t * (current[1] - previous[1])
                    ))
                }
                if (isInside) output.add(current)
                previous = current
                wasInside = isInside
            }
            clipped = output
        }
        if (clipped.size < 3) return false
        val x0 = clipped[0][0]
        val y0 = clipped[0][1]
        var twiceArea = 0.0
        // Triangle fan relative to the first vertex avoids cancellation when
        // computing the area of a tiny coastal fragment.
        for (i in 1 until clipped.lastIndex) {
            val a = clipped[i]
            val b = clipped[i + 1]
            twiceArea += (a[0] - x0) * (b[1] - y0) -
                (b[0] - x0) * (a[1] - y0)
        }
        return kotlin.math.abs(twiceArea) > 1e-10
    }

}
