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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.GridQso
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.model.MarkedStation
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoTWGridSyncTest {

    // 2026-09-16T00:00:00Z — fixed "now" for the cursor assertions.
    private val now = 1_789_516_800_000L

    // region resolveLoTWSyncMode

    @Test
    fun resolveModeHonorsExplicitFull() {
        assertEquals(LoTWSyncMode.Full, resolveLoTWSyncMode("BA7OPF", "BA7OPF", LoTWSyncMode.Full))
        assertEquals(LoTWSyncMode.Full, resolveLoTWSyncMode("", "BA7OPF", LoTWSyncMode.Full))
    }

    @Test
    fun resolveModeAllowsIncrementalOnlyForSameCallsign() {
        assertEquals(
            LoTWSyncMode.Incremental,
            resolveLoTWSyncMode("BA7OPF", "BA7OPF", LoTWSyncMode.Incremental)
        )
        assertEquals(
            LoTWSyncMode.Full,
            resolveLoTWSyncMode("BG7ABC", "BA7OPF", LoTWSyncMode.Incremental)
        )
        assertEquals(
            LoTWSyncMode.Full,
            resolveLoTWSyncMode("", "BA7OPF", LoTWSyncMode.Incremental)
        )
    }

    @Test
    fun resolveModeForAutoSyncPicksIncrementalWhenPossible() {
        assertEquals(LoTWSyncMode.Incremental, resolveLoTWSyncMode("BA7OPF", "BA7OPF", null))
        assertEquals(LoTWSyncMode.Full, resolveLoTWSyncMode("BG7ABC", "BA7OPF", null))
        assertEquals(LoTWSyncMode.Full, resolveLoTWSyncMode("", "BA7OPF", null))
    }

    // endregion

    // region shouldAutoSyncLoTW

    @Test
    fun autoSyncGateRequiresEverything() {
        val today = "20260916"
        // Not configured.
        assertFalse(shouldAutoSyncLoTW(false, true, "2026-09-15 10:00:00", today))
        // LoTW auto-sync toggle off.
        assertFalse(shouldAutoSyncLoTW(true, false, "2026-09-15 10:00:00", today))
        // Never synced manually — first sync stays manual/full.
        assertFalse(shouldAutoSyncLoTW(true, true, "", today))
        // Already synced today (both legacy and current cursor formats) — ARRL rate-limit guard.
        assertFalse(shouldAutoSyncLoTW(true, true, "20260916", today))
        assertFalse(shouldAutoSyncLoTW(true, true, "2026-09-16 08:00:00", today))
        // All gates open — yesterday in either format.
        assertTrue(shouldAutoSyncLoTW(true, true, "20260915", today))
        assertTrue(shouldAutoSyncLoTW(true, true, "2026-09-15 10:00:00", today))
    }

    // endregion

    // region cursor helpers

    @Test
    fun cursorApiNormalizesLegacyAndPassesFullThrough() {
        assertEquals("2026-09-16", lotwCursorApi("20260916"))
        assertEquals("2026-09-16 08:16:02", lotwCursorApi("2026-09-16 08:16:02"))
        assertEquals("", lotwCursorApi(""))
    }

    @Test
    fun cursorDateToleratesBothFormats() {
        assertEquals("20260916", lotwCursorDate("20260916"))
        assertEquals("20260916", lotwCursorDate("2026-09-16 08:16:02"))
        assertEquals("", lotwCursorDate(""))
    }

    @Test
    fun cursorEpochMsParsesBothFormatsUtc() {
        // 2026-09-16T00:00:00Z in both spellings.
        assertEquals(now, lotwCursorEpochMs("20260916"))
        assertEquals(now, lotwCursorEpochMs("2026-09-16 00:00:00"))
        assertEquals(0L, lotwCursorEpochMs(""))
        assertEquals(0L, lotwCursorEpochMs("garbage"))
    }

    // endregion

    // region mergeGridQsos

    @Test
    fun mergeGridQsosDedupsByCallAndTime() {
        val existing = mapOf(
            "OL62" to listOf(qso("BA7ABC", 1000L)),
            "OL63" to listOf(qso("BA7DEF", 2000L))
        )
        val fresh = mapOf(
            // Same grid, same QSO re-delivered by an overlapping since-date.
            "OL62" to listOf(qso("BA7ABC", 1000L)),
            // Same grid, brand-new QSO.
            "OL62" to listOf(qso("BA7GHI", 3000L)),
            // New grid entirely.
            "PM95" to listOf(qso("BA7JKL", 4000L))
        )
        val merged = mergeGridQsos(existing, fresh)
        assertEquals(setOf("OL62", "OL63", "PM95"), merged.keys)
        assertEquals(2, merged["OL62"]?.size) // BA7ABC once, BA7GHI added
        assertEquals(1, merged["OL63"]?.size)
        assertEquals(1, merged["PM95"]?.size)
    }

    @Test
    fun mergeGridQsosHandlesEmptySides() {
        val fresh = mapOf("OL62" to listOf(qso("BA7ABC", 1000L)))
        assertEquals(fresh, mergeGridQsos(emptyMap(), fresh))
        // An empty incremental pull must leave the stored side untouched.
        assertEquals(fresh, mergeGridQsos(fresh, emptyMap()))
        assertEquals(emptyMap<String, List<GridQso>>(), mergeGridQsos(emptyMap(), emptyMap()))
    }

    @Test
    fun mergeCorrectedStationLocationReplacesPriorCopies() {
        val old = qso("BA7ABC", 1000L).copy(
            myGrid = "OL62", myGrids = setOf("OL62"),
            stationKey = "old", theirGrids = listOf("PM95", "PM96")
        )
        val fixed = old.copy(
            myGrid = "OL63", myGrids = setOf("OL63", "OL64"),
            stationKey = "new", theirGrids = listOf("PM96")
        )
        val other = qso("BA7DEF", 2000L).copy(myGrid = "OL62")
        val merged = mergeGridQsos(
            mapOf("PM95" to listOf(old, other), "PM96" to listOf(old)),
            mapOf("PM96" to listOf(fixed))
        )
        assertEquals(listOf(other), merged["PM95"])
        assertEquals(listOf(fixed), merged["PM96"])
    }

    @Test
    fun mergeKeepsOtherSatelliteAtSameCallAndTime() {
        val old = qso("BA7ABC", 1000L)
        val other = old.copy(satName = "SO-50")
        val fixed = old.copy(myGrid = "OL63", myGrids = setOf("OL63"))
        val merged = mergeGridQsos(
            mapOf("PM95" to listOf(old, other)),
            mapOf("PM95" to listOf(fixed))
        )
        assertEquals(listOf(other, fixed), merged["PM95"])
    }

    // endregion

    // region applyLoTWGridResult

    @Test
    fun applyIncrementalMergesAndAdvancesCursor() = runBlocking {
        val repo = FakeSettingsRepo().apply {
            setWorkedGrids(setOf("OL62"))
            setWorkedGridQsos(mapOf("OL62" to listOf(qso("BA7ABC", 1000L))))
            setRoamedGrids(setOf("OL62"))
            setLastLotwSyncDate("20260915")
            setLastLotwSyncCallsign("BA7OPF")
        }
        val result = LoTWResult.Success(
            grids = setOf("OL62", "PM95"),
            qsos = mapOf("OL62" to listOf(qso("BA7ABC", 1000L)), "PM95" to listOf(qso("BA7GHI", 2000L))),
            roamedGrids = setOf("PM95")
        )
        val count = applyLoTWGridResult(repo, result, LoTWSyncMode.Incremental, "BA7OPF", now)

        assertEquals(2, count)
        assertEquals(setOf("OL62", "PM95"), repo.getWorkedGrids())
        assertEquals(1, repo.getWorkedGridQsos()["OL62"]?.size) // deduped
        assertEquals(setOf("OL62", "PM95"), repo.getRoamedGrids())
        assertEquals("2026-09-16 00:00:00", repo.getLastLotwSyncDate())
        assertEquals("BA7OPF", repo.getLastLotwSyncCallsign())
    }

    @Test
    fun applyIncrementalCorrectsWorkedAndRoamedGrids() = runBlocking {
        val old = qso("BA7ABC", 1000L).copy(
            myGrid = "OL62", myGrids = setOf("OL62"), theirGrids = listOf("PM95")
        )
        val fixed = old.copy(
            myGrid = "OL63", myGrids = setOf("OL63", "OL64"),
            theirGrids = listOf("PM96")
        )
        val repo = FakeSettingsRepo().apply {
            setWorkedGrids(setOf("PM95"))
            setWorkedGridQsos(mapOf("PM95" to listOf(old)))
            setRoamedGrids(setOf("OL62"))
            setLastLotwSyncDate("2026-10-19 11:00:00")
            setLastLotwQslCursor("2026-10-19 10:00:00")
        }
        val report = LoTWResult.Success(
            setOf("PM96"), mapOf("PM96" to listOf(fixed)),
            setOf("OL63", "OL64"), "2026-10-20 10:00:00"
        )
        applyLoTWGridResult(repo, report, LoTWSyncMode.Incremental, "BA7OPF", now)
        assertEquals(setOf("PM96"), repo.getWorkedGrids())
        assertEquals(setOf("PM96"), repo.getWorkedGridQsos().keys)
        assertEquals(setOf("OL63", "OL64"), repo.getRoamedGrids())
        assertEquals("2026-10-20 10:00:00", lotwIncrementalSince(repo))
        assertEquals(lotwSyncCursor(now), repo.getLastLotwSyncDate())
    }

    @Test
    fun emptyIncrementalPreservesQslCursor() = runBlocking {
        val repo = FakeSettingsRepo().apply {
            setLastLotwSyncDate("2026-10-19 11:00:00")
            setLastLotwQslCursor("2026-10-19 10:00:00")
        }
        applyLoTWGridResult(repo, LoTWResult.Success(emptySet(), emptyMap()),
            LoTWSyncMode.Incremental, "BA7OPF", now)
        assertEquals("2026-10-19 10:00:00", repo.getLastLotwQslCursor())
        assertEquals(lotwSyncCursor(now), repo.getLastLotwSyncDate())
    }

    @Test
    fun applyFullReplacesEverythingAndAdvancesCursor() = runBlocking {
        val repo = FakeSettingsRepo().apply {
            setWorkedGrids(setOf("OL62", "STALE"))
            setWorkedGridQsos(mapOf("STALE" to listOf(qso("BG7OLD", 1L))))
            setRoamedGrids(setOf("STALE"))
            setLastLotwSyncDate("20260910")
            setLastLotwSyncCallsign("BG7OLD")
        }
        val result = LoTWResult.Success(
            grids = setOf("PM95"),
            qsos = mapOf("PM95" to listOf(qso("BA7GHI", 2000L))),
            roamedGrids = setOf("PM95")
        )
        val count = applyLoTWGridResult(repo, result, LoTWSyncMode.Full, "BA7OPF", now)

        assertEquals(1, count)
        assertEquals(setOf("PM95"), repo.getWorkedGrids())
        assertEquals(setOf("PM95"), repo.getWorkedGridQsos().keys)
        assertEquals(setOf("PM95"), repo.getRoamedGrids())
        assertEquals("2026-09-16 00:00:00", repo.getLastLotwSyncDate())
        assertEquals("BA7OPF", repo.getLastLotwSyncCallsign())
    }

    // endregion

    private fun qso(call: String, epochMs: Long): GridQso =
        GridQso(call, epochMs, "FO-29", "FM", "70CM", "2M")

    /**
     * Minimal ISettingsRepo fake backing only the LoTW sync surface; the rest
     * throws (mirrors the FakeSettingsRepo pattern used in feature tests).
     */
    private class FakeSettingsRepo : ISettingsRepo {
        override val stationPosition: StateFlow<GeoPos> = MutableStateFlow(GeoPos(23.13, 113.26))
        override val appVersionName: String = "test"
        override val selectedIds: StateFlow<List<Int>> = MutableStateFlow(emptyList())
        override val selectedTypes: StateFlow<List<String>> = MutableStateFlow(emptyList())
        override val passesSettings: StateFlow<PassesSettings> = MutableStateFlow(
            PassesSettings(hoursAhead = 24, minElevation = 10.0, selectedModes = emptyList())
        )
        override val databaseState: StateFlow<DatabaseState> = MutableStateFlow(DatabaseState(0, 0, 0L))
        override val rcSettings: StateFlow<RCSettings> = MutableStateFlow(
            RCSettings(false, "", "", "", false, "", "", "", 0L, false, "", "", "", false, "", "")
        )
        override val otherSettings: StateFlow<OtherSettings> = MutableStateFlow(
            OtherSettings(
                stateOfAutoUpdate = true,
                stateOfSensors = false,
                stateOfSweep = false,
                stateOfUtc = false,
                stateOfLightTheme = false,
                stateOfNightMode = false,
                stateOfMapGrid = false,
                shouldSeeWarning = false,
                shouldSeeWhatsNew = false
            )
        )
        override val dataSourcesSettings: StateFlow<DataSourcesSettings> = MutableStateFlow(
            DataSourcesSettings(satelliteUrls = emptyList(), transceiversUrls = emptyList())
        )
        override val dataSourcesStatus: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
        override val radioControlSettings: StateFlow<RadioControlSettings> = MutableStateFlow(
            RadioControlSettings(false, RadioControlSettings.MODEL_YAESU_FT817, "", "", "", "", 9600)
        )

        override val wavelogUploadSettings: StateFlow<WavelogUploadSettings> = MutableStateFlow(WavelogUploadSettings())
        override val lotwSettings: StateFlow<LoTWSettings> = MutableStateFlow(LoTWSettings())

        private var workedGrids: Set<String> = emptySet()
        private var workedGridQsos: Map<String, List<GridQso>> = emptyMap()
        private var roamedGrids: Set<String> = emptySet()
        private var lastSyncDate: String = ""
        private var lastSyncCallsign: String = ""
        private var lastQslCursor: String = ""

        override fun getWorkedGrids(): Set<String> = workedGrids
        override fun setWorkedGrids(grids: Set<String>) { workedGrids = grids }
        override fun getWorkedGridQsos(): Map<String, List<GridQso>> = workedGridQsos
        override fun setWorkedGridQsos(qsos: Map<String, List<GridQso>>) { workedGridQsos = qsos }
        override fun getRoamedGrids(): Set<String> = roamedGrids
        override fun setRoamedGrids(grids: Set<String>) { roamedGrids = grids }
        private var vuccSelectedStationIds: Set<String> = emptySet()
        override fun getVuccSelectedStationIds(): Set<String> = vuccSelectedStationIds
        override fun setVuccSelectedStationIds(ids: Set<String>) { vuccSelectedStationIds = ids }
        private var markedGridStations: Map<String, List<MarkedStation>> = emptyMap()
        override fun getMarkedGridStations(): Map<String, List<MarkedStation>> = markedGridStations
        override fun setMarkedGridStations(stations: Map<String, List<MarkedStation>>) { markedGridStations = stations }
        override fun getLastLotwSyncDate(): String = lastSyncDate
        override fun getLastLotwQslCursor(): String = lastQslCursor
        override fun setLastLotwQslCursor(cursor: String) { lastQslCursor = cursor }
        override fun setLastLotwSyncDate(date: String) { lastSyncDate = date }
        override fun getLastLotwSyncCallsign(): String = lastSyncCallsign
        override fun setLastLotwSyncCallsign(callsign: String) { lastSyncCallsign = callsign }

        private var wavelogStations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList()
        private var wavelogSyncCursors: Map<String, Long> = emptyMap()
        private var wavelogSyncUrl: String = ""
        private var lastWavelogSyncEpochMs: Long = 0L
        override fun getWavelogStations(): List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = wavelogStations
        override fun setWavelogStations(stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>) { wavelogStations = stations }
        override fun getWavelogSyncCursors(): Map<String, Long> = wavelogSyncCursors
        override fun setWavelogSyncCursors(cursors: Map<String, Long>) { wavelogSyncCursors = cursors }
        override fun getWavelogSyncUrl(): String = wavelogSyncUrl
        override fun setWavelogSyncUrl(url: String) { wavelogSyncUrl = url }
        override fun getLastWavelogSyncEpochMs(): Long = lastWavelogSyncEpochMs
        override fun setLastWavelogSyncEpochMs(value: Long) { lastWavelogSyncEpochMs = value }
        override fun getLastWavelogFullSyncEpochMs(): Long = 0L
        override fun setLastWavelogFullSyncEpochMs(value: Long) = Unit

        override fun setSelectedIds(ids: List<Int>) = TODO()
        override fun setSelectedTypes(types: List<String>) = TODO()
        override fun setPassesSettings(settings: PassesSettings) = TODO()
        override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean = TODO()
        override fun setStationPosition(): Boolean = TODO()
        override fun setStationPosition(locator: String): Boolean = TODO()
        override fun getCurrentGrid(): String? = TODO()
        override fun getSatelliteTypesIds(types: List<String>): List<Int> = TODO()
        override fun setSatelliteTypeIds(type: String, ids: List<Int>) = TODO()
        override fun updateDatabaseState(state: DatabaseState) = TODO()
        override fun updateRCSettings(settings: RCSettings) = TODO()
        override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) = TODO()
        override fun updateDataSourcesSettings(settings: DataSourcesSettings) = TODO()
        override fun updateDataSourcesStatus(status: Map<String, Int>) = TODO()
        override fun updateRadioControlSettings(settings: RadioControlSettings) = TODO()
        override fun getSatelliteOffset(catnum: Int): String = ""
        override fun getSatelliteMode(catnum: Int): String = ""
        override fun setSatelliteMode(catnum: Int, mode: String) {}
        override fun setSatelliteOffset(catnum: Int, offset: String) = TODO()
        override fun getAmSatCallsign(): String = ""
        override fun setAmSatCallsign(callsign: String) = TODO()

        override fun updateWavelogUploadSettings(settings: WavelogUploadSettings) = TODO()
        override fun updateLoTWSettings(settings: LoTWSettings) = TODO()
    }
}
