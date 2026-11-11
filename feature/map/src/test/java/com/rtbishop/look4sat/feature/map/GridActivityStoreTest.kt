package com.rtbishop.look4sat.feature.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GridActivityStoreTest {

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

    @Test(expected = IOException::class)
    fun refusesIncompleteDataSoLastGoodCacheIsNotReplaced() {
        GridActivityStore.parse(JSONObject().put("OL62", 17).toString())
    }
}
