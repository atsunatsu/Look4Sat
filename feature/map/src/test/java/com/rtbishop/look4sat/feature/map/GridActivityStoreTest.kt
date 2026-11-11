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

    @Test
    fun activityColourScaleUsesDatasetMaximumAndFilterUpperLimit() {
        // The dataset only reaches 20%, so 10% maps to yellow instead of red.
        assertEquals(0, gridActivityColorIndex(0, 20, 100))
        assertEquals(25, gridActivityColorIndex(5, 20, 100))
        assertEquals(50, gridActivityColorIndex(10, 20, 100))
        assertEquals(100, gridActivityColorIndex(20, 20, 100))

        // Narrowing the upper filter to 10% spreads the palette over 0..10%.
        assertEquals(50, gridActivityColorIndex(5, 20, 10))
        assertEquals(100, gridActivityColorIndex(10, 20, 10))
        assertEquals(100, gridActivityColorIndex(20, 20, 10))

        // Zero maxima and out-of-range values never divide by zero or overflow.
        assertEquals(0, gridActivityColorIndex(0, 0, 0))
        assertEquals(0, gridActivityColorIndex(5, 0, 0))
        assertEquals(100, gridActivityColorIndex(200, 20, 20))
    }

    @Test(expected = IOException::class)
    fun refusesIncompleteDataSoLastGoodCacheIsNotReplaced() {
        GridActivityStore.parse(JSONObject().put("OL62", 17).toString())
    }
}
