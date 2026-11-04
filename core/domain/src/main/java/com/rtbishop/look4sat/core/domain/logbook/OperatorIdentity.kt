/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.domain.logbook

import java.util.Locale

/**
 * Own (operator) identity written into the records this app logs.
 *
 * When Wavelog is the active upload destination, its selected station profile must win
 * even if an old LoTW certificate remains installed. Otherwise the CW console and new QSO
 * records may carry the wrong station callsign and fail to match QSOs synced from Wavelog.
 * Outside Wavelog mode, the LoTW certificate wins with Wavelog as a fallback.
 */
fun operatorCallsign(
    certificateCallsign: String,
    wavelogCallsign: String,
    wavelogMode: Boolean = false
): String =
    (if (wavelogMode) wavelogCallsign else certificateCallsign.ifBlank { wavelogCallsign })
        .trim().uppercase(Locale.US)

/**
 * Station grids follow the active upload destination too: Wavelog's selected station
 * profile when ready, otherwise the LoTW location with Wavelog as a fallback. Either side
 * may hold several comma-separated gridsquares.
 */
fun operatorGrids(
    lotwStationGrid: String,
    wavelogStationGrid: String,
    wavelogMode: Boolean = false
): List<String> =
    (if (wavelogMode) wavelogStationGrid else lotwStationGrid.ifBlank { wavelogStationGrid })
        .split(',')
        .map { it.trim().uppercase(Locale.US) }
        .filter(String::isNotEmpty)
