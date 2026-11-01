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
    // The north pole has no valid first-row Maidenhead square. Wrap 180°E
    // (and legacy 0..360° inputs) rather than emitting fields beyond R.
    if (!isValidPosition(latitude, longitude) || latitude >= 90.0) return null
    val newLongitude = normalizeLongitude(longitude) + 180.0
    val newLatitude = latitude + 90
    val lonFirst = (65 + (newLongitude / 20)).toInt().toChar()
    val latFirst = (65 + (newLatitude / 10)).toInt().toChar()
    val lonSecond = ((newLongitude / 2) % 10).toInt()
    val latSecond = (newLatitude % 10).toInt()
    val lonThird = (65 + (newLongitude % 2) * 12).toInt().toChar().uppercaseChar()
    val latThird = (65 + (newLatitude % 1) * 24).toInt().toChar().uppercaseChar()
    return "$lonFirst$latFirst$lonSecond$latSecond$lonThird$latThird"
}

/**
 * Convert a position to its 4-char Maidenhead locator (field + square), the
 * square VUCC credits, upper-cased. Returns null outside the Maidenhead grid
 * (lat >= 90 is out of range: the top row is 80..90 N). The longitude is
 * wrapped into [-180, 180) first, so fixes either side of the antimeridian
 * resolve to the square they are actually in.
 */
fun positionToGrid4(latitude: Double, longitude: Double): String? {
    if (latitude < -90.0 || latitude >= 90.0) return null
    val normalizedLon = normalizeLongitude(longitude)
    val fieldLat = ((latitude + 90.0) / 10.0).toInt().coerceIn(0, 17)
    val fieldLon = ((normalizedLon + 180.0) / 20.0).toInt().coerceIn(0, 17)
    val subLat = ((latitude + 90.0) % 10.0).toInt()
    val subLon = ((normalizedLon + 180.0) % 20.0 / 2.0).toInt()
    return "${'A' + fieldLon}${'A' + fieldLat}$subLon$subLat"
}

/**
 * Convert a position to its 8-char Maidenhead locator: the 6-char locator plus
 * the numeric extended-square pair (the Maidenhead maximum). Returns null for
 * out-of-range positions.
 */
fun positionToGrid8(latitude: Double, longitude: Double): String? {
    val normalizedLon = normalizeLongitude(longitude)
    val base = positionToQth(latitude, normalizedLon) ?: return null
    val latitudeOffset = latitude.coerceIn(-90.0, 89.999999) + 90.0
    val longitudeOffset = normalizedLon.coerceIn(-180.0, 179.999999) + 180.0
    val subLon = 2.0 / 24.0
    val subLat = 1.0 / 24.0
    val lonDigit = minOf(9, ((longitudeOffset % subLon) / subLon * 10).toInt())
    val latDigit = minOf(9, ((latitudeOffset % subLat) / subLat * 10).toInt())
    return "$base$lonDigit$latDigit"
}

/** Wrap any longitude into [-180, 180). */
private fun normalizeLongitude(longitude: Double): Double {
    return ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

private fun isValidPosition(lat: Double, lon: Double): Boolean {
    return (lat >= -90.0 && lat <= 90.0) && (lon >= -180.0 && lon <= 360.0)
}

private fun isValidLocator(locator: String): Boolean {
    // 4 chars: [A-R][A-R]\\d\\d ; 6 chars: + [A-X][A-X] ; 8 chars: + \\d\\d
    return when (locator.length) {
        4 -> Regex("[A-R]{2}\\d{2}").matches(locator)
        6 -> Regex("[A-R]{2}\\d{2}[A-X]{2}").matches(locator)
        8 -> Regex("[A-R]{2}\\d{2}[A-X]{2}\\d{2}").matches(locator)
        else -> false
    }
}
