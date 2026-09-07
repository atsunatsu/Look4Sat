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
 * One confirmed satellite QSO as reported by LoTW, kept per worked gridsquare
 * so the map can show, for any worked grid, which callsigns were worked there
 * and when.
 *
 * @param call      opposite station callsign (as logged by the opposite op)
 * @param epochMs   QSO date+time in UTC milliseconds
 * @param satName   satellite name (e.g. "FO-29")
 * @param mode      ADIF mode (FM / CW / SSB ...)
 * @param bandUp    uplink band as reported by LoTW (BAND_RX: "70CM", "2M", "10M"...)
 * @param bandDown  downlink band (BAND: "2M", "70CM"...) — may be empty when
 *                  LoTW did not include it
 */
data class GridQso(
    val call: String,
    val epochMs: Long,
    val satName: String,
    val mode: String,
    val bandUp: String,
    val bandDown: String
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
