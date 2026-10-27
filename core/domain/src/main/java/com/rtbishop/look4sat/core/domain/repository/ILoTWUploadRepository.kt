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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import java.util.Locale

interface ILoTWUploadRepository {
    suspend fun certificate(): LoTWCertificate?
    suspend fun station(): LoTWStation?
    suspend fun importCertificate(data: ByteArray, password: CharArray): LoTWCertificate
    /** Parse a .p12 without persisting it — enough to learn the certificate's DXCC
     *  entity so the station form can offer its region field before the import. */
    suspend fun previewCertificate(data: ByteArray, password: CharArray): LoTWCertificate
    suspend fun saveCertificatePassword(password: CharArray): LoTWCertificate
    suspend fun saveStation(station: LoTWStation): LoTWStation
    suspend fun removeCertificate()
    /** Region field (State/Province/Prefecture…) and national CQZ/ITUZ map for a DXCC entity. */
    suspend fun stationMeta(dxcc: Int): LoTWStationMeta
    /** Every satellite name ARRL accepts (config.tq6), sorted. Only these names may be
     *  signed, so the logbook UI validates/picks satellite names from this list. */
    suspend fun satelliteCatalog(): List<String>
    suspend fun audit(records: List<QsoRecord>): LoTWUploadAudit
    suspend fun prepare(records: List<QsoRecord>, resubmit: Boolean): LoTWUploadPreview
    suspend fun upload(previewId: String): LoTWUploadResult
    fun discardPreview()
}

data class LoTWCertificate(
    val callsign: String,
    val dxcc: Int,
    val serial: String,
    val expires: String,
    val firstQsoDate: String,
    val lastQsoDate: String,
    val passwordSaved: Boolean = false
)

/** The location is explicitly confirmed for the selected contacts, not inferred from today's GPS. */
data class LoTWStation(
    val grid: String = "",
    val cqZone: String = "",
    val ituZone: String = "",
    val region: String = "",
    val county: String = "",
    val iota: String = ""
)

/** One (ITU:CQ) pair from a LoTW zonemap, e.g. Guangdong is 44:24. */
data class LoTWZonePair(val itu: Int, val cq: Int)

/** One selectable region code with the zones it maps to (cross-zone regions carry several pairs). */
data class LoTWRegionOption(val code: String, val name: String, val zones: List<LoTWZonePair>)

/** Country-dependent region field (US_STATE, CN_PROVINCE, …) with its selectable options. */
data class LoTWRegionField(val id: String, val label: String, val options: List<LoTWRegionOption>)

/**
 * Everything the station-location UI needs for one DXCC entity:
 * the region field to show (null when the country has none) and the national
 * zonemap (used to prefill CQZ/ITUZ when the country has a single zone pair).
 */
data class LoTWStationMeta(
    val regionField: LoTWRegionField? = null,
    val countryZones: List<LoTWZonePair> = emptyList()
)

data class LoTWUploadPreview(
    val id: String,
    val callsign: String,
    val dxcc: Int,
    val grid: String,
    val count: Int,
    val skipped: Int,
    val firstUtc: String,
    val lastUtc: String,
    val contacts: List<String>,
    val unknownSkipped: Int = 0,
    /** Un-signable records (invalid call/date/…) skipped instead of aborting the batch. */
    val unavailableSkipped: Int = 0,
    /** Why those records were un-signable, so the operator sees the real cause. */
    val unavailableReasons: Map<LoTWProblem, Int> = emptyMap(),
    /** Records dropped because the same contact already appears earlier in the batch. */
    val duplicateSkipped: Int = 0,
    /** Ids of the records that actually made it into this TQ8 batch. Only these
     *  may be marked "uploaded" after an accepted POST — never the full candidate list. */
    val submittedIds: List<Long> = emptyList(),
    /** The full station-location grid set this batch is signed with (1 inside a grid,
     *  2 on a line, 4 on a corner). Stamped onto the uploaded records so the logbook
     *  can show which gridsquares the QSO went out under. */
    val grids: List<String> = emptyList(),
    /** True when this batch is a resubmit: already-uploaded/confirmed records were allowed
     *  through so the corrected station location reaches LoTW as an update of the contact. */
    val resubmit: Boolean = false,
    /** Records in this batch whose own callsign is blank (logged before a certificate was
     *  installed). They are signed with [callsign]; a successful upload writes it into them. */
    val missingCallsign: Int = 0,
    /** Records refused because they name a different own callsign than [callsign]. The upload
     *  flow offers either rewriting them with [callsign] or using the other certificate. */
    val callsignConflicts: List<Long> = emptyList()
)

/** Pre-upload location check: the freshest known position grid is not covered by the
 *  station grids an upload would be signed with. */
data class LoTWPositionWarning(
    /** Normalized 4-character grid of the freshest known position. */
    val currentGrid: String,
    /** Distinct station-location grids the upload would be signed with. */
    val stationGrids: List<String>
)

/** Why the un-uploadable records of a selection cannot be signed, and what was left out. */
data class LoTWUploadAudit(
    val total: Int,
    val pending: Int,
    val uploaded: Int,
    val unknown: Int,
    val unavailable: Int,
    /** Signing failures counted per reason (see [com.rtbishop.look4sat.core.domain.logbook.label]). */
    val reasons: Map<LoTWProblem, Int> = emptyMap(),
    /** First offending value per reason, e.g. SATELLITE -> "SAUDISAT 1C". */
    val details: Map<LoTWProblem, String> = emptyMap(),
    /** Records dropped as duplicates of an earlier record in the same selection. */
    val duplicates: Int = 0,
    /** Records that are not marked complete, so they are not uploadable at all. */
    val incomplete: Int = 0
)

sealed interface LoTWUploadResult {
    data class Accepted(val count: Int) : LoTWUploadResult
    data class Rejected(val message: String = "") : LoTWUploadResult
    /** May have reached LoTW. Keep the receipt unknown and never retry automatically. */
    data class Unknown(
        val reason: String = "The server response did not confirm whether the upload was accepted.",
        val httpStatus: Int? = null,
        val technicalDetail: String = ""
    ) : LoTWUploadResult
    data object ExpiredPreview : LoTWUploadResult
}

class LoTWOperationException(val reason: LoTWProblem, val detail: String = "") : Exception(reason.name)

enum class LoTWProblem {
    CERTIFICATE_PASSWORD, CERTIFICATE_INVALID, CERTIFICATE_EXPIRED, CERTIFICATE_MISSING,
    CERTIFICATE_FORMAT, STORAGE, EMPTY_SELECTION, CALLSIGN_MISMATCH, QSO_DATE, QSO_FUTURE, STATION_GRID,
    STATION_GRID_COUNT, STATION_GRID_NOT_ADJACENT,
    STATION_REGION, STATION_ZONE, STATION_IOTA, MODE, BAND, SATELLITE, INVALID_CONTACT,
    LOCATION_MISMATCH, TOO_MANY_CONTACTS
}

/**
 * Roaming check run before upload: warns when the operator's freshest known position
 * (see [ISettingsRepo.getCurrentGrid]) lies outside every grid of the station location the
 * batch would be signed with — the signature of operating while roaming with a station
 * location that was never updated. An unknown position, missing station grids, or a covered
 * position (boundary operations included) produce no warning.
 */
fun positionWarning(currentGrid: String?, stationGrids: List<String>): LoTWPositionWarning? {
    val current = currentGrid?.grid4() ?: return null
    val station = stationGrids.mapNotNull { it.grid4() }.toSet()
    if (station.isEmpty()) return null
    if (current in station) return null
    return LoTWPositionWarning(current, station.sorted())
}

/** Normalized 4-character grid, null when the value is not a usable grid. */
private fun String.grid4(): String? = trim().uppercase(Locale.US).take(4).takeIf { it.length >= 4 }
