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

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

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

private const val MILLIS_PER_UTC_DAY = 24L * 60L * 60L * 1000L
private val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("UTC")

/** Parse an ISO date as an integer UTC epoch day. Invalid dates return null. */
internal fun parseGridMasterUtcDate(date: String?): Long? {
    val value = date ?: return null
    if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
    val year = value.substring(0, 4).toIntOrNull() ?: return null
    val month = value.substring(5, 7).toIntOrNull() ?: return null
    val day = value.substring(8, 10).toIntOrNull() ?: return null
    if (year < 1 || month !in 1..12 || day !in 1..31) return null

    return try {
        GregorianCalendar(UTC_TIME_ZONE, Locale.US).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / MILLIS_PER_UTC_DAY
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Maps a GridMaster last-active ISO date to the user's eight fixed age bands.
 * [todayUtcDay] is the current UTC epoch day so every grid changes band together.
 * Invalid and future dates are omitted rather than shown as fresh activity.
 */
internal fun recentActivityTimeBand(date: String?, todayUtcDay: Long): Int? {
    val lastActiveDay = parseGridMasterUtcDate(date) ?: return null
    if (lastActiveDay > todayUtcDay) return null

    val daysAgo = todayUtcDay - lastActiveDay
    return when {
        daysAgo <= 7L -> 0
        daysAgo <= 30L -> 1
        daysAgo <= 90L -> 2
        daysAgo <= 180L -> 3
        daysAgo <= 365L -> 4
        lastActiveDay >= utcDayYearsBefore(todayUtcDay, 3) -> 5
        lastActiveDay >= utcDayYearsBefore(todayUtcDay, 10) -> 6
        else -> 7
    }
}

private fun utcDayYearsBefore(todayUtcDay: Long, years: Int): Long =
    GregorianCalendar(UTC_TIME_ZONE, Locale.US).run {
        timeInMillis = todayUtcDay * MILLIS_PER_UTC_DAY
        add(Calendar.YEAR, -years)
        timeInMillis / MILLIS_PER_UTC_DAY
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
