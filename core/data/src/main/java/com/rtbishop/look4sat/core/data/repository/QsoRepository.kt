/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.data.database.QsoDao
import com.rtbishop.look4sat.core.data.database.entity.QsoEntity
import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.AdifImportResult
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.bandsMatchOrMirror
import com.rtbishop.look4sat.core.domain.logbook.contentScore
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.sameConfirmedContact
import com.rtbishop.look4sat.core.domain.logbook.sameContactIdentity
import com.rtbishop.look4sat.core.domain.logbook.satelliteIdentity
import com.rtbishop.look4sat.core.domain.logbook.splitConfirmationPairs
import com.rtbishop.look4sat.core.domain.logbook.withConfirmation
import com.rtbishop.look4sat.core.domain.logbook.confirmationLookupKey
import com.rtbishop.look4sat.core.domain.logbook.officialSatelliteName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

class QsoRepository(
    private val dao: QsoDao,
    private val dispatcher: CoroutineDispatcher
) : IQsoRepository, com.rtbishop.look4sat.core.domain.logbook.ITransactionalLoTWSync {
    private val importMutex = Mutex()

    override val records: Flow<List<QsoRecord>> = dao.observeAll().map { records -> records.map(QsoEntity::toDomain) }

    override suspend fun find(id: Long): QsoRecord? = withContext(dispatcher) { dao.find(id)?.toDomain() }

    override suspend fun save(record: QsoRecord): Long = withContext(dispatcher) {
        importMutex.withLock {
            val previous = if (record.id != 0L) dao.find(record.id)?.toDomain() else null
            // Editing a record that was already uploaded or confirmed must not rewrite it in
            // place: that contact already exists on LoTW under the old values. The original row
            // keeps its upload/confirmation state, and the edited content is saved as a NEW
            // record (without any LoTW state, so it can be uploaded again).
            if (previous != null &&
                (previous.lotwUploaded || previous.lotwConfirmed || previous.wavelogUploaded) &&
                !record.sameEditableContentAs(previous)
            ) {
                dao.save(record.copy(
                    id = 0L,
                    lotwUploaded = false, lotwConfirmed = false, lotwReceived = false,
                    wavelogUploaded = false,
                    syncOrigin = "",
                    wavelogStation = "",
                    lotwQslDate = "", vuccGrids = emptyList(), theirVuccGrids = emptyList(),
                    dxcc = null, country = "", cqZone = null, region = ""
                ).toEntity())
            } else {
                val saved = if (previous?.lotwConfirmed == true && sameConfirmedContact(record, previous)) {
                    record.withConfirmation(previous)
                } else if (previous?.lotwConfirmed == true) record.copy(
                    lotwConfirmed = false, lotwQslDate = "", vuccGrids = emptyList(), theirVuccGrids = emptyList(),
                    dxcc = null, country = "", cqZone = null, region = ""
                ) else record
                val received = if (previous != null && (
                    stableQsoKey(record) != stableQsoKey(previous) || record.myGrid != previous.myGrid ||
                        record.rxFrequencyHz != previous.rxFrequencyHz || record.band != previous.band ||
                        record.rxBand != previous.rxBand || record.propagationMode != previous.propagationMode
                    )) false else saved.lotwReceived
                dao.save(saved.copy(lotwReceived = received, syncOrigin = "").toEntity())
            }
        }
    }

    override suspend fun delete(id: Long) = withContext(dispatcher) { importMutex.withLock { dao.delete(id) } }

    override suspend fun markUploaded(ids: List<Long>, grids: List<String>, certificateCallsign: String) = withContext(dispatcher) {
        if (ids.isEmpty()) return@withContext
        dao.markUploaded(ids)
        // Stamp the station grid set this batch went out under (a line/corner prefill from
        // the grid finder carries 2–4 grids). The logbook row shows it after the timestamp.
        val normalized = grids.map { it.trim().uppercase(Locale.US).take(4) }
            .filter { it.length >= 4 }.distinct().sorted()
        if (normalized.isNotEmpty()) {
            val stamped = ids.mapNotNull { id ->
                dao.find(id)?.toDomain()?.takeIf { it.vuccGrids != normalized }?.copy(vuccGrids = normalized)
            }
            if (stamped.isNotEmpty()) dao.saveBatch(stamped.map { it.toEntity() })
        }
        // Records logged while no certificate was installed carry no own callsign; they were just
        // signed with the certificate's, so write it into them. Blanks only, and through save() so
        // the stored dedupe key follows the new value.
        if (certificateCallsign.isNotBlank()) {
            ids.mapNotNull { id -> dao.find(id)?.toDomain()?.takeIf { it.myCallsign.isBlank() } }
                .forEach { save(it.copy(myCallsign = certificateCallsign.trim().uppercase(Locale.US))) }
        }
    }

    override suspend fun rewriteMyCallsign(ids: List<Long>, callsign: String): Int = withContext(dispatcher) {
        val target = callsign.trim().uppercase(Locale.US)
        if (ids.isEmpty() || target.isBlank()) return@withContext 0
        var changed = 0
        ids.forEach { id ->
            val record = dao.find(id)?.toDomain() ?: return@forEach
            if (record.myCallsign.trim().equals(target, true)) return@forEach
            save(record.copy(myCallsign = target))
            changed++
        }
        changed
    }

    override suspend fun markWavelogUploaded(ids: List<Long>, stationId: String, stationCallsign: String) =
        withContext(dispatcher) {
            if (ids.isEmpty()) return@withContext
            importMutex.withLock { dao.markWavelogUploaded(ids, stationId) }
            // Same backfill as the LoTW side, from the other identity source: a Wavelog-only setup
            // has no certificate, so records logged there carry no own callsign. The server has
            // just imported them under the station profile's callsign — write that in, so the
            // local row is the one a later sync pulls back (the dedupe key contains the own
            // callsign; a blank one could never match the server's copy). Blanks only, and
            // through save() so the stored key follows.
            if (stationCallsign.isNotBlank()) {
                ids.mapNotNull { id -> dao.find(id)?.toDomain()?.takeIf { it.myCallsign.isBlank() } }
                    .forEach { save(it.copy(myCallsign = stationCallsign.trim().uppercase(Locale.US))) }
            }
        }

    override suspend fun exportAdi(ids: Set<Long>?, includeIncomplete: Boolean): String = withContext(dispatcher) {
        val records = dao.getAll()
            .asSequence()
            .filter { ids == null || it.id in ids }
            .map(QsoEntity::toDomain)
            .filter { includeIncomplete || it.status == QsoStatus.COMPLETE }
            .toList()
        AdifCodec.encode(records)
    }

    override suspend fun importAdi(content: String): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock {
            mergeRecords(AdifCodec.decode(content))
        }
    }

    override suspend fun mergeConfirmed(records: List<QsoRecord>): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock { mergeRecords(records.filter { it.lotwConfirmed }) }
    }

    override suspend fun mergeLoTW(records: List<QsoRecord>): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock { mergeRecords(records, fromLoTW = true) }
    }
    override suspend fun mergeLoTW(
        records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean
    ): AdifImportResult = withContext(dispatcher) {
        val origin = "LOTW|" + sourceKey.trim().uppercase(Locale.US)
        require(sourceKey.isNotBlank())
        importMutex.withLock { mergeRecords(records, fromLoTW = true, origin = origin, replaceExisting = replaceExisting) }
    }

    /**
     * Merges QSO records pulled from Wavelog (the sync/download direction).
     *
     * Matching uses the same [stableQsoKey] identity as every other import, so a record
     * already recorded locally (typically uploaded from this app) is not duplicated — it
     * simply learns its Wavelog station profile and, where the local row is missing them,
     * the opposite-station fields the server kept. Fresh records are inserted already
     * marked as uploaded, so a later upload never sends them straight back.
     */
    override suspend fun mergeWavelog(records: List<QsoRecord>): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock { mergeWavelogRecords(records) }
    }
    override suspend fun mergeWavelog(
        records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean
    ): AdifImportResult = withContext(dispatcher) {
        require(sourceKey.isNotBlank())
        val origin = "WAVELOG|" + sourceKey.trim().trimEnd('/').lowercase(Locale.US)
        importMutex.withLock { mergeWavelogRecords(records, origin, replaceExisting) }
    }

    override suspend fun consolidateConfirmations(): Int = withContext(dispatcher) {
        importMutex.withLock {
            val working = dao.getAll().map(QsoEntity::toDomain).toMutableList()
            val consolidation = consolidateSplitRows(working)
            val renames = rewriteOfficialNames(working)
            if (consolidation.merged.isEmpty() && consolidation.redundant.isEmpty() && renames.isEmpty()) {
                return@withLock 0
            }
            val toSave = LinkedHashMap(consolidation.merged).apply { putAll(renames) }
            dao.saveBatch(toSave.values.map(QsoRecord::toEntity))
            consolidation.redundant.forEach { dao.delete(it.id) }
            consolidation.redundant.size
        }
    }

    private suspend fun mergeRecords(
        records: List<QsoRecord>, fromLoTW: Boolean = false,
        origin: String = "", replaceExisting: Boolean = false
    ): AdifImportResult {
        val existing = dao.getAll()
        val (removed, retained) = existing.partition { replaceExisting && it.syncOrigin == origin }
        val working = retained.map(QsoEntity::toDomain).toMutableList()
        // Consolidate first: confirmations synced before the identity fix sit as their own
        // rows (the tracker name and a mirrored band direction both failed the comparison),
        // and left in place they would make the lookup below ambiguous between the operator's
        // own row and the stale duplicate.
        val consolidation = consolidateSplitRows(working)
        // Rows imported under a tracking-source name join the ARRL name here, so what is stored
        // is the identity both sides of a match resolve to (the lookup below is built on it).
        val renames = rewriteOfficialNames(working)
        val lookup = working.indices.groupBy { working[it].confirmationLookupKey() }
            .mapValues { it.value.toMutableList() }.toMutableMap()
        val knownKeys = working.map { stableQsoKey(it) }.toMutableSet()
        val changes = linkedMapOf<Int, QsoRecord>().apply {
            putAll(consolidation.merged)
            putAll(renames)
        }
        var imported = 0
        var updated = consolidation.merged.size
        var skipped = 0
        records.forEach { remote ->
            if (!fromLoTW && !remote.lotwConfirmed && stableQsoKey(remote) in knownKeys) { skipped++; return@forEach }
            val candidates = if (fromLoTW || remote.lotwConfirmed) lookup[remote.confirmationLookupKey()].orEmpty()
                .filter { sameConfirmedContact(working[it], remote) } else emptyList()
            val exact = candidates.filter { working[it].startUtcMillis == remote.startUtcMillis }
            val match = exact.singleOrNull() ?: candidates.singleOrNull()
            if (match == null) {
                // A confirmation downloaded by an early release sits as its own row with the
                // band direction read mirrored (the downlink was stored as the uplink). The
                // strict lookup above cannot see it: its bands fail the comparison, and its
                // dedupe key can differ too (fields written by older releases — timestamp
                // precision, FT4 sub-mode spelling, missing callsign — so it is not treated
                // as known either). Fold the report into the richest mirrored row instead of
                // inserting a second row next to it; left as two rows, neither consolidation
                // nor a later sync could reliably heal them.
                val mirroredRows = if (fromLoTW) lookup[remote.confirmationLookupKey()].orEmpty().filter { index ->
                    working[index].band.isNotBlank() && remote.band.isNotBlank() &&
                        !working[index].band.equals(remote.band, true) &&
                        sameContactIdentity(working[index], remote) &&
                        bandsMatchOrMirror(working[index], remote)
                } else emptyList()
                if (mirroredRows.isNotEmpty()) {
                    val target = mirroredRows.maxBy { contentScore(working[it]) }
                    mirroredRows.forEach { index ->
                        val healed = working[index].copy(
                            band = remote.band.ifBlank { working[index].band },
                            rxBand = remote.rxBand.ifBlank { working[index].rxBand }
                        )
                        val folded = if (index == target) healed.withConfirmation(remote).copy(
                            syncOrigin = sharedOrigin(healed.syncOrigin, origin)) else healed
                        working[index] = folded
                        changes[index] = folded
                    }
                    updated += mirroredRows.size
                    return@forEach
                }
                if (stableQsoKey(remote) in knownKeys) { skipped++; return@forEach }
                val added = remote.copy(id = 0L, satelliteName = officialSatelliteName(remote.satelliteName), syncOrigin = origin)
                changes[working.size] = added
                lookup.getOrPut(added.confirmationLookupKey()) { mutableListOf() }.add(working.size)
                knownKeys += stableQsoKey(added)
                working += added
                imported++
            } else {
                val previous = working[match]
                val merged = previous.withConfirmation(remote).copy(syncOrigin = sharedOrigin(previous.syncOrigin, origin))
                if (merged == previous) skipped++ else {
                    working[match] = merged
                    changes[match] = merged
                    updated++
                }
            }
        }
        dao.applySyncBatch(removed.map { it.id }, changes.values.map(QsoRecord::toEntity), consolidation.redundant.map { it.id })
        return AdifImportResult(imported, skipped, updated)
    }

    private suspend fun mergeWavelogRecords(
        records: List<QsoRecord>, origin: String = "", replaceExisting: Boolean = false
    ): AdifImportResult {
        val existing = dao.getAll()
        val (removed, retained) = existing.partition { replaceExisting && it.syncOrigin == origin }
        val working = retained.map(QsoEntity::toDomain).toMutableList()
        // Rows imported under a tracking-source name join the ARRL name first, so the
        // lookup below sees one identity for both sides of a match.
        val renames = rewriteOfficialNames(working)
        val indexByKey = HashMap<String, Int>(working.size)
        working.indices.forEach { index -> indexByKey.putIfAbsent(stableQsoKey(working[index]), index) }
        val changes = linkedMapOf<Int, QsoRecord>().apply { putAll(renames) }
        var imported = 0
        var updated = renames.size
        var skipped = 0
        val seenKeys = hashSetOf<String>()
        records.forEach { remote ->
            val key = stableQsoKey(remote)
            if (!seenKeys.add(key)) {
                skipped++
                return@forEach
            }
            // Wavelog may add a frequency later to a QSO previously synced without one.
            // Match only a unique identical contact whose transmit frequency is missing
            // on exactly one side, so different-frequency QSOs are never collapsed.
            val index = indexByKey[key] ?: working.indices.filter { i ->
                sameWavelogContactWithMissingFrequency(working[i], remote)
            }.singleOrNull()
            if (index == null) {
                val added = remote.copy(
                    id = 0L,
                    syncOrigin = origin,
                    wavelogUploaded = true,
                    satelliteName = officialSatelliteName(remote.satelliteName)
                )
                changes[working.size] = added
                indexByKey[stableQsoKey(added)] = working.size
                working += added
                imported++
            } else {
                val previous = working[index]
                val merged = previous.copy(
                    syncOrigin = sharedOrigin(previous.syncOrigin, origin),
                    wavelogUploaded = true,
                    wavelogStation = remote.wavelogStation.ifBlank { previous.wavelogStation },
                    // Fill missing numeric frequencies, but never clear or overwrite
                    // frequencies already stored locally.
                    txFrequencyHz = previous.txFrequencyHz ?: remote.txFrequencyHz,
                    rxFrequencyHz = previous.rxFrequencyHz ?: remote.rxFrequencyHz,
                    band = previous.band.ifBlank { remote.band },
                    rxBand = previous.rxBand.ifBlank { remote.rxBand },
                    // Use the latest matching server station locator, retaining local data
                    // only when the Wavelog export has no MY_GRIDSQUARE.
                    myGrid = remote.myGrid.ifBlank { previous.myGrid },
                    theirGrid = previous.theirGrid.ifBlank { remote.theirGrid },
                    theirVuccGrids = previous.theirVuccGrids.ifEmpty { remote.theirVuccGrids },
                    theirFullGrids = (previous.theirFullGrids + remote.theirFullGrids).distinct(),
                    myFullGrids = (previous.myFullGrids + remote.myFullGrids).distinct(),
                    iota = remote.iota.ifBlank { previous.iota },
                    myIota = remote.myIota.ifBlank { previous.myIota }
                )
                if (merged != previous) {
                    working[index] = merged
                    indexByKey.putIfAbsent(stableQsoKey(merged), index)
                    changes[index] = merged
                    updated++
                } else {
                    skipped++
                }
            }
        }
        dao.applySyncBatch(removed.map { it.id }, changes.values.map { it.toEntity() })
        return AdifImportResult(imported, skipped, updated)
    }

    /** Result of folding already-split rows back together. */
    private data class Consolidation(
        /** index in the stored list -> the record to save (local row + its confirmation). */
        val merged: Map<Int, QsoRecord>,
        /** imported confirmation rows that are now part of a local record. */
        val redundant: List<QsoRecord>
    )

    private fun sharedOrigin(previous: String, incoming: String): String =
        if (previous == incoming) previous else ""

    /**
     * Rewrites the names records were imported under into their ARRL names ("SAUDISAT 1C" ->
     * "SO-50"), so the logbook, the ADIF export and the signed record all carry one name.
     * Names the alias table does not know (recycled placeholders, satellites ARRL does not
     * list) are kept exactly as they are. [working] is updated in place; the rewritten records
     * are returned for the caller to save.
     */
    private fun rewriteOfficialNames(working: MutableList<QsoRecord>): Map<Int, QsoRecord> {
        val renamed = mutableMapOf<Int, QsoRecord>()
        working.indices.forEach { index ->
            val record = working[index]
            val official = officialSatelliteName(record.satelliteName)
            if (official != record.satelliteName) {
                val updated = record.copy(satelliteName = official)
                working[index] = updated
                renamed[index] = updated
            }
        }
        return renamed
    }

    /**
     * Folds confirmation rows that were imported as separate QSOs back into the local
     * record they belong to (see [splitConfirmationPairs]). [working] is updated in
     * place; the redundant rows are returned for the caller to delete.
     */
    private fun consolidateSplitRows(working: MutableList<QsoRecord>): Consolidation {
        val merged = mutableMapOf<Int, QsoRecord>()
        val redundant = mutableListOf<QsoRecord>()
        // Snapshot the pairs first: the list is rewritten as pairs are applied.
        splitConfirmationPairs(working.toList()).forEach { pair ->
            val local = working[pair.localIndex]
            val confirmation = working[pair.confirmationIndex]
            val folded = local.withConfirmation(confirmation).copy(
                syncOrigin = sharedOrigin(local.syncOrigin, confirmation.syncOrigin))
            if (folded != local) {
                working[pair.localIndex] = folded
                merged[pair.localIndex] = folded
            }
            redundant += confirmation
        }
        return Consolidation(merged, redundant)
    }
}

private fun QsoEntity.toDomain() = QsoRecord(
    id = id,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    theirCallsign = theirCallsign,
    myCallsign = myCallsign,
    theirGrid = theirGrid,
    myGrid = myGrid,
    sentReport = sentReport,
    receivedReport = receivedReport,
    txFrequencyHz = txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz,
    band = band,
    rxBand = rxBand,
    mode = mode,
    submode = submode,
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    passAosUtcMillis = passAosUtcMillis,
    automatic = automatic,
    status = runCatching { QsoStatus.valueOf(status) }.getOrDefault(QsoStatus.DRAFT),
    propagationMode = propagationMode,
    lotwConfirmed = lotwConfirmed,
    lotwUploaded = lotwUploaded,
    wavelogUploaded = wavelogUploaded,
    wavelogStation = wavelogStation,
    lotwReceived = lotwReceived,
    lotwQslDate = lotwQslDate,
    vuccGrids = vuccGrids.split(',').filter(String::isNotBlank),
    theirVuccGrids = theirVuccGrids.split(',').filter(String::isNotBlank),
    dxcc = dxcc,
    country = country,
    cqZone = cqZone,
    region = region,
    comment = comment,
    iota = iota,
    theirFullGrids = theirFullGrids.split(',').filter(String::isNotBlank),
    myFullGrids = myFullGrids.split(',').filter(String::isNotBlank),
    myIota = myIota,
    syncOrigin = syncOrigin
)

private fun QsoRecord.toEntity() = QsoEntity(
    id = id,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    theirCallsign = theirCallsign.trim().uppercase(Locale.US),
    myCallsign = myCallsign.trim().uppercase(Locale.US),
    theirGrid = theirGrid.trim().uppercase(Locale.US),
    myGrid = myGrid.trim().uppercase(Locale.US),
    sentReport = sentReport.trim(),
    receivedReport = receivedReport.trim(),
    txFrequencyHz = txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz,
    band = band,
    rxBand = rxBand,
    mode = mode.trim(),
    submode = submode.trim(),
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    passAosUtcMillis = passAosUtcMillis,
    automatic = automatic,
    status = status.name,
    dedupeKey = stableQsoKey(this),
    propagationMode = propagationMode.ifBlank { if (satelliteName.isNotBlank()) "SAT" else "" },
    lotwConfirmed = lotwConfirmed,
    lotwUploaded = lotwUploaded,
    wavelogUploaded = wavelogUploaded,
    wavelogStation = wavelogStation.trim(),
    lotwReceived = lotwReceived,
    lotwQslDate = lotwQslDate,
    vuccGrids = vuccGrids.joinToString(","),
    theirVuccGrids = theirVuccGrids.joinToString(","),
    dxcc = dxcc,
    country = country,
    cqZone = cqZone,
    region = region,
    comment = comment,
    iota = iota.trim().uppercase(Locale.US),
    theirFullGrids = theirFullGrids.joinToString(","),
    myFullGrids = myFullGrids.joinToString(","),
    myIota = myIota.trim().uppercase(Locale.US),
    syncOrigin = syncOrigin
)

/**
 * One-side-missing-frequency fallback for Wavelog ADIF downloads.
 * Keep the exact timestamp and all other stable QSO identity fields: guessing by callsign
 * and minute alone could fold two real contacts from the same satellite pass.
 */
private fun sameWavelogContactWithMissingFrequency(local: QsoRecord, remote: QsoRecord): Boolean =
    (local.txFrequencyHz == null) != (remote.txFrequencyHz == null) &&
        stableQsoKey(local.copy(txFrequencyHz = null)) ==
            stableQsoKey(remote.copy(txFrequencyHz = null)) &&
        (local.rxFrequencyHz == null || remote.rxFrequencyHz == null ||
            local.rxFrequencyHz == remote.rxFrequencyHz) &&
        (local.band.isBlank() || remote.band.isBlank() || local.band.equals(remote.band, true)) &&
        (local.rxBand.isBlank() || remote.rxBand.isBlank() || local.rxBand.equals(remote.rxBand, true))

internal fun stableQsoKey(record: QsoRecord): String = listOf(
    record.startUtcMillis.toString(),
    record.theirCallsign.trim().uppercase(Locale.US),
    record.myCallsign.trim().uppercase(Locale.US),
    record.txFrequencyHz?.toString().orEmpty(),
    record.mode.trim().uppercase(Locale.US),
    record.submode.trim().uppercase(Locale.US),
    // The identity, not the spelling: a row stored as "SO-50" is the same QSO as one imported
    // as "SAUDISAT 1C", so re-importing an older export does not duplicate it.
    satelliteIdentity(record.satelliteName)
).joinToString("|")

/**
 * Whether two rows carry the same operator-editable content.
 *
 * Minutes are the granularity for time: the edit dialog edits whole minutes (and rounds the
 * stored seconds away when saving), so a seconds-only difference must not count as an edit.
 * The satellite is compared by its ARRL identity, the mode by its display label — both sides
 * of the same contact written differently (tracker vs official name, MFSK vs FT4) are the same
 * content. Used to decide whether saving an already-uploaded/confirmed row should create a new
 * record or leave the row alone.
 */
private fun QsoRecord.sameEditableContentAs(other: QsoRecord): Boolean =
    theirCallsign.trim().equals(other.theirCallsign.trim(), true) &&
        startUtcMillis / 60_000L == other.startUtcMillis / 60_000L &&
        txFrequencyHz == other.txFrequencyHz &&
        rxFrequencyHz == other.rxFrequencyHz &&
        displayMode == other.displayMode &&
        satelliteIdentity(satelliteName) == satelliteIdentity(other.satelliteName) &&
        sentReport.trim() == other.sentReport.trim() &&
        receivedReport.trim() == other.receivedReport.trim() &&
        theirGrid.trim().uppercase(Locale.US) == other.theirGrid.trim().uppercase(Locale.US) &&
        comment == other.comment
