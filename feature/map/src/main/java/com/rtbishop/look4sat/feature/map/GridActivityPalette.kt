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

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Fixed seven-band GridMaster-style activity palette.
 *
 * Boundaries are inferred from the visible SAT / LEO Most Wanted maps,
 * not from a published upstream color specification. Both award types use
 * the same percentage-to-color mapping. Changing the range filter only
 * changes which squares are visible; it never stretches the color scale.
 *
 * Returned values are opaque Android ARGB colors. Overlay transparency
 * is applied only when drawing the map, so dialog badges use the same hues.
 */
internal fun gridActivityPaletteColor(percentage: Int): Int = when (percentage.coerceIn(0, 100)) {
    in 0..2 -> 0xFFB41D2A.toInt()   // deep red
    in 3..7 -> 0xFFE64A2D.toInt()   // red-orange
    in 8..12 -> 0xFFF09124.toInt()  // orange
    in 13..17 -> 0xFFF2CE29.toInt() // yellow
    in 18..22 -> 0xFFB5E133.toInt() // yellow-green
    in 23..32 -> 0xFF32E64D.toInt() // bright green
    else -> 0xFF087C30.toInt()      // deep green, from 33%
}


/** Shared fill opacity for GridMaster map heatmaps (110/255 = 43.1%). */
internal const val GRID_ACTIVITY_FILL_ALPHA = 110

/**
 * Maps a GridMaster last-active ISO date to the user's eight fixed age bands.
 * [today] must be the current UTC calendar date so every grid changes band together.
 * Invalid and future dates are omitted rather than shown as fresh activity.
 */
internal fun recentActivityTimeBand(date: String?, today: LocalDate): Int? {
    val lastActive = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    if (lastActive.isAfter(today)) return null

    val daysAgo = ChronoUnit.DAYS.between(lastActive, today)
    return when {
        daysAgo <= 7L -> 0
        daysAgo <= 30L -> 1
        daysAgo <= 90L -> 2
        daysAgo <= 180L -> 3
        daysAgo <= 365L -> 4
        !lastActive.isBefore(today.minusYears(3)) -> 5
        !lastActive.isBefore(today.minusYears(10)) -> 6
        else -> 7
    }
}

/** Opaque palette colors; [GRID_ACTIVITY_FILL_ALPHA] is applied only by the map paint. */
internal fun recentActivityTimePaletteColor(band: Int): Int = when (band.coerceIn(0, 7)) {
    0 -> 0xFFF44336.toInt() // 0–7 days
    1 -> 0xFFFF7043.toInt() // 8–30 days
    2 -> 0xFFFFB74D.toInt() // 31–90 days
    3 -> 0xFFC6DBEF.toInt() // 91–180 days
    4 -> 0xFF9ECAE1.toInt() // 181–365 days
    5 -> 0xFF6BAED6.toInt() // 1–3 years
    6 -> 0xFF3182BD.toInt() // 3–10 years
    else -> 0xFF08519C.toInt() // over 10 years
}
