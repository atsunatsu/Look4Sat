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

import com.rtbishop.look4sat.core.domain.model.GridQso

/**
 * Reconstruct the complete Maidenhead set from map-QSO fine locators plus
 * 4-grid fallback. A 4-char VUCC index alone is not a source-data substitute.
 */
private fun completeGrids(coarse: Collection<String>, fine: List<String>): List<String> =
    (fine + coarse.filter { grid -> fine.none { it.take(4) == grid.take(4) } }).distinct()

/** Bridge a LoTW confirmed QSO into the logbook without losing any full
 *  opposite/own locators or the IOTA reference. */

fun GridQso.toConfirmedRecord(accountCallsign: String): QsoRecord = QsoRecord(
    startUtcMillis = epochMs,
    theirCallsign = call,
    myCallsign = accountCallsign.trim().uppercase(),
    theirGrid = completeGrids(theirGrids, theirFineGrids).firstOrNull().orEmpty(),
    theirVuccGrids = theirGrids,
    myGrid = completeGrids(myGrids.ifEmpty { listOfNotNull(myGrid).toSet() }, myFineGrids)
        .firstOrNull().orEmpty(),
    vuccGrids = myGrids.toList(),
    band = bandUp,
    rxBand = bandDown,
    txFrequencyHz = txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz,
    mode = mode,
    // On satellites, MODE=MFSK means FT4 (ADIF pairing); keep the sub-mode so
    // confirmations match local FT4 records (mode=MFSK, submode=FT4).
    submode = if (mode.equals("MFSK", true)) "FT4" else "",
    satelliteName = satName,
    propagationMode = "SAT",
    status = QsoStatus.COMPLETE,
    lotwConfirmed = true,
    dxcc = dxcc,
    country = country.orEmpty(),
    cqZone = cqz,
    region = state.orEmpty(),
    iota = iota.orEmpty(),
    myIota = myIota.orEmpty(),
    theirFullGrids = completeGrids(theirGrids, theirFineGrids),
    myFullGrids = completeGrids(myGrids.ifEmpty { listOfNotNull(myGrid).toSet() }, myFineGrids)
)
