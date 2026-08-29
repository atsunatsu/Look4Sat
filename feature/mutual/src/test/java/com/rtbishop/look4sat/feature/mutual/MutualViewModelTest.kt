package com.rtbishop.look4sat.feature.mutual

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MutualViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * ViewModel wired to fakes and a test dispatcher that shares the runTest
     * scheduler, so [advanceUntilIdle] deterministically drains both the
     * viewModelScope (Main) queue and the compute dispatcher.
     */
    private fun TestScope.createVm(
        satellites: List<OrbitalObject> = emptyList(),
        passes: List<OrbitalPass> = emptyList(),
        position: GeoPos = TestOrbits.GUANGZHOU
    ): MutualViewModel = MutualViewModel(
        satelliteRepo = FakeSatelliteRepo(satellites, passes),
        settingsRepo = FakeSettingsRepo(position),
        computeDispatcher = StandardTestDispatcher(mainDispatcherRule.dispatcher.scheduler)
    )

    private fun TestScope.queryAndSettle(vm: MutualViewModel) {
        vm.queryMutualPasses()
        advanceUntilIdle()
    }

    // ---------------------------------------------------------------- init

    @Test
    fun `init prefills station A with the current position`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(position = TestOrbits.GUANGZHOU)
        val state = vm.uiState.value

        assertEquals("23.1300", state.stationALat)
        assertEquals("113.2600", state.stationALon)
        assertEquals(6, state.stationAGrid.length)
        assertTrue(state.stationAGrid.startsWith("OL63"))
        assertTrue(state.stationAGrid.uppercase() == state.stationAGrid)
        assertEquals(0.0, state.stationAMinElev, 0.0)
    }

    // ------------------------------------------------------- station input

    @Test
    fun `entering latitude recomputes the maidenhead grid`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(position = TestOrbits.SHENZHEN) // station A pre-filled with SZ
        vm.onStationALat("23.5")

        // 23.5N 114.06E -> field OL, square 73 (Shenzhen), subsquare AM
        assertEquals("OL73AM", vm.uiState.value.stationAGrid)
        // raw input string is stored verbatim, not reformatted
        assertEquals("23.5", vm.uiState.value.stationALat)
    }

    @Test
    fun `entering longitude recomputes the maidenhead grid`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(position = TestOrbits.GUANGZHOU)
        vm.onStationALon("113.5")

        // 23.13N 113.5E -> Guangzhou field OL63, subsquare SD
        assertEquals("OL63SD", vm.uiState.value.stationAGrid)
        // raw input string is stored verbatim, not reformatted
        assertEquals("113.5", vm.uiState.value.stationALon)
    }

    @Test
    fun `entering a lowercase grid normalises to uppercase and updates lat lon`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(position = TestOrbits.NEW_YORK)
        vm.onStationAGrid("ol63")

        val state = vm.uiState.value
        // The grid is normalised to upper case on input (Bug fix: raw input
        // used to be stored verbatim, e.g. "ol63" stayed lowercase).
        assertEquals("OL63", state.stationAGrid)
        // 4-char grid resolves to the square centre
        assertEquals(23.5, state.stationALat.toDouble(), 1e-9)
        assertEquals(113.0, state.stationALon.toDouble(), 1e-9)
    }

    @Test
    fun `invalid grid leaves lat and lon untouched`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(position = TestOrbits.GUANGZHOU)
        val before = vm.uiState.value.stationALat
        vm.onStationAGrid("ZZ99") // valid shape, out-of-range letters

        assertEquals("ZZ99", vm.uiState.value.stationAGrid)
        assertEquals(before, vm.uiState.value.stationALat)
    }

    @Test
    fun `use current position restores station A from settings`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(position = TestOrbits.GUANGZHOU)
        vm.onStationALat("10.0")
        vm.onStationALon("20.0")
        assertNotEquals("23.1300", vm.uiState.value.stationALat)

        vm.onUseCurrentPosition()

        val state = vm.uiState.value
        assertEquals("23.1300", state.stationALat)
        assertEquals("113.2600", state.stationALon)
        assertTrue(state.stationAGrid.startsWith("OL63"))
    }

    @Test
    fun `station B independent from station A`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm()
        vm.onStationBLat("22.54")
        vm.onStationBLon("114.06")

        // 22.54N 114.06E is Shenzhen: OL72 field (lon square 7, lat square 2)
        assertEquals("OL72", vm.uiState.value.stationBGrid.take(4))
        assertTrue(vm.uiState.value.stationBGrid.length == 6)
    }

    // ------------------------------------------------------ query: errors

    @Test
    fun `query with invalid coordinates sets an error and does not search`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(satellites = listOf(TestOrbits.ISS), passes = TestOrbits.findPassWindows())
        vm.onStationAGrid("ZZ99")
        vm.onStationBGrid("ZZ99")

        queryAndSettle(vm)

        val state = vm.uiState.value
        assertTrue(state.errorMessage != null)
        assertFalse(state.hasSearched)
        assertFalse(state.isCalculating)
        assertTrue(state.mutualPasses.isEmpty())
    }

    @Test
    fun `query with no satellites sets an error`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(satellites = emptyList())
        vm.onStationBGrid("OL62")

        queryAndSettle(vm)

        assertEquals(
            "No satellite data. Select satellites from the list first.",
            vm.uiState.value.errorMessage
        )
    }

    @Test
    fun `query with everything empty reports empty pass list`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(satellites = listOf(TestOrbits.ISS), passes = emptyList())
        vm.onStationBGrid("OL62")
        vm.onStationAMinElev(89.0)
        vm.onStationBMinElev(89.0)
        vm.onHoursAhead(2)

        queryAndSettle(vm)

        val state = vm.uiState.value
        assertFalse(state.isCalculating)
        assertEquals("Pass list is empty. Select satellites from the list first.", state.errorMessage)
    }

    // --------------------------------------------------- query: happy path

    @Test
    fun `query finds mutual passes reusing the main pass list`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val windows = TestOrbits.findPassWindows(hoursAhead = 12)
        assertTrue("test fixture must produce ISS passes over Guangzhou", windows.isNotEmpty())

        val vm = createVm(
            satellites = listOf(TestOrbits.ISS),
            passes = windows
        )
        vm.onStationBGrid("OL62")
        vm.onHoursAhead(12)

        queryAndSettle(vm)

        val state = vm.uiState.value
        assertNull(state.errorMessage)
        assertTrue(state.hasSearched)
        assertFalse(state.isCalculating)
        assertTrue("expected at least one mutual pass", state.mutualPasses.isNotEmpty())
        assertTrue(state.mutualPasses.all { it.catNum == 25544 })
        assertTrue(state.mutualPasses.all { it.name == "ISS (ZARYA)" })
        // AOS/LOS within the searched horizon
        val now = System.currentTimeMillis()
        assertTrue(state.mutualPasses.all { it.startTime >= now - 120_000L })
        assertTrue(state.mutualPasses.all { it.endTime <= now + 12 * 3600_000L + 120_000L })
    }

    @Test
    fun `query results are sorted by start time even when input is shuffled`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        // A 24h horizon reliably yields several windows per satellite (ISS +
        // staggered variant), guaranteeing >= 2 distinct passes.
        val windows = TestOrbits.findPassWindows(hoursAhead = 24) +
            TestOrbits.findPassWindows(sat = TestOrbits.ISS_VARIANT, hoursAhead = 24)
        assertTrue("fixture must produce at least two windows", windows.size >= 2)

        val shuffled = windows.shuffled(Random(42))
        val vm = createVm(
            satellites = listOf(TestOrbits.ISS, TestOrbits.ISS_VARIANT),
            passes = shuffled
        )
        vm.onStationBGrid("OL62")
        vm.onHoursAhead(24)

        queryAndSettle(vm)

        val starts = vm.uiState.value.mutualPasses.map { it.startTime }
        assertEquals(starts.sorted(), starts)
        // Refined AOS must not drift far from the coarse window
        val byAos = windows.associateBy { it.aosTime }
        vm.uiState.value.mutualPasses.forEach { mp ->
            assertTrue("refined AOS ${mp.startTime} near a coarse AOS", byAos.keys.any { kotlin.math.abs(it - mp.startTime) < 5 * 60_000L })
        }
    }

    // -------------------------------------------------- query: fallback

    @Test
    fun `query falls back to independent search when pass list is empty`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        // Two satellites (ISS + staggered variant) raise the odds of a window
        // within the fallback horizon while keeping the search realistic.
        val vm = createVm(
            satellites = listOf(TestOrbits.ISS, TestOrbits.ISS_VARIANT),
            passes = emptyList()
        )
        vm.onStationBGrid("OL62")
        vm.onHoursAhead(12)

        queryAndSettle(vm)

        val state = vm.uiState.value
        assertFalse(state.isCalculating)
        assertTrue(state.hasSearched)
        // GZ + SZ are ~100km apart: a 12h ISS window must yield at least one mutual pass
        assertNull(state.errorMessage)
        assertTrue("fallback should find mutual passes", state.mutualPasses.isNotEmpty())
    }

    @Test
    fun `query over antipodal stations finds no mutual passes`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm(satellites = listOf(TestOrbits.ISS), passes = emptyList())
        vm.onStationBLat("40.71")
        vm.onStationBLon("-74.01")
        vm.onHoursAhead(2)

        queryAndSettle(vm)

        val state = vm.uiState.value
        assertFalse(state.isCalculating)
        assertTrue(state.mutualPasses.isEmpty())
        assertTrue(state.errorMessage != null)
    }

    // -------------------------------------------------------------- misc

    @Test
    fun `clearError resets the error message`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createVm()
        vm.onStationAGrid("ZZ99")
        vm.onStationBGrid("ZZ99")
        queryAndSettle(vm)
        assertTrue(vm.uiState.value.errorMessage != null)

        vm.clearError()

        assertNull(vm.uiState.value.errorMessage)
    }
}