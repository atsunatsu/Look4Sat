package com.rtbishop.look4sat.feature.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GridActivityStoreTest {

    @Test
    fun exposesFiveCoreSatelliteAwardsWithIndependentCachesAndApiCodes() {
        assertEquals("sat", GridActivityAward.SATELLITE.apiCode)
        assertEquals("sat/leo", GridActivityAward.LEO.apiCode)
        assertEquals("sat/meo", GridActivityAward.MEO.apiCode)
        assertEquals("sat/geo", GridActivityAward.GEO.apiCode)
        assertEquals("sat/ngs", GridActivityAward.NON_GEOSTATIONARY.apiCode)

        val cacheFiles = GridActivityAward.values().map { it.cacheFileName }
        assertEquals(5, cacheFiles.distinct().size)
        assertEquals(
            GridActivityAward.SATELLITE,
            GridActivityAward.fromApiCode("unknown")
        )
        assertEquals(
            GridActivityAward.LEO,
            GridActivityAward.fromApiCode("sat/leo")
        )
    }

    @Test
    fun buildsGridMasterMapUrlForSelectedAward() {
        assertEquals(
            "https://gridmaster.fr/api/grids.json?award=sat/leo&format=map",
            GridActivityStore.endpointFor(GridActivityAward.LEO)
        )
        assertEquals(
            "https://gridmaster.fr/api/grids.json?award=sat&format=map",
            GridActivityStore.endpointFor(GridActivityAward.SATELLITE)
        )
    }

    @Test
    fun parsesFourCharacterGridPercentagesAndIgnoresBadEntries() {
        val raw = JSONObject()
        for (i in 0 until 60) {
            val grid = "AA" + (i / 10) + (i % 10)
            raw.put(grid, i)
        }
        raw.put("RR99", 100)
        raw.put("ZZ99", 20)
        raw.put("AB12", 101)
        raw.put("AB13", -5)
        raw.put("AB14", "not a number")

        val parsed = GridActivityStore.parse(raw.toString())
        assertEquals(61, parsed.size)
        assertEquals(0, parsed["AA00"])
        assertEquals(59, parsed["AA59"])
        assertEquals(100, parsed["RR99"])
        assertFalse(parsed.containsKey("ZZ99"))
        assertFalse(parsed.containsKey("AB12"))
        assertFalse(parsed.containsKey("AB13"))
        assertFalse(parsed.containsKey("AB14"))
    }

    @Test
    fun acceptsNonEmptySpecializedAwardDatasetsWithFewerThanFiftyGrids() {
        val oneGrid = JSONObject().put("OL62", 7).toString()
        assertEquals(mapOf("OL62" to 7), GridActivityStore.parse(oneGrid, minimumEntries = 1))
    }

    @Test
    fun gridMasterStyleActivityColorsUseFixedSevenBands() {
        // SAT and LEO use the same percentage thresholds. These colors should
        // not be rescaled to the current award dataset or slider maximum.
        val bins = listOf(
            0 to 0xFFB41D2A.toInt(), 2 to 0xFFB41D2A.toInt(),
            3 to 0xFFE64A2D.toInt(), 7 to 0xFFE64A2D.toInt(),
            8 to 0xFFF09124.toInt(), 12 to 0xFFF09124.toInt(),
            13 to 0xFFF2CE29.toInt(), 17 to 0xFFF2CE29.toInt(),
            18 to 0xFFB5E133.toInt(), 22 to 0xFFB5E133.toInt(),
            23 to 0xFF32E64D.toInt(), 32 to 0xFF32E64D.toInt(),
            33 to 0xFF087C30.toInt(), 50 to 0xFF087C30.toInt(),
            77 to 0xFF087C30.toInt(), 100 to 0xFF087C30.toInt()
        )
        bins.forEach { (percentage, color) ->
            assertEquals("$percentage%", color, gridActivityPaletteColor(percentage))
        }
        assertEquals(gridActivityPaletteColor(0), gridActivityPaletteColor(-1))
        assertEquals(gridActivityPaletteColor(100), gridActivityPaletteColor(101))
    }

    @Test(expected = IOException::class)
    fun refusesIncompleteDataSoLastGoodCacheIsNotReplaced() {
        GridActivityStore.parse(JSONObject().put("OL62", 17).toString())
    }
}
