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
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.domain.model.GridQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logbook split reported 2026-09-28: a QSO uploaded from the app and the confirmation LoTW
 * reported for it ended up as two rows, because the record keeps the tracking-source name
 * ("SAUDISAT 1C") and the uplink band while the report carries the ARRL name ("SO-50") — and,
 * before the direction fix, the mirrored band pair.
 */
class QsoRepositoryTest {

    private val dao = FakeQsoDao()
    private val repository = QsoRepository(dao, Dispatchers.Unconfined)

    /** 2026-09-16 07:45Z, an SO-50 contact of the user's own report. */
    private val qsoStart = 1_789_544_700_000L

    @Test
    fun mergeLoTW_confirmsTheUploadedRecordInsteadOfAddingASecondRow() = runBlocking {
        val uploadedId = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity()) // 145.850 -> 2M

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(uploadedId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // The row is also renamed to the ARRL spelling while it is merged.
        assertEquals("SO-50", rows.first().satelliteName)
        assertEquals(145_850_000L, rows.first().txFrequencyHz)
        // The opposite station's grid set from the report lands on the row.
        assertEquals("EN52", rows.first().theirGrid)
        assertEquals("EN52,EN53", rows.first().theirVuccGrids)
    }

    @Test
    fun mergeLoTW_foldsRowsSplitByTheOldNameAndBandMismatch() = runBlocking {
        val uploadedId = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())
        // Row imported before the fix: ARRL name and the mirrored band pair (BAND_RX read as
        // the uplink), so it never folded into the local record.
        dao.save(
            reportConfirmation(satName = "SO-50").copy(band = "70CM", rxBand = "2M").toEntity()
        )

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(uploadedId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // The operator's own frequencies survive the consolidation.
        assertEquals(145_850_000L, rows.first().txFrequencyHz)
        assertEquals(436_795_000L, rows.first().rxFrequencyHz)
    }

    @Test
    fun mergeLoTW_keepsAnotherContactOfTheSamePassApart() = runBlocking {
        dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())
        val otherOperator = reportConfirmation(satName = "SO-50").copy(theirCallsign = "BG5JVM")

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50"), otherOperator))

        val rows = dao.getAll()
        assertEquals(2, rows.size)
        // The local row is confirmed by its own report entry; the second operator's contact
        // is a separate QSO and stays a separate, separately confirmed row.
        assertEquals(setOf("BG5JSB", "BG5JVM"), rows.map { it.theirCallsign }.toSet())
        assertTrue(rows.all { it.lotwConfirmed })
    }

    @Test
    fun consolidateConfirmations_repairsAnAlreadySplitLogbook() = runBlocking {
        val uploadedId = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())
        // Row imported before the fix: ARRL name + mirrored band pair.
        dao.save(reportConfirmation(satName = "SO-50").copy(band = "70CM", rxBand = "2M").toEntity())

        assertEquals(1, repository.consolidateConfirmations())

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(uploadedId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // Nothing left to fold on a second pass.
        assertEquals(0, repository.consolidateConfirmations())
    }

    @Test
    fun consolidateConfirmations_renamesRowsImportedUnderATrackerName() = runBlocking {
        // A record logged before names were normalised at write time.
        val id = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())

        repository.consolidateConfirmations()

        val row = dao.getAll().single()
        assertEquals(id, row.id)
        assertEquals("SO-50", row.satelliteName)
        // Upload state and the operator's own frequencies survive the rename.
        assertTrue(row.lotwUploaded)
        assertEquals(145_850_000L, row.txFrequencyHz)
        // A satellite ARRL does not list keeps whatever the tracker published.
        dao.save(loggedInApp("FOO-1", lotwUploaded = false).toEntity())
        repository.consolidateConfirmations()
        assertEquals(setOf("SO-50", "FOO-1"), dao.getAll().map { it.satelliteName }.toSet())
    }

    @Test
    fun consolidateConfirmations_foldsADoubledConfirmedPairKeepingTheRicherRow() = runBlocking {
        // The mirrored import the old parser created (no frequencies, no report-side grids —
        // the pre-fix parser filled neither — band direction reversed)... seeded FIRST so it
        // carries the lower id.
        dao.save(
            reportConfirmation(satName = "SO-50")
                .copy(band = "70CM", rxBand = "2M", theirGrid = "", theirVuccGrids = emptyList())
                .toEntity()
        )
        // ...plus the app row that was confirmed on its own later (frequencies, upload state).
        val localId = dao.save(
            loggedInApp("SO-50", lotwUploaded = true).copy(lotwConfirmed = true).toEntity()
        )

        assertEquals(1, repository.consolidateConfirmations())

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(localId, rows.first().id)
        // The surviving row keeps the correct uplink-first bands and gains the report fills.
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        assertEquals("OL62", rows.first().myGrid)
        assertEquals(318, rows.first().dxcc)
        assertEquals("China", rows.first().country)
        assertTrue(rows.first().lotwUploaded)
    }

    @Test
    fun mergeLoTW_healsADoubledMirroredContact() = runBlocking {
        // Two mirrored copies of one confirmation (as the pre-fix parser imported on separate
        // syncs — no frequencies, no report-side grids): the full sync must consolidate them
        // into one row with the report's direction.
        val stale = reportConfirmation(satName = "SO-50")
            .copy(band = "70CM", rxBand = "2M", theirGrid = "", theirVuccGrids = emptyList())
        dao.save(stale.toEntity())
        dao.save(stale.copy(id = 0L).toEntity())

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        // The report's fills land on the healed row.
        assertEquals("EN52", rows.first().theirGrid)
    }

    @Test
    fun mergeLoTW_healsAMirroredRowWhoseDedupeKeyDivergedFromTheReport() = runBlocking {
        // The doubled contacts of the reported bug: a mirrored row written by an older release
        // (millisecond-precision timestamp here — enough to make the dedupe key, which holds
        // the exact time, differ from the report's) used to gain a second row next to it
        // instead of being healed, because every net relied on the key or the strict bands.
        val staleId = dao.save(
            reportConfirmation(satName = "SO-50")
                .copy(
                    band = "70CM", rxBand = "2M", theirGrid = "", theirVuccGrids = emptyList(),
                    startUtcMillis = qsoStart + 456L
                )
                .toEntity()
        )

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(staleId, rows.first().id)
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        assertTrue(rows.first().lotwConfirmed)
        assertEquals("EN52", rows.first().theirGrid)
    }

    @Test
    fun mergeLoTW_foldsAMirroredRowSpelledAsPlainMfskIntoItsFt4Report() = runBlocking {
        // Releases that persisted no sub-mode stored FT4 rows as plain MFSK while the report
        // carries MFSK + FT4. The spelling difference must not block the heal either.
        dao.save(
            reportConfirmation(satName = "SO-50")
                .copy(
                    band = "70CM", rxBand = "2M", mode = "MFSK", submode = "",
                    theirGrid = "", theirVuccGrids = emptyList()
                )
                .toEntity()
        )

        repository.mergeLoTW(
            listOf(reportConfirmation(satName = "SO-50").copy(mode = "MFSK", submode = "FT4"))
        )

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        assertTrue(rows.first().lotwConfirmed)
        assertEquals("EN52", rows.first().theirGrid)
    }

    @Test
    fun mergeLoTW_fixesBandDirectionOfAStaleConfirmationRow() = runBlocking {
        // A confirmation downloaded by the pre-fix parser: ARRL name but the band direction
        // mirrored (downlink stored as the uplink), and no local row to fold it into, so it
        // sits as its own QSL row.
        val staleId = dao.save(reportConfirmation(satName = "SO-50").copy(band = "70CM", rxBand = "2M").toEntity())

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(staleId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // The stale row adopts the report's (uplink-first) direction instead of being skipped
        // forever by the dedupe key.
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
    }

    @Test
    fun save_editingAnUploadedRecordCreatesANewRecord() = runBlocking {
        val originalId = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true))
        val edited = loggedInApp("SAUDISAT 1C", lotwUploaded = true).copy(id = originalId, theirCallsign = "BG5JVM")

        val newId = repository.save(edited)

        assertNotEquals(originalId, newId)
        val rows = dao.getAll()
        assertEquals(2, rows.size)
        // The original keeps its identity and upload state, untouched.
        val original = rows.first { it.id == originalId }
        assertEquals("BG5JSB", original.theirCallsign)
        assertTrue(original.lotwUploaded)
        // The edited content is a fresh, uploadable record.
        val created = rows.first { it.id == newId }
        assertEquals("BG5JVM", created.theirCallsign)
        assertFalse(created.lotwUploaded)
        assertFalse(created.lotwConfirmed)
    }

    @Test
    fun save_editingAnUnuploadedRecordUpdatesInPlace() = runBlocking {
        val id = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = false))
        repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = false).copy(id = id, theirCallsign = "BG5JVM"))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("BG5JVM", rows.first().theirCallsign)
    }

    @Test
    fun save_editingAnUploadedRecordWithoutChangesKeepsOneRow() = runBlocking {
        val id = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true))
        repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).copy(id = id))

        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun save_editingAnUploadedRecordWithOnlySecondsChangedKeepsOneRow() = runBlocking {
        val id = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true))
        // The edit dialog edits whole minutes and rounds the seconds away on save, so a
        // seconds-only difference must not be treated as an edit (no new record).
        repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).copy(id = id, startUtcMillis = qsoStart + 10_000L))

        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun markUploaded_fillsBlankCallsignsAndKeepsTheOthers() = runBlocking {
        val blank = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "").toEntity())
        val named = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "BH3HCF").toEntity())

        repository.markUploaded(listOf(blank, named), emptyList(), "BA7OPF")

        val rows = dao.getAll().associateBy { it.id }
        // Records logged before a certificate existed get the certificate's callsign; a record that
        // names another callsign is not touched (they are refused, not rewritten, by the uploader).
        assertEquals("BA7OPF", rows[blank]!!.myCallsign)
        assertEquals("BH3HCF", rows[named]!!.myCallsign)
        assertTrue(rows[blank]!!.lotwUploaded)
        assertTrue(rows[named]!!.lotwUploaded)
    }

    @Test
    fun rewriteMyCallsign_rewritesTheConflictRecordsOnly() = runBlocking {
        val conflict = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "XX0YY").toEntity())
        val kept = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "BA7OPF").toEntity())

        val changed = repository.rewriteMyCallsign(listOf(conflict, kept), "ba7opf")

        assertEquals(1, changed)
        val rows = dao.getAll().associateBy { it.id }
        assertEquals("BA7OPF", rows[conflict]!!.myCallsign)
        assertEquals("BA7OPF", rows[kept]!!.myCallsign)
        assertFalse(rows[conflict]!!.lotwUploaded)
    }

    @Test
    fun markWavelogUploaded_setsTheFlagAndTheStationStamp() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = false).toEntity())

        repository.markWavelogUploaded(listOf(id), "3")

        val row = dao.getAll().single()
        assertTrue(row.wavelogUploaded)
        assertFalse(row.lotwUploaded)
        assertEquals("3", row.wavelogStation)
    }

    @Test
    fun markWavelogUploaded_fillsBlankCallsignsFromTheStationProfile() = runBlocking {
        val blank = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "").toEntity())
        val named = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "BH3HCF").toEntity())

        repository.markWavelogUploaded(listOf(blank, named), "3", "ba7opf")

        val rows = dao.getAll().associateBy { it.id }
        // A Wavelog-only setup has no certificate, so records logged there carry no own callsign.
        // The server imported them under the station profile's callsign — the local rows follow,
        // or a later sync would pull the same contact back as a second row (the dedupe key
        // contains the own callsign). A row naming another callsign is left alone.
        assertEquals("BA7OPF", rows[blank]!!.myCallsign)
        assertEquals("BH3HCF", rows[named]!!.myCallsign)
        assertTrue(rows[blank]!!.wavelogUploaded)
        assertEquals("3", rows[blank]!!.wavelogStation)
    }

    @Test
    fun save_editingAWavelogUploadedRecordCreatesANewRecord() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(wavelogUploaded = true).toEntity())

        repository.save(loggedInApp("SO-50", lotwUploaded = false).copy(id = id, theirCallsign = "BG5JVM"))

        assertEquals(2, dao.getAll().size)
        assertEquals(1, dao.getAll().count { it.wavelogUploaded })
    }

    @Test
    fun mergeWavelog_backfillsBothFrequenciesWithoutCreatingDuplicate() = runBlocking {
        val localId = dao.save(loggedInApp("SO-50", lotwUploaded = false)
            .copy(txFrequencyHz = null, rxFrequencyHz = null, comment = "local note").toEntity())
        val remote = loggedInApp("SO-50", lotwUploaded = false).copy(wavelogStation = "3")

        repository.mergeWavelog(listOf(remote))
        repository.mergeWavelog(listOf(remote))

        val row = dao.getAll().single()
        assertEquals(localId, row.id)
        assertEquals(145_850_000L, row.txFrequencyHz)
        assertEquals(436_795_000L, row.rxFrequencyHz)
        assertEquals("local note", row.comment)
        assertEquals("3", row.wavelogStation)
    }

    @Test
    fun mergeWavelog_keepsKnownLocalFrequenciesWhenServerOmitsThem() = runBlocking {
        dao.save(loggedInApp("SO-50", lotwUploaded = false).toEntity())

        repository.mergeWavelog(listOf(loggedInApp("SO-50", lotwUploaded = false)
            .copy(txFrequencyHz = null, rxFrequencyHz = null)))

        val row = dao.getAll().single()
        assertEquals(145_850_000L, row.txFrequencyHz)
        assertEquals(436_795_000L, row.rxFrequencyHz)
    }

    @Test
    fun mergeWavelog_doesNotCollapseTwoDistinctKnownFrequencies() = runBlocking {
        dao.save(loggedInApp("SO-50", lotwUploaded = false).toEntity())

        repository.mergeWavelog(listOf(loggedInApp("SO-50", lotwUploaded = false)
            .copy(txFrequencyHz = 145_900_000L)))

        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun mergeLoTW_backfillsMissingFrequenciesFromTheConfirmation() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = true)
            .copy(txFrequencyHz = null, rxFrequencyHz = null).toEntity())
        val confirmation = reportConfirmation("SO-50")
            .copy(txFrequencyHz = 145_850_000L, rxFrequencyHz = 436_795_000L)

        repository.mergeLoTW(listOf(confirmation))

        val row = dao.getAll().single()
        assertEquals(id, row.id)
        assertEquals(145_850_000L, row.txFrequencyHz)
        assertEquals(436_795_000L, row.rxFrequencyHz)
    }

    @Test
    fun mergeWavelog_insertsNewRecordsAlreadyMarkedAndStationTagged() = runBlocking {
        val result = repository.mergeWavelog(
            listOf(loggedInApp("SO-50", lotwUploaded = false).copy(wavelogStation = "2"))
        )

        assertEquals(1, result.imported)
        val row = dao.getAll().single()
        assertTrue(row.wavelogUploaded)
        assertEquals("2", row.wavelogStation)
        assertFalse(row.lotwUploaded)
    }

    @Test
    fun mergeWavelog_matchesALocalRowInsteadOfDuplicating() = runBlocking {
        val id = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = false).copy(wavelogUploaded = true).toEntity())

        repository.mergeWavelog(
            listOf(loggedInApp("SAUDISAT 1C", lotwUploaded = false).copy(wavelogStation = "1", theirGrid = "EN52"))
        )

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(id, rows.first().id)
        assertEquals("1", rows.first().wavelogStation)
        assertEquals("EN52", rows.first().theirGrid)
        // The row joins the ARRL spelling like every other import path.
        assertEquals("SO-50", rows.first().satelliteName)
    }

    @Test
    fun mergeLoTW_updatesExistingOwnGridOnMatchedConfirmation() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = true)
            .copy(myGrid = "OL62").toEntity())

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")
            .copy(myGrid = "OL62TI")))

        val row = dao.getAll().single()
        assertEquals(id, row.id)
        assertEquals("OL62TI", row.myGrid)
        assertTrue(row.lotwConfirmed)
    }

    @Test
    fun mergeWavelog_overwritesExistingOwnGridWithRemoteValue() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = false)
            .copy(myGrid = "OL62", comment = "local note").toEntity())
        val remote = loggedInApp("SO-50", lotwUploaded = false)
            .copy(myGrid = "OL62TI", comment = "server note", wavelogStation = "3")

        repository.mergeWavelog(listOf(remote))

        val row = dao.getAll().single()
        assertEquals(id, row.id)
        assertEquals("OL62TI", row.myGrid)
        assertEquals("local note", row.comment)
        assertEquals("3", row.wavelogStation)
    }

    @Test
    fun mergeWavelog_keepsOwnGridWhenRemoteHasNoGrid() = runBlocking {
        dao.save(loggedInApp("SO-50", lotwUploaded = false)
            .copy(myGrid = "OL62TI").toEntity())
        repository.mergeWavelog(listOf(loggedInApp("SO-50", lotwUploaded = false)
            .copy(myGrid = "", wavelogStation = "3")))

        assertEquals("OL62TI", dao.getAll().single().myGrid)
    }

    @Test
    fun mergeWavelog_isIdempotentAcrossSyncs() = runBlocking {
        val records = listOf(loggedInApp("SO-50", lotwUploaded = false).copy(wavelogStation = "1"))

        repository.mergeWavelog(records)
        repository.mergeWavelog(records)

        assertEquals(1, dao.getAll().size)
    }


    @Test
    fun savingAndMergingDoesNotLoseFullGridsOrIota() = runBlocking {
        val q = loggedInApp("SO-50", lotwUploaded = false).copy(
            theirGrid = "OI71OI42", myGrid = "OL62TI99",
            theirFullGrids = listOf("OI71OI42", "PI59PC55"),
            myFullGrids = listOf("OL62TI99", "OL61AT"),
            iota = "OC-022", myIota = "AS-129"
        )
        repository.save(q)
        val stored = dao.getAll().single()
        assertEquals("OI71OI42,PI59PC55", stored.theirFullGrids)
        assertEquals("OL62TI99,OL61AT", stored.myFullGrids)
        assertEquals("OC-022", stored.iota)
        assertEquals("AS-129", stored.myIota)
        repository.mergeWavelog(listOf(q.copy(
            wavelogStation = "3", theirFullGrids = listOf("PI79AD55")
        )))
        val merged = dao.getAll().single()
        assertEquals("OI71OI42,PI59PC55,PI79AD55", merged.theirFullGrids)
        assertEquals("OL62TI99,OL61AT", merged.myFullGrids)
        assertEquals("OC-022", merged.iota)
        assertEquals("AS-129", merged.myIota)
    }

    /** A record as the log page creates it: tracker name, repeater pair, uplink band. */
    private fun loggedInApp(satelliteName: String, lotwUploaded: Boolean) = QsoRecord(
        startUtcMillis = qsoStart,
        theirCallsign = "BG5JSB",
        myCallsign = "BA7OPF",
        txFrequencyHz = 145_850_000L,
        rxFrequencyHz = 436_795_000L,
        band = "2M",
        rxBand = "70CM",
        mode = "FM",
        satelliteName = satelliteName,
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE,
        lotwUploaded = lotwUploaded
    )

    /** A confirmation as the report parser + [toConfirmedRecord] build it (BAND carries the uplink). */
    private fun reportConfirmation(satName: String) = GridQso(
        call = "BG5JSB",
        epochMs = qsoStart,
        satName = satName,
        mode = "FM",
        bandUp = "2M",
        bandDown = "70CM",
        dxcc = 318,
        country = "China",
        cqz = 24,
        state = "GD",
        myGrid = "OL62",
        myGrids = setOf("OL62", "OL63"),
        theirGrids = listOf("EN52", "EN53")
    ).toConfirmedRecord("BA7OPF")

    private fun QsoRecord.toEntity() = QsoEntity(
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
        status = status.name,
        dedupeKey = listOf(startUtcMillis, theirCallsign, myCallsign, txFrequencyHz, mode, submode, satelliteName)
            .joinToString("|"),
        propagationMode = propagationMode,
        lotwConfirmed = lotwConfirmed,
        lotwUploaded = lotwUploaded,
        wavelogUploaded = wavelogUploaded,
        wavelogStation = wavelogStation,
        lotwReceived = lotwReceived,
        lotwQslDate = lotwQslDate,
        vuccGrids = vuccGrids.joinToString(","),
        theirVuccGrids = theirVuccGrids.joinToString(","),
        dxcc = dxcc,
        country = country,
        cqZone = cqZone,
        region = region,
        comment = comment
    )

    private class FakeQsoDao : QsoDao {
        private val rows = linkedMapOf<Long, QsoEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<QsoEntity>> = flowOf(rows.values.toList())

        override suspend fun find(id: Long): QsoEntity? = rows[id]

        override suspend fun getAll(): List<QsoEntity> = rows.values.toList()

        override suspend fun getDedupeKeys(): List<String> = rows.values.map { it.dedupeKey }

        override suspend fun save(record: QsoEntity): Long {
            val id = if (record.id == 0L) nextId++ else record.id
            rows[id] = record.copy(id = id)
            return id
        }

        override suspend fun importRecords(records: List<QsoEntity>): List<Long> = records.map { save(it) }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun markUploaded(ids: List<Long>) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(lotwUploaded = true) } }
        }

        override suspend fun markWavelogUploaded(ids: List<Long>, stationId: String) {
            ids.forEach { id ->
                rows[id]?.let {
                    rows[id] = it.copy(
                        wavelogUploaded = true,
                        wavelogStation = if (stationId.isBlank()) it.wavelogStation else stationId
                    )
                }
            }
        }
    }
}
