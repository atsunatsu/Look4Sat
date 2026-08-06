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
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.predict.GeoPos

/**
 * Converts a Maidenhead locator (QTH grid square) to a GeoPos.
 * Supports 6-char (3 pair), 8-char (4 pair) and 10-char (5 pair) locators.
 * The returned position is the center of the finest cell encoded by the locator:
 *  - 6 char: 5' lon x 2.5' lat cell center
 *  - 8 char: 30" lon x 15" lat cell center
 *  - 10 char: 1.25" lon x 0.625" lat cell center
 */
fun qthToPosition(locator: String): GeoPos? {
    val trimmedQth = locator.trim().uppercase()
    if (!isValidLocator(trimmedQth)) return null
    val lonFirst = (trimmedQth[0].code - 65) * 20
    val latFirst = (trimmedQth[1].code - 65) * 10
    val lonSecond = trimmedQth[2].toString().toInt() * 2
    val latSecond = trimmedQth[3].toString().toInt()
    val lonThird = (trimmedQth[4].lowercaseChar().code - 97) / 12.0
    val latThird = (trimmedQth[5].lowercaseChar().code - 97) / 24.0
    var longitude = lonFirst + lonSecond + lonThird - 180
    var latitude = latFirst + latSecond + latThird - 90
    // 8-char extension: 4th pair, digits, 30" lon x 15" lat cells
    if (trimmedQth.length >= 8) {
        longitude += trimmedQth[6].toString().toInt() / 120.0
        latitude += trimmedQth[7].toString().toInt() / 240.0
    }
    // 10-char extension: 5th pair, letters, 1.25" lon x 0.625" lat cells
    if (trimmedQth.length >= 10) {
        longitude += (trimmedQth[8].lowercaseChar().code - 97) / 2880.0
        latitude += (trimmedQth[9].lowercaseChar().code - 97) / 5760.0
    }
    // Offset to the center of the finest encoded cell
    when (trimmedQth.length) {
        8 -> {
            longitude += 1.0 / 240.0
            latitude += 1.0 / 480.0
        }
        10 -> {
            longitude += 1.0 / 5760.0
            latitude += 1.0 / 11520.0
        }
        else -> {
            longitude += 1.0 / 24.0
            latitude += 1.0 / 48.0
        }
    }
    return GeoPos(latitude.round(6), longitude.round(6))
}

/**
 * Converts a GeoPos to a Maidenhead locator (QTH grid square).
 * Default precision is 8 characters (4 pairs) giving 30" lon x 15" lat resolution,
 * matching common 8-char grid square tools. Pass precision = 6 for the classic
 * 5' x 2.5' resolution, or precision = 10 for the finest 1.25" x 0.625" resolution.
 */
fun positionToQth(latitude: Double, longitude: Double, precision: Int = 8): String? {
    if (!isValidPosition(latitude, longitude)) return null
    val newLongitude = longitude + 180
    val newLatitude = latitude + 90
    val lonFirst = (65 + (newLongitude / 20).toInt().coerceIn(0, 17)).toChar()
    val latFirst = (65 + (newLatitude / 10).toInt().coerceIn(0, 17)).toChar()
    val lonSecond = ((newLongitude % 20) / 2).toInt()
    val latSecond = (newLatitude % 10).toInt()
    val lonThird = (65 + (newLongitude % 2) * 12).toInt().toChar().lowercaseChar()
    val latThird = (65 + (newLatitude % 1) * 24).toInt().toChar().lowercaseChar()
    val qth = "$lonFirst$latFirst$lonSecond$latSecond$lonThird$latThird"
    if (precision < 8) return qth
    val lonFourth = ((newLongitude % (1.0 / 12.0)) * 120).toInt()
    val latFourth = ((newLatitude % (1.0 / 24.0)) * 240).toInt()
    val qth8 = "$qth$lonFourth$latFourth"
    if (precision < 10) return qth8
    val lonFifth = (65 + (newLongitude % (1.0 / 120.0)) * 2880).toInt().toChar().lowercaseChar()
    val latFifth = (65 + (newLatitude % (1.0 / 240.0)) * 5760).toInt().toChar().lowercaseChar()
    return "$qth8$lonFifth$latFifth"
}

private fun isValidPosition(lat: Double, lon: Double): Boolean {
    return (lat >= -90.0 && lat <= 90.0) && (lon >= -180.0 && lon <= 360.0)
}

private fun isValidLocator(locator: String): Boolean {
    return locator.matches("[a-xA-X]{2}\\d{2}[a-xA-X]{2}(?:\\d{2}(?:[a-xA-X]{2})?)?".toRegex())
}

/**
 * Returns the 4-char field+square part of a locator, e.g. "OL42ih45" -> "OL42".
 * A 4-char input is returned as-is when valid.
 */
fun qthToSquare(locator: String): String {
    val upper = locator.trim().uppercase()
    return when {
        upper.length >= 4 && isValidLocator(upper) -> upper.take(4)
        upper.length == 4 && upper.matches("[a-xA-X]{2}\\d{2}".toRegex()) -> upper
        else -> "----"
    }
}

/**
 * Builds the 3x3 grid of 4-char squares surrounding [square] (e.g. "OL42").
 * Row 0 = north (lat +1), col 0 = west (lon -1). Handles field/square carry
 * at boundaries (e.g. "AA00" wraps to "RR99" at the south-west corner).
 * Mirrors the neighbor logic decompiled from the QTH定位器 app.
 */
fun qthNeighbors(square: String): List<String> {
    if (square.length != 4) return emptyList()
    val lonField = (square[0].uppercaseChar().code - 65).coerceIn(0, 17)
    val latField = (square[1].uppercaseChar().code - 65).coerceIn(0, 17)
    val lonSquare = square[2].digitToCharOrNull() ?: return emptyList()
    val latSquare = square[3].digitToCharOrNull() ?: return emptyList()
    val result = mutableListOf<String>()
    for (dLat in 1 downTo -1) {          // north -> south
        for (dLon in -1..1) {            // west -> east
            var lf = lonField
            var tf = latField
            var ls = lonSquare + dLon
            var ts = latSquare + dLat
            if (ls < 0) { lf -= 1; ls = 9 } else if (ls > 9) { lf += 1; ls = 0 }
            if (ts < 0) { tf -= 1; ts = 9 } else if (ts > 9) { tf += 1; ts = 0 }
            lf = (lf + 18) % 18
            tf = (tf + 18) % 18
            result += "${('A' + lf).toChar()}${('A' + tf).toChar()}$ls$ts"
        }
    }
    return result
}

private fun Char.digitToCharOrNull(): Int? = digitToIntOrNull()
