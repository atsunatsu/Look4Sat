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
    fun buildsSingleGridDetailUrlForSelectedAward() {
        assertEquals(
            "https://gridmaster.fr/api/grid.json?grid=JO31&award=sat",
            GridActivityStore.endpointForGrid("jo31", GridActivityAward.SATELLITE)
        )
        assertEquals(
            "https://gridmaster.fr/api/grid.json?grid=OL62&award=sat/leo",
            GridActivityStore.endpointForGrid("OL62", GridActivityAward.LEO)
        )
    }

    @Test
    fun bulkLatestEndpointUsesAwardAndNoPerGridRequests() {
        assertEquals(
            "https://gridmaster.fr/api/last.json?award=sat",
            GridActivityStore.endpointForBulkLatest(GridActivityAward.SATELLITE)
        )
        assertEquals(
            "https://gridmaster.fr/api/last.json?award=sat/leo",
            GridActivityStore.endpointForBulkLatest(GridActivityAward.LEO)
        )
    }

    @Test
    fun parsesLatestOperatorsAndDatesForEveryActivatedGrid() {
        val response = JSONObject()
            .put("award", JSONObject().put("code", "sat"))
            .put("updated", "2026-11-16")
            .put("grids", 2)
            .put("worked", JSONObject()
                .put("JO31", JSONObject().put("call", "DF2ET").put("date", "2026-11-12"))
                .put("OL62", JSONObject().put("call", "BA7OPF").put("date", "2026-11-16")))
        assertEquals(
            mapOf(
                "JO31" to GridActivityRecentOperator("DF2ET", "2026-11-12"),
                "OL62" to GridActivityRecentOperator("BA7OPF", "2026-11-16")
            ),
            GridActivityStore.parseBulkLatest(response.toString(), minimumEntries = 1)
        )
    }

    @Test(expected = IOException::class)
    fun rejectsBulkLatestDatasetWithMissingWorkedMap() {
        GridActivityStore.parseBulkLatest(JSONObject().put("grids", 2).toString(), 1)
    }

    @Test(expected = IOException::class)
    fun refusesIncompleteBulkLatestSoGoodCacheIsNotOverwritten() {
        val response = JSONObject()
            .put("grids", 2)
            .put("worked", JSONObject()
                .put("JO31", JSONObject().put("call", "DF2ET").put("date", "2026-11-12")))
        GridActivityStore.parseBulkLatest(response.toString(), minimumEntries = 1)
    }

    @Test
    fun gridMasterCacheExpiresAtUtcMidnight() {
        val utcMidnight = 20_000L * GridActivityStore.MILLIS_PER_UTC_DAY

        assertTrue(GridActivityStore.isCacheFreshForUtcDay(utcMidnight - 1_000L, utcMidnight - 1L))
        assertFalse(GridActivityStore.isCacheFreshForUtcDay(utcMidnight - 1_000L, utcMidnight))
        assertTrue(GridActivityStore.isCacheFreshForUtcDay(utcMidnight, utcMidnight + 1L))
        assertFalse(GridActivityStore.isCacheFreshForUtcDay(utcMidnight + 1L, utcMidnight))
        assertFalse(GridActivityStore.isCacheFreshForUtcDay(0L, utcMidnight))
    }

    @Test
    fun sharedRequestBudgetRefillsAtOneTokenEveryThirtySixSecondsAndCapsBurst() {
        val empty = GridActivityStore.RequestBudgetState(tokens = 0.0, updatedAt = 1_000L)
        assertEquals(
            1.0,
            GridActivityStore.refillRequestBudget(empty, 37_000L).tokens,
            0.0001
        )
        assertEquals(
            0.5,
            GridActivityStore.refillRequestBudget(empty, 19_000L).tokens,
            0.0001
        )
        val full = GridActivityStore.RequestBudgetState(
            tokens = GridActivityStore.REQUEST_BUDGET_CAPACITY,
            updatedAt = 1_000L
        )
        assertEquals(
            GridActivityStore.REQUEST_BUDGET_CAPACITY,
            GridActivityStore.refillRequestBudget(full, 3_601_000L).tokens,
            0.0001
        )
    }

    @Test
    fun sharedRequestBudgetWaitsForBothRetryAfterAndNextToken() {
        val empty = GridActivityStore.RequestBudgetState(tokens = 0.0, updatedAt = 1_000L)
        assertEquals(
            GridActivityStore.REQUEST_REFILL_INTERVAL_MS,
            GridActivityStore.requestBudgetWaitMillis(empty, 1_000L)
        )
        val rateLimited = empty.copy(blockedUntil = 50_000L)
        assertEquals(
            49_000L,
            GridActivityStore.requestBudgetWaitMillis(rateLimited, 1_000L)
        )
    }

    @Test
    fun parsesRetryAfterSecondsAndUsesSafeFallbackWhenMissing() {
        assertEquals(12_000L, GridActivityStore.parseRetryAfterDelayMillis("12", 1_000L))
        assertEquals(
            GridActivityStore.REQUEST_REFILL_INTERVAL_MS,
            GridActivityStore.parseRetryAfterDelayMillis(null, 1_000L)
        )
    }

    @Test
    fun parsesMostRecentCallsignFromTheFirstLastEntry() {
        val response = JSONObject()
            .put("grid", "JO31")
            .put("percent", 48)
            .put("operator", 1503)
            .put(
                "last",
                org.json.JSONArray()
                    .put(JSONObject().put("call", "DL/F4MFS").put("date", "2026-11-06"))
                    .put(JSONObject().put("call", "DF2ET").put("date", "2026-10-31"))
            )

        assertEquals("DL/F4MFS", GridActivityStore.parseLatestCall(response.toString()))
    }

    @Test
    fun parsesAllRecentOperatorsAndTheirDatesInNewestFirstOrder() {
        val last = org.json.JSONArray()
            .put(JSONObject().put("call", "DL/F4MFS").put("date", "2026-11-06"))
            .put(JSONObject().put("call", "DF2ET").put("date", "2026-10-31"))
            .put(JSONObject().put("call", "DL1EGL").put("date", "2026-10-30"))
            .put(JSONObject().put("call", "DL2ABC").put("date", "2026-10-29"))
            .put(JSONObject().put("call", "DL3DEF").put("date", "2026-10-28"))
            .put(JSONObject().put("call", "DL4GHI").put("date", "2026-10-27"))
        val response = JSONObject().put("grid", "JO31").put("last", last)

        assertEquals(
            listOf(
                GridActivityRecentOperator("DL/F4MFS", "2026-11-06"),
                GridActivityRecentOperator("DF2ET", "2026-10-31"),
                GridActivityRecentOperator("DL1EGL", "2026-10-30"),
                GridActivityRecentOperator("DL2ABC", "2026-10-29"),
                GridActivityRecentOperator("DL3DEF", "2026-10-28"),
                GridActivityRecentOperator("DL4GHI", "2026-10-27")
            ),
            GridActivityStore.parseRecentOperators(response.toString())
        )
        assertEquals("DL/F4MFS", GridActivityStore.parseLatestCall(response.toString()))
    }

    @Test
    fun ignoresEntriesWithoutCallsignAndCapsRecentOperatorsAtSix() {
        val last = org.json.JSONArray()
            .put(JSONObject().put("call", "DL1AAA").put("date", "2026-11-06"))
            .put(JSONObject().put("date", "2026-11-05"))
            .put(JSONObject().put("call", "DL2BBB").put("date", "2026-11-04"))
            .put(JSONObject().put("call", "DL3CCC").put("date", "2026-11-03"))
            .put(JSONObject().put("call", "DL4DDD").put("date", "2026-11-02"))
            .put(JSONObject().put("call", "DL5EEE").put("date", "2026-11-01"))
            .put(JSONObject().put("call", "DL6FFF").put("date", "2026-10-31"))
            .put(JSONObject().put("call", "DL7GGG").put("date", "2026-10-30"))
        val response = JSONObject().put("grid", "JO31").put("last", last)

        assertEquals(
            listOf("DL1AAA", "DL2BBB", "DL3CCC", "DL4DDD", "DL5EEE", "DL6FFF"),
            GridActivityStore.parseRecentOperators(response.toString()).map { it.call }
        )
    }

    @Test
    fun emptyLastListMeansNoRecentCallsign() {
        val response = JSONObject().put("grid", "JO31").put("percent", 0)
            .put("operator", 0).put("last", org.json.JSONArray())

        assertEquals(null, GridActivityStore.parseLatestCall(response.toString()))
    }

    @Test(expected = IOException::class)
    fun rejectsGridDetailResponsesWithoutLastList() {
        GridActivityStore.parseLatestCall(JSONObject().put("grid", "JO31").toString())
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
