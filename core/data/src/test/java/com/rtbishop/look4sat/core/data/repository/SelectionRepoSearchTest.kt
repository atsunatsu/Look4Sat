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

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.repository.ISelectionRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionRepoSearchTest {

    private val sampleItems = listOf(
        SatItem(catnum = 25544, name = "ISS (ZARYA)"),
        SatItem(catnum = 7530, name = "AO-7 (AMSAT-OSCAR 7)"),
        SatItem(catnum = 39444, name = "AO-73 (FUNcube-1)"),
        SatItem(catnum = 43803, name = "JO-97 (BIRDS-3)"),
        SatItem(catnum = 99999, name = "FO-29"),
    )

    @Test
    fun `query without separators matches name with dashes spaces brackets`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("ao7")
        val results = repo.getEntriesFlow().first()
        // "ao7" is a substring of the normalized "AO-73 (FUNcube-1)" too, so a
        // fuzzy search legitimately returns both AO-7 (first) and AO-73. The
        // key guarantee is that AO-7 — which was unreachable before because of
        // the dashes/brackets — is now found.
        assertTrue(results.map { it.catnum }.contains(7530))
        assertEquals(7530, results.first().catnum)
    }

    @Test
    fun `query without separators matches name with only dashes`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("fo29")
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(99999), results.map { it.catnum })
    }

    @Test
    fun `space-separated tokens all must match in any order`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("zarya iss")
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(25544), results.map { it.catnum })
    }

    @Test
    fun `exact name query still matches`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("AO-7 (AMSAT-OSCAR 7)")
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(7530), results.map { it.catnum })
    }

    @Test
    fun `partial token query matches substring`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("funcube")
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(39444), results.map { it.catnum })
    }

    @Test
    fun `numeric query matches catnum exactly`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("25544")
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(25544), results.map { it.catnum })
    }

    @Test
    fun `no query returns all items`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("")
        val results = repo.getEntriesFlow().first()
        assertEquals(sampleItems.size, results.size)
    }

    @Test
    fun `unmatched query returns nothing`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setQuery("zzzznomatch")
        assertTrue(repo.getEntriesFlow().first().isEmpty())
    }

    @Test
    fun `FM virtual type shows only hardcoded FM satellites`() = runTest {
        // AO-123 ASRTU-1 (61781) is on the hardcoded FM list; a random
        // satellite (99999) is not.
        val items = sampleItems + SatItem(catnum = 61781, name = "ASRTU-1")
        val repo = createRepo(items)
        repo.setTypes(listOf("AMSAT Live FM"))
        val results = repo.getEntriesFlow().first()
        assertEquals(setOf(25544, 61781), results.map { it.catnum }.toSet())
    }

    @Test
    fun `FM virtual type includes the BA7OPF RS95S UNNE-1B and IO-86 additions`() = runTest {
        // RS95S / QMR-KWT-2 (67291), UNNE-1B (98205) and IO-86 / LAPAN-A2 (40931)
        // joined the hardcoded AMSAT Live FM set on 2026-10-09; each carries a V/U
        // FM repeater in SatNOGS ("V/U FM Transponder CTCSS 67.0 Hz",
        // "Mode V/U - FM Repeater (Voice + (A)FSK)", "V/U FM Voice (PL 88.5Hz)").
        // sampleItems contributes ISS (25544) only.
        val items = sampleItems + listOf(
            SatItem(catnum = 67291, name = "QMR-KWT-2 (RS95S)"),
            SatItem(catnum = 98205, name = "UNNE-1B"),
            SatItem(catnum = 40931, name = "LAPAN-A2 (IO-86)")
        )
        val repo = createRepo(items)
        repo.setTypes(listOf("AMSAT Live FM"))
        val results = repo.getEntriesFlow().first()
        assertEquals(setOf(25544, 67291, 98205, 40931), results.map { it.catnum }.toSet())
    }

    @Test
    fun `Linear virtual type shows only hardcoded linear satellites`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setTypes(listOf("AMSAT Live Linear"))
        val results = repo.getEntriesFlow().first()
        // Hardcoded linear list: RS-44 (44909), FO-29 (24278), AO-7 (7530),
        // AO-73 (39444), JO-97 (43803). Present in the sample DB: 7530, 39444, 43803.
        assertEquals(setOf(7530, 39444, 43803), results.map { it.catnum }.toSet())
    }

    @Test
    fun `SSTV virtual type shows only satellites with SSTV radios`() = runTest {
        val repo = createRepo(
            items = sampleItems,
            sstvIds = listOf(43803)
        )
        repo.setTypes(listOf("Live SSTV"))
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(43803), results.map { it.catnum })
    }

    @Test
    fun `SSTV virtual type uses the amateur-only radio query`() = runTest {
        // The repo delegates to getIdsWithModesAndAmateur: the fake returns
        // only catnums the DAO would have filtered to Amateur service (43803).
        val repo = createRepo(
            items = sampleItems,
            sstvIds = listOf(43803)
        )
        repo.setTypes(listOf("Live SSTV"))
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(43803), results.map { it.catnum })
    }

    @Test
    fun `SSTV virtual type excludes rocket-body debris by name`() = runTest {
        // 60239 is "ARIANE 6 R/B" (launcher debris carrying an amateur SSTV
        // payload); it must be dropped even though its radio is Amateur.
        val items = sampleItems + SatItem(catnum = 60239, name = "ARIANE 6 R/B")
        val repo = createRepo(
            items = items,
            sstvIds = listOf(43803, 60239)
        )
        repo.setTypes(listOf("Live SSTV"))
        val results = repo.getEntriesFlow().first()
        assertEquals(listOf(43803), results.map { it.catnum })
    }

    @Test
    fun `multiple virtual types union their satellites`() = runTest {
        val repo = createRepo(
            items = sampleItems,
            sstvIds = listOf(43803)
        )
        repo.setTypes(listOf("AMSAT Live FM", "Live SSTV"))
        val results = repo.getEntriesFlow().first()
        assertEquals(setOf(25544, 43803), results.map { it.catnum }.toSet())
    }

    @Test
    fun `clearing types restores full list`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setTypes(listOf("AMSAT Live FM"))
        assertTrue(repo.getEntriesFlow().first().isNotEmpty())
        repo.setTypes(emptyList())
        assertEquals(sampleItems.size, repo.getEntriesFlow().first().size)
    }

    @Test
    fun `virtual type with no matching satellites shows empty not everything`() = runTest {
        // 硬编码 FM 列表 (27607/25544/61781) 里没有样本库中的卫星时, 选虚拟
        // 类型应显示空列表, 而非全部卫星.
        val items = listOf(SatItem(catnum = 7530, name = "AO-7 (AMSAT-OSCAR 7)"))
        val repo = createRepo(items)
        repo.setTypes(listOf("AMSAT Live FM"))
        assertTrue(repo.getEntriesFlow().first().isEmpty())
    }

    @Test
    fun `virtual type combined with regular type unions both lists`() = runTest {
        val repo = createRepo(items = sampleItems)
        repo.setTypes(listOf("AMSAT Live FM", "AMSAT Live Linear"))
        val results = repo.getEntriesFlow().first()
        assertEquals(
            setOf(25544, 7530, 39444, 43803),
            results.map { it.catnum }.toSet()
        )
    }

    @Test
    fun `types list has virtual transponder types first and keeps All`() {
        val repo = createRepo(sampleItems)
        val types = repo.getTypesList()
        assertTrue(types.size >= 4)
        assertEquals("AMSAT Live FM", types[0])
        assertEquals("AMSAT Live Linear", types[1])
        assertEquals("Live SSTV", types[2])
        // "All" must be kept in the list (first original type shown after virtual ones).
        assertTrue("All" in types)
    }

    @Test
    fun `All displays all local satellites despite incomplete CelesTrak cached ids`() = runTest {
        val fake = FakeSettingsRepoForSearch()
        fake.setSatelliteTypeIds("All", listOf(25544))
        val repo = SelectionRepo(
            dispatcher = Dispatchers.Unconfined,
            localSource = FakeLocalSourceForSearch(sampleItems),
            settingsRepo = fake
        )
        repo.setTypes(listOf("All"))
        val results = repo.getEntriesFlow().first()
        assertEquals(sampleItems.map { it.catnum }.toSet(), results.map { it.catnum }.toSet())
        repo.setTypes(listOf("Amsat", "All"))
        assertEquals(sampleItems.map { it.catnum }.toSet(), repo.getEntriesFlow().first().map { it.catnum }.toSet())
    }

    @Test
    fun `All with empty persisted ids falls back to no filtering`() = runTest {
        // "All" is never restricted by the CelesTrak source's cached IDs.
        val repo = createRepo(sampleItems)
        repo.setTypes(listOf(Sources.allSourceType))
        val results = repo.getEntriesFlow().first()
        assertEquals(sampleItems.map { it.catnum }.toSet(), results.map { it.catnum }.toSet())
    }

    @Test
    fun `other source type with empty persisted ids still shows empty`() = runTest {
        // A regular source without persisted ids still shows no matches.
        val repo = createRepo(sampleItems)
        repo.setTypes(listOf("Amsat"))
        assertTrue(repo.getEntriesFlow().first().isEmpty())
    }

    @Test
    fun `getTypesList excludes only the Other placeholder`() = runTest {
        val repo = createRepo(sampleItems)
        val types = repo.getTypesList()
        // All real TLE source types must be selectable, including "All"
        // (whole local catalogue) and "ARISS" (removed by the old removeAt(0)).
        assertTrue("All" in types)
        assertTrue("ARISS" in types)
        assertTrue("R4UAB" in types)
        assertTrue("SatNOGS" in types)
        assertTrue("Other" !in types)
    }

    @Test
    fun `all types shows all items`() = runTest {
        val repo = createRepo(sampleItems)
        repo.setTypes(emptyList()) // "All" = no type filtering
        val results = repo.getEntriesFlow().first()
        assertEquals(sampleItems.size, results.size)
        assertEquals(sampleItems.map { it.catnum }.toSet(), results.map { it.catnum }.toSet())
    }

    @Test
    fun `selected satellites are shown first`() = runTest {
        // Pinning ported from upstream: ticked satellites sort above the rest,
        // each group keeping the name order.
        val repo = createRepo(
            items = listOf(
                SatItem(catnum = 44444, name = "Zeta"),
                SatItem(catnum = 25544, name = "Alpha"),
                SatItem(catnum = 40967, name = "Beta")
            )
        )
        val flow = repo.getEntriesFlow()
        repo.setSelection(listOf(40967), true)
        val results = flow.first()
        assertEquals(listOf(40967, 25544, 44444), results.map { it.catnum })
        assertEquals(listOf(true, false, false), results.map { it.isSelected })
    }

    private fun createRepo(
        items: List<SatItem>,
        sstvIds: List<Int> = emptyList()
    ): ISelectionRepo {
        return SelectionRepo(
            dispatcher = Dispatchers.Unconfined,
            localSource = FakeLocalSourceForSearch(items, sstvIds),
            settingsRepo = FakeSettingsRepoForSearch()
        )
    }
}

private class FakeLocalSourceForSearch(
    private val items: List<SatItem>,
    private val sstvIds: List<Int> = emptyList()
) : ILocalSource {
    override suspend fun getEntriesTotal(): Int = items.size
    override suspend fun getEntriesList(): List<SatItem> = items
    override suspend fun getEntriesWithIds(ids: List<Int>): List<OrbitalObject> = emptyList()
    override suspend fun getEntriesEpochs(): Map<Int, Double> = emptyMap()
    override suspend fun getEntriesNames(): Map<Int, String> = emptyMap()
    override suspend fun renameEntries(names: Map<Int, String>) = Unit
    override suspend fun deleteEntriesWithIds(ids: List<Int>) = Unit
    override suspend fun insertEntries(entries: List<OrbitalData>) = Unit
    override suspend fun deleteEntries() = Unit
    override suspend fun getIdsWithModes(modes: List<String>): List<Int> = sstvIds
    override suspend fun getIdsWithModesAndUplink(modes: List<String>): List<Int> = sstvIds
    override suspend fun getIdsWithModesAndAmateur(modes: List<String>): List<Int> = sstvIds
    override suspend fun getRadiosTotal(): Int = 0
    override suspend fun getRadiosWithId(id: Int): List<SatRadio> = emptyList()
    override suspend fun insertRadios(radios: List<SatRadio>, isCustom: Boolean) = Unit
    override suspend fun deleteManagedRadios() = Unit
    override suspend fun deleteRadios() = Unit
}

private class FakeSettingsRepoForSearch : ISettingsRepo {
    private val typeIds = mutableMapOf<String, List<Int>>()
    override val appVersionName: String = "test"
    override val selectedIds: StateFlow<List<Int>> = MutableStateFlow(emptyList())
    override val selectedTypes: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val passesSettings: StateFlow<PassesSettings> = MutableStateFlow(
        PassesSettings(hoursAhead = 24, minElevation = 0.0, selectedModes = emptyList())
    )
    override val stationPosition: StateFlow<GeoPos> = MutableStateFlow(GeoPos(0.0, 0.0))
    override val databaseState: MutableStateFlow<DatabaseState> = MutableStateFlow(DatabaseState(0, 0, 0L))
    override val rcSettings: StateFlow<RCSettings> = MutableStateFlow(
        RCSettings(false, "", "", "", false, "", "", "", 0L, false, "", "", "", false, "", "")
    )
    override val otherSettings: StateFlow<OtherSettings> = MutableStateFlow(
        OtherSettings(
            false, false, false, false, false, false, false,
            shouldSeeWarning = false, shouldSeeWhatsNew = false
        )
    )
    override val dataSourcesSettings: MutableStateFlow<DataSourcesSettings> =
        MutableStateFlow(DataSourcesSettings(satelliteUrls = emptyList(), transceiversUrls = emptyList()))
    override val dataSourcesStatus: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val radioControlSettings: StateFlow<RadioControlSettings> = MutableStateFlow(
        RadioControlSettings(false, RadioControlSettings.MODEL_YAESU_FT817, "", "", "", "", 9600)
    )

    override val wavelogUploadSettings: StateFlow<com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings> =
        MutableStateFlow(com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings())
    override fun updateWavelogUploadSettings(settings: com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings) = Unit
    override val lotwSettings: StateFlow<com.rtbishop.look4sat.core.domain.model.LoTWSettings> =
        MutableStateFlow(com.rtbishop.look4sat.core.domain.model.LoTWSettings())

    override fun setSelectedIds(ids: List<Int>) = Unit
    override fun setSelectedTypes(types: List<String>) = Unit
    override fun setPassesSettings(settings: PassesSettings) = Unit
    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean = true
    override fun setStationPosition(): Boolean = true
    override fun setStationPosition(locator: String): Boolean = true
    override fun getCurrentGrid(): String? = null
    override fun getSatelliteTypesIds(types: List<String>): List<Int> =
        types.flatMap { typeIds[it].orEmpty() }.distinct()

    override fun setSatelliteTypeIds(type: String, ids: List<Int>) {
        typeIds[type] = ids
    }
    override fun updateDatabaseState(state: DatabaseState) = Unit
    override fun updateRCSettings(settings: RCSettings) = Unit
    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) = Unit
    override fun updateDataSourcesSettings(settings: DataSourcesSettings) = Unit
    override fun updateDataSourcesStatus(status: Map<String, Int>) = Unit
    override fun updateRadioControlSettings(settings: RadioControlSettings) = Unit
    override fun getSatelliteOffset(catnum: Int): String = ""
    override fun getSatelliteMode(catnum: Int): String = ""
    override fun setSatelliteMode(catnum: Int, mode: String) {}
    override fun setSatelliteOffset(catnum: Int, offset: String) = Unit
    override fun getAmSatCallsign(): String = ""
    override fun setAmSatCallsign(callsign: String) = Unit

    override fun getWorkedGrids(): Set<String> = emptySet()
    override fun setWorkedGrids(grids: Set<String>) = Unit
    override fun getWorkedGridQsos(): Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>> = emptyMap()
    override fun setWorkedGridQsos(qsos: Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>) = Unit
    override fun getRoamedGrids(): Set<String> = emptySet()
    override fun setRoamedGrids(grids: Set<String>) = Unit
    override fun getVuccSelectedStationIds(): Set<String> = emptySet()
    override fun setVuccSelectedStationIds(ids: Set<String>) = Unit
    override fun getMarkedGridStations(): Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>> = emptyMap()
    override fun setMarkedGridStations(stations: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>) = Unit
    override fun updateLoTWSettings(settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings) = Unit
    override fun getLastLotwSyncDate(): String = ""
    override fun setLastLotwSyncDate(date: String) = Unit
    override fun getLastLotwSyncCallsign(): String = ""
    override fun setLastLotwSyncCallsign(callsign: String) = Unit
    override fun getWavelogStations(): List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList()
    override fun setWavelogStations(stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>) = Unit
    override fun getWavelogSyncCursors(): Map<String, Long> = emptyMap()
    override fun setWavelogSyncCursors(cursors: Map<String, Long>) = Unit
    override fun getWavelogSyncUrl(): String = ""
    override fun setWavelogSyncUrl(url: String) = Unit
    override fun getLastWavelogSyncEpochMs(): Long = 0L
    override fun setLastWavelogSyncEpochMs(value: Long) = Unit
    override fun getLastWavelogFullSyncEpochMs(): Long = 0L
    override fun setLastWavelogFullSyncEpochMs(value: Long) = Unit
}
