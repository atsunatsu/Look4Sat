package com.rtbishop.look4sat.feature.mutual

import org.junit.Assert.assertEquals
import org.junit.Test

class MutualPassFilteringTest {
    private fun pass(catNum: Int, startTime: Long) = MutualPass(
        catNum = catNum,
        name = "SAT-$catNum",
        startTime = startTime,
        endTime = startTime + 1_000L,
        maxElevationA = 30.0,
        maxElevationB = 25.0,
        elevationSamples = emptyList()
    )

    @Test
    fun `focusing a satellite filters its passes and clearing focus restores all passes`() {
        val passes = listOf(
            pass(catNum = 25544, startTime = 100L),
            pass(catNum = 25545, startTime = 200L),
            pass(catNum = 25544, startTime = 300L)
        )

        val focused = visibleMutualPasses(passes, focusedCatNum = 25545)
        assertEquals(listOf(1), focused.map { it.first })
        assertEquals(listOf(25545), focused.map { it.second.catNum })

        val all = visibleMutualPasses(passes, focusedCatNum = null)
        assertEquals(listOf(0, 1, 2), all.map { it.first })
        assertEquals(passes, all.map { it.second })
    }
}
