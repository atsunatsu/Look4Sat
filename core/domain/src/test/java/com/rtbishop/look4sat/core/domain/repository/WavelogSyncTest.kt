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
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.GridQso
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.model.MarkedStation
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogSyncTest {

    // region # resolveWavelogSyncMode

    @Test
    fun fullRequestIsAlwaysHonored() {
        assertEquals(WavelogSyncMode.Full, resolveWavelogSyncMode("", "http://a", WavelogSyncMode.Full))
        assertEquals(WavelogSyncMode.Full, resolveWavelogSyncMode("http://a", "http://a", WavelogSyncMode.Full))
    }

    @Test
    fun incrementalIsHonoredOnlyOnTheSameServer() {
        assertEquals(
            WavelogSyncMode.Incremental,
            resolveWavelogSyncMode("http://a", "http://a/", WavelogSyncMode.Incremental)
        )
        assertEquals(WavelogSyncMode.Full, resolveWavelogSyncMode("", "http://a", WavelogSyncMode.Incremental))
        assertEquals(
            WavelogSyncMode.Full,
            resolveWavelogSyncMode("http://old", "http://a", WavelogSyncMode.Incremental)
        )
    }

    @Test
    fun urlNormalizationTrimsWhitespaceAndSlashes() {
        assertEquals("http://a/wavelog", normalizeWavelogUrl(" http://a/wavelog/ "))
    }

    // endregion

    // region # buildWavelogGridData

    @Test
    fun gridDataCollectsTheirGridsAndRoamedOwnGrids() {
        val data = buildWavelogGridData(
            listOf(
                record(start = 1_000L, theirGrid = "EN52", myGrid = "OL62"),
                record(start = 2_000L, theirGrid = "EN53XX", myGrid = "OL62TI")
            )
        )
        assertEquals(setOf("EN52", "EN53"), data.grids)
        assertEquals(setOf("OL62"), data.roamedGrids)
        assertEquals(1, data.gridQsos.getValue("EN52").size)
        assertEquals(1, data.gridQsos.getValue("EN53").size)
    }

    @Test
    fun gridDataSpreadsAMultiGridQsoAcrossItsGrids() {
        val data = buildWavelogGridData(
            listOf(record(start = 1_000L, theirGrid = "EN52", theirVucc = listOf("EN52", "EN53")))
        )
        assertEquals(setOf("EN52", "EN53"), data.grids)
        assertEquals(1, data.gridQsos.getValue("EN52").size)
        assertEquals(1, data.gridQsos.getValue("EN53").size)
        // The same QSO object is shared, so its detail matches from either grid.
        assertEquals(data.gridQsos.getValue("EN52").single(), data.gridQsos.getValue("EN53").single())
    }

    @Test
    fun gridDataIncludesPrimaryGridWhenMultiGridFieldContainsOnlyAdditionalSquares() {
        val qso = record(
            start = 1_000L,
            theirGrid = "EN52",
            theirVucc = listOf("EN53"),
            myGrid = "OL62"
        ).copy(vuccGrids = listOf("OL63"))

        val data = buildWavelogGridData(listOf(qso))
        assertEquals(setOf("EN52", "EN53"), data.grids)
        assertEquals(setOf("OL62", "OL63"), data.roamedGrids)
        assertEquals(listOf("EN52", "EN53"), data.gridQsos.getValue("EN52").single().theirGrids)
        assertEquals(setOf("OL62", "OL63"), data.gridQsos.getValue("EN52").single().myGrids)
        assertEquals(1, data.gridQsos.getValue("EN52").size)
        assertEquals(1, data.gridQsos.getValue("EN53").size)
    }

    @Test
    fun gridDataMergesDuplicateLocatorsWithoutCountingOwnSquaresAsWorked() {
        val qso = record(
            start = 1_000L,
            theirGrid = "EN52EN",
            theirVucc = listOf("EN52", "en53fa", "ZZ99"),
            myGrid = "OL62TI"
        ).copy(vuccGrids = listOf("OL62", "ol63ti", "XX99"))

        val data = buildWavelogGridData(listOf(qso))
        assertEquals(setOf("EN52", "EN53"), data.grids)
        assertEquals(setOf("OL62", "OL63"), data.roamedGrids)
        assertEquals(1, data.gridQsos.getValue("EN52").size)
        assertEquals(1, data.gridQsos.getValue("EN53").size)
    }

    @Test
    fun gridDataIgnoresNonSatelliteRecordsAndBlankGrids() {
        val data = buildWavelogGridData(
            listOf(
                record(start = 1L, theirGrid = "", sat = ""),
                record(start = 2L, theirGrid = "XX99", sat = "SO-50")
            )
        )
        assertTrue(data.grids.isEmpty())
        assertTrue(data.roamedGrids.isEmpty())
        assertTrue(data.gridQsos.isEmpty())
    }

    @Test
    fun gridQsoCarriesTheFullRecordDetails() {
        val data = buildWavelogGridData(listOf(record(start = 1_000L, theirGrid = "EN52", myGrid = "OL62TI")))
        val qso = data.gridQsos.getValue("EN52").single()
        assertEquals("BG5JVM", qso.call)
        assertEquals(1_000L, qso.epochMs)
        assertEquals("SO-50", qso.satName)
        assertEquals("FM", qso.mode)
        assertEquals("2M", qso.bandUp)
        assertEquals("70CM", qso.bandDown)
        assertEquals("OL62", qso.myGrid)
        assertEquals(setOf("OL62"), qso.myGrids)
        assertEquals(listOf("EN52"), qso.theirGrids)
    }

    @Test
    fun wavelogConfirmedGridCarriesFineLocatorAndIotaForWaipa() {
        val rec = record(start = 2_000L, theirGrid = "PI59PC")
            .copy(theirCallsign = "YB9XYZ", dxcc = 327, iota = "OC-177")
        val data = buildWavelogGridData(listOf(rec))
        val qso = data.gridQsos.getValue("PI59").single()
        assertEquals(listOf("PI59PC"), qso.theirFineGrids)
        assertEquals("OC-177", qso.iota)
        assertEquals(327, qso.dxcc)
    }


    @Test
    fun wavelogMapRetainsFullLocatorsAndIotaIndependentlyOfVuccKeys() {
        val imported = record(start = 1_000L, theirGrid = "OI71OI42")
            .copy(
                theirFullGrids = listOf("OI71OI42", "PI59PC55", "PI79AD"),
                myFullGrids = listOf("OL62TI99", "OL61AT"),
                theirVuccGrids = listOf("OI71", "PI59", "PI79"),
                iota = "OC-022"
            )
        val data = buildWavelogGridData(listOf(imported))
        val q = data.gridQsos.getValue("OI71").single()
        assertEquals(setOf("OI71", "PI59", "PI79"), data.grids)
        assertEquals(listOf("OI71OI42", "PI59PC55", "PI79AD"), q.theirFineGrids)
        assertEquals(listOf("OL62TI99", "OL61AT"), q.myFineGrids)
        assertEquals("OC-022", q.iota)
    }

    @Test
    fun gridDataSkipsUnconfirmedContactsButKeepsTheRoamedGrid() {
        val data = buildWavelogGridData(
            listOf(
                record(start = 1_000L, theirGrid = "EN52", myGrid = "OL62", confirmed = false),
                record(start = 2_000L, theirGrid = "EN53", myGrid = "OL63", confirmed = false)
            )
        )
        // Wavelog fills the opposite station's grid in from a callsign lookup; an unconfirmed
        // contact must not turn a square green even though the grid is there.
        assertTrue(data.grids.isEmpty())
        assertTrue(data.gridQsos.isEmpty())
        // Where the operator was is not something the opposite station confirms.
        assertEquals(setOf("OL62", "OL63"), data.roamedGrids)
    }

    @Test
    fun gridDataAcceptsPaperAndEqslConfirmations() {
        val data = buildWavelogGridData(
            listOf(
                record(start = 1_000L, theirGrid = "EN52", confirmed = false, paper = true),
                record(start = 2_000L, theirGrid = "EN53", confirmed = false, eqsl = true)
            )
        )
        assertEquals(setOf("EN52", "EN53"), data.grids)
        assertEquals(1, data.gridQsos.getValue("EN52").size)
        assertEquals(1, data.gridQsos.getValue("EN53").size)
    }

    // endregion

    // region # applyWavelogSyncResult

    @Test
    fun applySyncMergesGridsAndAdvancesBookkeeping() = runBlocking {
        val settings = FakeSettings()
        settings.setWorkedGrids(setOf("PM01"))

        val count = applyWavelogSyncResult(
            settings,
            listOf(record(start = 1_000L, theirGrid = "EN52", myGrid = "OL62")),
            mapOf("1" to 500L),
            "http://a/wavelog/",
            WavelogSyncMode.Incremental,
            now = 42L
        )

        assertEquals(setOf("PM01", "EN52"), settings.getWorkedGrids())
        assertEquals(setOf("OL62"), settings.getRoamedGrids())
        assertEquals(setOf("EN52"), settings.getWorkedGridQsos().keys)
        assertEquals(mapOf("1" to 500L), settings.getWavelogSyncCursors())
        assertEquals("http://a/wavelog", settings.getWavelogSyncUrl())
        assertEquals(42L, settings.getLastWavelogSyncEpochMs())
        assertEquals(0L, settings.getLastWavelogFullSyncEpochMs())
        assertEquals(2, count)
    }

    @Test
    fun applySyncNeverShrinksStoredData() = runBlocking {
        val settings = FakeSettings()
        settings.setWorkedGrids(setOf("PM01"))
        settings.setRoamedGrids(setOf("OM60"))

        applyWavelogSyncResult(
            settings, listOf(record(1_000L, "EN52", myGrid = "OL62")), mapOf("1" to 10L), "http://a",
            WavelogSyncMode.Incremental
        )
        applyWavelogSyncResult(
            settings, listOf(record(2_000L, "EN53", myGrid = "OL63")), mapOf("1" to 20L), "http://a",
            WavelogSyncMode.Incremental
        )

        assertEquals(setOf("PM01", "EN52", "EN53"), settings.getWorkedGrids())
        assertEquals(setOf("OM60", "OL62", "OL63"), settings.getRoamedGrids())
        assertEquals(mapOf("1" to 20L), settings.getWavelogSyncCursors())
    }

    @Test
    fun fullSyncReplacesTheStoredSets() = runBlocking {
        val settings = FakeSettings()
        // A square kept from before the confirmation rule existed, plus one that is real now.
        settings.setWorkedGrids(setOf("PM01", "EN52"))
        settings.setWorkedGridQsos(mapOf("PM01" to emptyList()))
        settings.setRoamedGrids(setOf("OM60", "OL62"))

        val count = applyWavelogSyncResult(
            settings,
            listOf(record(1_000L, "EN52", myGrid = "OL62")),
            mapOf("1" to 10L),
            "http://a",
            WavelogSyncMode.Full,
            now = 99L
        )

        assertEquals(setOf("EN52"), settings.getWorkedGrids())
        assertEquals(setOf("EN52"), settings.getWorkedGridQsos().keys)
        assertEquals(setOf("OL62"), settings.getRoamedGrids())
        assertEquals(99L, settings.getLastWavelogFullSyncEpochMs())
        assertEquals(1, count)
    }

    @Test
    fun fullSyncWithAFailedStationDegradesToMerge() = runBlocking {
        val settings = FakeSettings()
        settings.setWorkedGrids(setOf("PM01"))

        applyWavelogSyncResult(
            settings,
            listOf(record(1_000L, "EN52")),
            mapOf("1" to 10L),
            "http://a",
            WavelogSyncMode.Full,
            failedStations = setOf("2"),
            now = 99L
        )

        // Partial pull: never wipe what the station that failed contributed.
        assertEquals(setOf("PM01", "EN52"), settings.getWorkedGrids())
        assertEquals(0L, settings.getLastWavelogFullSyncEpochMs())
    }

    @Test
    fun incrementalRequestIsPromotedOnceTheLastFullScanIsStale() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(shouldPromoteWavelogFullSync(lastFullSyncEpochMs = 0L, now = day))
        assertTrue(shouldPromoteWavelogFullSync(lastFullSyncEpochMs = 0L, now = 1000L * day))
        assertEquals(
            false,
            shouldPromoteWavelogFullSync(lastFullSyncEpochMs = 10 * day, now = 12 * day)
        )
        assertTrue(shouldPromoteWavelogFullSync(lastFullSyncEpochMs = 10 * day, now = 13 * day))
    }

    // endregion

    private fun record(
        start: Long,
        theirGrid: String = "",
        theirVucc: List<String> = emptyList(),
        myGrid: String = "OL62TI",
        sat: String = "SO-50",
        confirmed: Boolean = true,
        paper: Boolean = false,
        eqsl: Boolean = false
    ) = QsoRecord(
        startUtcMillis = start,
        theirCallsign = "BG5JVM",
        myCallsign = "BA7OPF",
        theirGrid = theirGrid,
        theirVuccGrids = theirVucc,
        myGrid = myGrid,
        txFrequencyHz = 145_850_000L,
        rxFrequencyHz = 436_795_000L,
        band = "2M",
        rxBand = "70CM",
        mode = "FM",
        satelliteName = sat,
        propagationMode = if (sat.isNotBlank()) "SAT" else "",
        status = QsoStatus.COMPLETE,
        lotwConfirmed = confirmed,
        qslConfirmed = paper,
        eqslConfirmed = eqsl
    )

    private class FakeSettings : ISettingsRepo {
        private var workedGrids: Set<String> = emptySet()
        private var workedGridQsos: Map<String, List<GridQso>> = emptyMap()
        private var roamedGrids: Set<String> = emptySet()
        private var cursors: Map<String, Long> = emptyMap()
        private var syncUrl: String = ""
        private var lastSync: Long = 0L
        private var lastFullSync: Long = 0L

        override fun getWorkedGrids(): Set<String> = workedGrids
        override fun setWorkedGrids(grids: Set<String>) { workedGrids = grids }
        override fun getWorkedGridQsos(): Map<String, List<GridQso>> = workedGridQsos
        override fun setWorkedGridQsos(qsos: Map<String, List<GridQso>>) { workedGridQsos = qsos }
        override fun getRoamedGrids(): Set<String> = roamedGrids
        override fun setRoamedGrids(grids: Set<String>) { roamedGrids = grids }
        override fun getVuccSelectedStationIds(): Set<String> = emptySet()
        override fun setVuccSelectedStationIds(ids: Set<String>) = Unit
        override fun getWavelogSyncCursors(): Map<String, Long> = cursors
        override fun setWavelogSyncCursors(cursors: Map<String, Long>) { this.cursors = cursors }
        override fun getWavelogSyncUrl(): String = syncUrl
        override fun setWavelogSyncUrl(url: String) { syncUrl = url }
        override fun getLastWavelogSyncEpochMs(): Long = lastSync
        override fun setLastWavelogSyncEpochMs(value: Long) { lastSync = value }
        override fun getLastWavelogFullSyncEpochMs(): Long = lastFullSync
        override fun setLastWavelogFullSyncEpochMs(value: Long) { lastFullSync = value }
        override fun getLastWavelogConfirmationSyncEpochMs(): Long = 0L
        override fun setLastWavelogConfirmationSyncEpochMs(value: Long) = Unit

        override val appVersionName: String get() = TODO()
        override val selectedIds: StateFlow<List<Int>> get() = TODO()
        override val selectedTypes: StateFlow<List<String>> get() = TODO()
        override fun setSelectedIds(ids: List<Int>) = TODO()
        override fun setSelectedTypes(types: List<String>) = TODO()
        override val passesSettings: StateFlow<PassesSettings> get() = TODO()
        override fun setPassesSettings(settings: PassesSettings) = TODO()
        override val stationPosition: StateFlow<GeoPos> get() = TODO()
        override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean = TODO()
        override fun setStationPosition(): Boolean = TODO()
        override fun setStationPosition(locator: String): Boolean = TODO()
        override fun getCurrentGrid(): String? = TODO()
        override val databaseState: StateFlow<DatabaseState> get() = TODO()
        override fun getSatelliteTypesIds(types: List<String>): List<Int> = TODO()
        override fun setSatelliteTypeIds(type: String, ids: List<Int>) = TODO()
        override fun updateDatabaseState(state: DatabaseState) = TODO()
        override val rcSettings: StateFlow<RCSettings> get() = TODO()
        override fun updateRCSettings(settings: RCSettings) = TODO()
        override val otherSettings: StateFlow<OtherSettings> get() = TODO()
        override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) = TODO()
        override val dataSourcesSettings: StateFlow<DataSourcesSettings> get() = TODO()
        override fun updateDataSourcesSettings(settings: DataSourcesSettings) = TODO()
        override val dataSourcesStatus: StateFlow<Map<String, Int>> get() = TODO()
        override fun updateDataSourcesStatus(status: Map<String, Int>) = TODO()
        override val radioControlSettings: StateFlow<RadioControlSettings> get() = TODO()
        override fun updateRadioControlSettings(settings: RadioControlSettings) = TODO()
        override fun getSatelliteOffset(catnum: Int): String = TODO()
        override fun setSatelliteOffset(catnum: Int, offset: String) = TODO()
        override fun getSatelliteMode(catnum: Int): String = TODO()
        override fun setSatelliteMode(catnum: Int, mode: String) = TODO()
        override fun getAmSatCallsign(): String = TODO()
        override fun setAmSatCallsign(callsign: String) = TODO()

        override val wavelogUploadSettings: StateFlow<WavelogUploadSettings> get() = TODO()
        override fun updateWavelogUploadSettings(settings: WavelogUploadSettings) = TODO()
        override fun getWavelogStations(): List<WavelogStationInfo> = emptyList()
        override fun setWavelogStations(stations: List<WavelogStationInfo>) = TODO()
        override fun getMarkedGridStations(): Map<String, List<MarkedStation>> = TODO()
        override fun setMarkedGridStations(stations: Map<String, List<MarkedStation>>) = TODO()
        override val lotwSettings: StateFlow<LoTWSettings> get() = TODO()
        override fun updateLoTWSettings(settings: LoTWSettings) = TODO()
        override fun getLastLotwSyncDate(): String = TODO()
        override fun setLastLotwSyncDate(date: String) = TODO()
        override fun getLastLotwSyncCallsign(): String = TODO()
        override fun setLastLotwSyncCallsign(callsign: String) = TODO()
    }
}
