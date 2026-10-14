package com.rtbishop.look4sat.feature.logbook

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import com.rtbishop.look4sat.core.domain.logbook.SATELLITE_MODE_FILTER

class LogbookStateTest {
    @Test
    fun satelliteModeIsSelectedByDefault() {
        val satelliteFromLoTW = record(
            id = 1,
            satelliteName = "FO-29",
            propagationMode = "SAT"
        )
        val satelliteWithoutPropagation = record(
            id = 2,
            satelliteName = "SO-50",
            propagationMode = ""
        )
        val groundContact = record(id = 3)

        val state = LogbookState(records = listOf(satelliteFromLoTW, satelliteWithoutPropagation, groundContact))

        assertEquals(SATELLITE_MODE_FILTER, state.modeFilter)
        assertEquals(listOf(1L, 2L), state.filteredRecords.map(QsoRecord::id))
    }

    @Test
    fun allModesStillShowsSatelliteAndGroundContacts() {
        val records = listOf(
            record(id = 1, satelliteName = "FO-29", propagationMode = "SAT"),
            record(id = 2)
        )

        assertEquals(records, LogbookState(records = records, modeFilter = "").filteredRecords)
    }

    private fun record(
        id: Long,
        satelliteName: String = "",
        propagationMode: String = ""
    ) = QsoRecord(
        id = id,
        startUtcMillis = id,
        theirCallsign = "K1ABC",
        myCallsign = "BA7OPF",
        satelliteName = satelliteName,
        propagationMode = propagationMode
    )
}
