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
