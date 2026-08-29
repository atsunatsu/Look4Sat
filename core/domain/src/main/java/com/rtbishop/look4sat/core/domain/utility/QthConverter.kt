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
 * Convert a Maidenhead locator (4, 6 or 8 chars) to the position of its
 * square / subsquare / extended-square centre. Longer input is truncated to
 * 8 chars (the Maidenhead maximum); malformed or out-of-range input yields
 * null.
 */
fun qthToPosition(locator: String): GeoPos? {
    val trimmedQth = locator.trim().uppercase().take(8)
    if (trimmedQth.length !in listOf(4, 6, 8) || !isValidLocator(trimmedQth)) return null
    val lonFirst = (trimmedQth[0].code - 65) * 20
    val latFirst = (trimmedQth[1].code - 65) * 10
    val lonSecond = trimmedQth[2].toString().toInt() * 2
    val latSecond = trimmedQth[3].toString().toInt()
    // Start from the 4-char square centre, refine for 6/8-char subsquares.
    var longitude = lonFirst + lonSecond + 1.0
    var latitude = latFirst + latSecond + 0.5
    if (trimmedQth.length >= 6) {
        val lonSub = (trimmedQth[4].code - 65) * 5.0 / 60.0
        val latSub = (trimmedQth[5].code - 65) * 2.5 / 60.0
        if (lonSub < 0.0 || lonSub > 115.0 / 60.0 || latSub < 0.0 || latSub > 57.5 / 60.0) return null
        longitude = lonFirst + lonSecond + lonSub + 2.5 / 60.0
        latitude = latFirst + latSecond + latSub + 1.25 / 60.0
        if (trimmedQth.length >= 8) {
            val lonExt = (trimmedQth[6].code - 48) * 30.0 / 3600.0
            val latExt = (trimmedQth[7].code - 48) * 15.0 / 3600.0
            if (lonExt < 0.0 || lonExt > 270.0 / 3600.0 || latExt < 0.0 || latExt > 135.0 / 3600.0) return null
            longitude += lonExt + 15.0 / 3600.0
            latitude += latExt + 7.5 / 3600.0
        }
    }
    return GeoPos((latitude - 90.0).round(4), (longitude - 180.0).round(4))
}

/**
 * Convert a position to its 6-char Maidenhead locator, upper-cased (field,
 * square and subsquare letters). Returns null for out-of-range positions.
 */
fun positionToQth(latitude: Double, longitude: Double): String? {
    if (!isValidPosition(latitude, longitude)) return null
    val newLongitude = if (longitude > 180.0) longitude else longitude + 180
    val newLatitude = latitude + 90
    val lonFirst = (65 + (newLongitude / 20)).toInt().toChar()
    val latFirst = (65 + (newLatitude / 10)).toInt().toChar()
    val lonSecond = ((newLongitude / 2) % 10).toInt()
    val latSecond = (newLatitude % 10).toInt()
    val lonThird = (65 + (newLongitude % 2) * 12).toInt().toChar().uppercaseChar()
    val latThird = (65 + (newLatitude % 1) * 24).toInt().toChar().uppercaseChar()
    return "$lonFirst$latFirst$lonSecond$latSecond$lonThird$latThird"
}

private fun isValidPosition(lat: Double, lon: Double): Boolean {
    return (lat >= -90.0 && lat <= 90.0) && (lon >= -180.0 && lon <= 360.0)
}

private fun isValidLocator(locator: String): Boolean {
    // 4 chars: [A-X][A-X]\d\d ; 6 chars: + [A-X][A-X] ; 8 chars: + \d\d
    return when (locator.length) {
        4 -> Regex("[A-X]{2}\\d{2}").matches(locator)
        6 -> Regex("[A-X]{2}\\d{2}[A-X]{2}").matches(locator)
        8 -> Regex("[A-X]{2}\\d{2}[A-X]{2}\\d{2}").matches(locator)
        else -> false
    }
}
