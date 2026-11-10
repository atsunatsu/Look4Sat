/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import kotlinx.coroutines.flow.Flow

interface IQsoRepository {
    val records: Flow<List<QsoRecord>>

    suspend fun find(id: Long): QsoRecord?
    suspend fun save(record: QsoRecord): Long
    suspend fun delete(id: Long)
    /**
     * Marks the batch as uploaded and stamps the station-location grids the batch was
     * signed with (1–4 gridsquares: inside a grid / on a line / on a corner), so the
     * logbook row and own-station selector reflect a corrected resubmission instead
     * of retaining the previously signed location.
     */
    suspend fun markUploaded(ids: List<Long>, grids: List<String> = emptyList(), certificateCallsign: String = "")

    /**
     * Rewrites the operator's callsign on the given records. This is the answer to "these contacts
     * were logged under another certificate — upload them under the one installed now". Returns the
     * number of records that changed.
     */
    suspend fun rewriteMyCallsign(ids: List<Long>, callsign: String): Int

    /**
     * Marks the batch as uploaded to Wavelog (independent of the LoTW upload state) and
     * stamps the station profile (台址) the batch went out through, so the logbook's
     * 台址 selector can group the records. An empty [stationId] leaves the stamp as-is.
     *
     * [stationCallsign] is the station profile's own callsign. It fills the own callsign of
     * records that have none — a Wavelog-only setup has no certificate to take it from — so the
     * local row matches the copy the server now holds. Non-blank callsigns are never touched.
     */
    suspend fun markWavelogUploaded(ids: List<Long>, stationId: String = "", stationCallsign: String = "")

    suspend fun exportAdi(ids: Set<Long>? = null, includeIncomplete: Boolean = false): String
    suspend fun importAdi(content: String): AdifImportResult
    suspend fun mergeConfirmed(records: List<QsoRecord>): AdifImportResult
    suspend fun mergeLoTW(records: List<QsoRecord>): AdifImportResult
    /** For a full download, atomically replace rows owned by this provider. */
    suspend fun mergeLoTW(records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean): AdifImportResult {
        check(!replaceExisting) { "Full sync not implemented by this repository" }
        return mergeLoTW(records)
    }

    /**
     * Merges QSO records pulled from Wavelog by a sync (the download direction):
     * matches by the same stable identity as every other import, tags matched and
     * freshly inserted rows with their Wavelog station profile, and marks them as
     * already uploaded so a later upload never sends them straight back.
     */
    suspend fun mergeWavelog(records: List<QsoRecord>): AdifImportResult
    suspend fun mergeWavelog(records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean): AdifImportResult {
        check(!replaceExisting) { "Full sync not implemented by this repository" }
        return mergeWavelog(records)
    }

    /**
     * Folds LoTW confirmations that were imported as their own rows back into the local
     * record they belong to (see [com.rtbishop.look4sat.core.domain.logbook.splitConfirmationPairs]).
     * Confirmations merged before the tracker-name/band identity fix split every contact in
     * two; this repairs the existing logbook. Returns the number of merged rows.
     */
    suspend fun consolidateConfirmations(): Int
}
