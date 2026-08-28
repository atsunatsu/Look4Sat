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
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseRepoTest {

    private val dispatcher = StandardTestDispatcher()
    private val dataParser = DataParser(dispatcher)

    @Test
    fun `manual satellite import parses csv stream from content uri`() = runTest(dispatcher) {
        val uri = "content://look4sat/import/satellites"
        val localSource = FakeLocalSource()
        val remoteSource = FakeRemoteSource().apply {
            fileStreams[uri] = { validCsvStream() }
        }
        val settingsRepo = FakeSettingsRepo()
        val repository = DatabaseRepo(dispatcher, dataParser, localSource, remoteSource, settingsRepo)

        repository.updateTLEFromFile(uri)

        assertEquals(1, localSource.insertedEntries.size)
        assertEquals(25544, localSource.insertedEntries.first().catnum)
        assertEquals(listOf(25544), settingsRepo.satelliteTypeIdsByType["Other"])
        assertTrue(settingsRepo.databaseState.value.numberOfSatellites > 0)
    }

    @Test
    fun `manual satellite import keeps tle support`() = runTest(dispatcher) {
        val uri = "content://look4sat/import/legacy"
        val localSource = FakeLocalSource()
        val remoteSource = FakeRemoteSource().apply {
            fileStreams[uri] = { validTleStream() }
        }
        val settingsRepo = FakeSettingsRepo()
        val repository = DatabaseRepo(dispatcher, dataParser, localSource, remoteSource, settingsRepo)

        repository.updateTLEFromFile(uri)

        assertEquals(1, localSource.insertedEntries.size)
        assertEquals(25544, localSource.insertedEntries.first().catnum)
    }

    @Test
    fun `custom data source imports omm csv from web`() = runTest(dispatcher) {
        val customCsvUrl = "https://example.com/custom-omm.csv"
        val localSource = FakeLocalSource()
        val remoteSource = FakeRemoteSource().apply {
            networkStreams[customCsvUrl] = { validCsvStream() }
        }
        val settingsRepo = FakeSettingsRepo(
            dataSources = DataSourcesSettings(
                satelliteUrls = listOf(customCsvUrl),
                transceiversUrls = emptyList()
            )
        )
        val repository = DatabaseRepo(dispatcher, dataParser, localSource, remoteSource, settingsRepo)

        repository.updateFromRemote()

        assertTrue(localSource.insertedEntries.any { it.catnum == 25544 })
        assertEquals(listOf(25544), settingsRepo.satelliteTypeIdsByType["Other"])
    }

    @Test
    fun `remote update imports satellites from real SatNOGS TLE source`() = runTest(dispatcher) {
        val satnogsUrl = Sources.satelliteDataUrls.getValue("SatNOGS")
        val localSource = FakeLocalSource()
        val remoteSource = FakeRemoteSource().apply {
            networkStreams[satnogsUrl] = { jamxTleStream() }
        }
        val settingsRepo = FakeSettingsRepo(
            dataSources = DataSourcesSettings(
                satelliteUrls = listOf(satnogsUrl),
                transceiversUrls = emptyList()
            )
        )
        val repository = DatabaseRepo(dispatcher, dataParser, localSource, remoteSource, settingsRepo)

        repository.updateFromRemote()

        assertTrue(localSource.insertedEntries.any { it.name == "JAMX-0825b" && it.catnum == 98248 })
        assertEquals(listOf(98248), settingsRepo.satelliteTypeIdsByType["SatNOGS"])
    }

    private fun validCsvStream(): InputStream = """
        OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID,ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT
        ISS (ZARYA),1998-067A,2021-11-16T12:28:09.322176,15.48582035,.0004694,51.6447,309.4881,203.6966,299.8876,0,U,25544,999,31220,.31985E-4,.1288E-4,0
    """.trimIndent().byteInputStream()

    private fun validTleStream(): InputStream = """
        ISS (ZARYA)
        1 25544U 98067A   21320.51955234  .00001288  00000+0  31985-4 0  9990
        2 25544  51.6447 309.4881 0004694 203.6966 299.8876 15.48582035312205
    """.trimIndent().byteInputStream()

    private fun jamxTleStream(): InputStream = """
        JAMX-0825b
        1 98248U          26237.16675926  .00015724  00000-0  97477-3 0 00013
        2 98248 097.5373 310.9694 0011309 278.1232 340.7230 15.09766181000012
    """.trimIndent().byteInputStream()
}

private class FakeRemoteSource : IRemoteSource {
    val fileStreams: MutableMap<String, () -> InputStream> = mutableMapOf()
    val networkStreams: MutableMap<String, () -> InputStream> = mutableMapOf()

    override suspend fun getFileStream(uri: String): InputStream? = fileStreams[uri]?.invoke()

    override suspend fun getNetworkStream(url: String): InputStream? = networkStreams[url]?.invoke()

    override suspend fun getAmSatCatalog(): String? = null

    override suspend fun getAmSatReports(hours: Int, limit: Int): String? = null

    override suspend fun submitAmSatReport(payloadJson: String): Pair<Int, String>? = null
}

private class FakeLocalSource : ILocalSource {
    val insertedEntries = mutableListOf<OrbitalData>()
    private val insertedRadios = mutableListOf<SatRadio>()

    override suspend fun getEntriesTotal(): Int = insertedEntries.size

    override suspend fun getEntriesList(): List<SatItem> = emptyList()

    override suspend fun getEntriesWithIds(ids: List<Int>): List<OrbitalObject> = emptyList()

    override suspend fun insertEntries(entries: List<OrbitalData>) {
        insertedEntries += entries
    }

    override suspend fun deleteEntries() {
        insertedEntries.clear()
    }

    override suspend fun getIdsWithModes(modes: List<String>): List<Int> = emptyList()

    override suspend fun getRadiosTotal(): Int = insertedRadios.size

    override suspend fun getRadiosWithId(id: Int): List<SatRadio> = emptyList()

    override suspend fun insertRadios(radios: List<SatRadio>) {
        insertedRadios += radios
    }

    override suspend fun deleteRadios() {
        insertedRadios.clear()
    }
}

private class FakeSettingsRepo(dataSources: DataSourcesSettings = defaultDataSourcesSettings()) : ISettingsRepo {

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
        OtherSettings(false, false, false, false, false, false, false, false)
    )

    override val dataSourcesSettings: MutableStateFlow<DataSourcesSettings> = MutableStateFlow(dataSources)

    override val radioControlSettings: StateFlow<RadioControlSettings> = MutableStateFlow(
        RadioControlSettings(false, RadioControlSettings.MODEL_YAESU_FT817, "", "", "", "", 9600)
    )

    val satelliteTypeIdsByType = mutableMapOf<String, List<Int>>()

    override fun setSelectedIds(ids: List<Int>) = Unit

    override fun setSelectedTypes(types: List<String>) = Unit

    override fun setPassesSettings(settings: PassesSettings) = Unit

    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean = true

    override fun setStationPosition(): Boolean = true

    override fun setStationPosition(locator: String): Boolean = true

    override fun getSatelliteTypesIds(types: List<String>): List<Int> = emptyList()

    override fun setSatelliteTypeIds(type: String, ids: List<Int>) {
        satelliteTypeIdsByType[type] = ids
    }

    override fun updateDatabaseState(state: DatabaseState) {
        databaseState.value = state
    }

    override fun updateRCSettings(settings: RCSettings) = Unit

    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) = Unit

    override fun updateDataSourcesSettings(settings: DataSourcesSettings) {
        dataSourcesSettings.value = settings
    }

    override fun updateRadioControlSettings(settings: RadioControlSettings) = Unit

    override fun getSatelliteOffset(catnum: Int): String = ""

    override fun setSatelliteOffset(catnum: Int, offset: String) = Unit

    override fun getAmSatCallsign(): String = ""

    override fun setAmSatCallsign(callsign: String) = Unit
}

private fun defaultDataSourcesSettings(): DataSourcesSettings {
    return DataSourcesSettings(
        satelliteUrls = emptyList(),
        transceiversUrls = emptyList()
    )
}
