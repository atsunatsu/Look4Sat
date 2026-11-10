/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VuccMultiStationSelectionTest {
    private fun qso(call: String, timestamp: Long, vararg own: String): GridQso =
        GridQso(
            call = call, epochMs = timestamp, satName = "RS-44",
            mode = "CW", bandUp = "2M", bandDown = "70CM",
            myGrids = own.toSet(), myGrid = own.firstOrNull()
        )

    @Test
    fun multiSelectionUnionsCoveringScopesWithoutDuplicateQsos() {
        val single62 = qso("A", 20, "OL62")
        val single63 = qso("B", 30, "OL63")
        val boundary = qso("C", 10, "OL62", "OL63")
        val elsewhere = qso("D", 40, "PM01")
        val all = listOf(single62, single63, boundary, elsewhere)
        assertEquals(all, all.scopedToStations(emptySet()))
        assertEquals(listOf(single62, boundary), all.scopedToStations(setOf("OL62")))
        assertEquals(listOf(boundary), all.scopedToStations(setOf("OL62,OL63")))
        assertEquals(listOf(single62, single63, boundary), all.scopedToStations(setOf("OL62", "OL63")))
        assertEquals(listOf(boundary), all.scopedToStations(setOf("OL62,OL63", "OL62,OL63")))
    }

    @Test
    fun earliestCallsUseOnlySelectedStationQsos() {
        val qsos = mapOf(
            "NM84" to listOf(
                qso("WRONG", 1, "PM01"),
                qso("LATEST", 30, "OL62"),
                qso("FIRST", 10, "OL63"),
                qso("BOUNDARY", 20, "OL62", "OL63")
            )
        )
        assertEquals(mapOf("NM84" to "WRONG"), qsos.firstCallsByGrid(emptySet<String>()))
        assertEquals(mapOf("NM84" to "FIRST"), qsos.firstCallsByGrid(setOf("OL62", "OL63")))
        assertEquals(mapOf("NM84" to "BOUNDARY"), qsos.firstCallsByGrid(setOf("OL62,OL63")))
        assertEquals(emptyMap<String, String>(), qsos.firstCallsByGrid(setOf("OM60")))
    }

    @Test
    fun stationSelectionSeparatesSameGridForDifferentCallsigns() {
        val home = qso("A", 10, "OL62").copy(myCallsign = "BA7OPF")
        val portable = qso("B", 20, "OL62").copy(myCallsign = "BA7OPF/P")
        val homeId = home.stationSelectionKey()!!
        val portableId = portable.stationSelectionKey()!!
        org.junit.Assert.assertNotEquals(homeId, portableId)
        val qsos = listOf(home, portable)
        assertEquals(listOf(home), qsos.scopedToStation(homeId))
        assertEquals(listOf(portable), qsos.scopedToStation(portableId))
        val store = mapOf("FN31" to listOf(home), "FN32" to listOf(portable))
        assertEquals(setOf("FN31"), store.workedGridsForStations(setOf(homeId)))
        assertEquals(setOf("FN32"), store.workedGridsForStations(setOf(portableId)))
        assertEquals(setOf("FN31", "FN32"), store.workedGridsForStations(setOf(homeId, portableId)))
    }

    @Test
    fun stationIdentityUsesMyFieldsEvenWithSameCallsignAndGrid() {
        val hongKong = qso("A", 10, "OL62").copy(
            myCallsign = "BA7OPF", stationKey = "BA7OPF|321|HK||||"
        )
        val guangdong = qso("B", 20, "OL62").copy(
            myCallsign = "BA7OPF", stationKey = "BA7OPF|318|GD||||"
        )
        assertEquals(listOf(hongKong),
            listOf(hongKong, guangdong).scopedToStation(hongKong.stationSelectionKey()))
        assertEquals(listOf(guangdong),
            listOf(hongKong, guangdong).scopedToStation(guangdong.stationSelectionKey()))
    }

    @Test
    fun coveringRuleAppliesWithinOneStationIdentityOnly() {
        val single = qso("A", 10, "OL62").copy(myCallsign = "BA7OPF")
        val boundary = qso("B", 20, "OL62", "OL63").copy(myCallsign = "BA7OPF")
        val otherCall = qso("C", 30, "OL62", "OL63").copy(myCallsign = "BA7OPF/P")
        val all = listOf(single, boundary, otherCall)
        assertEquals(listOf(single, boundary), all.scopedToStation(single.stationSelectionKey()))
        assertEquals(listOf(boundary), all.scopedToStation(boundary.stationSelectionKey()))
        assertEquals(listOf(otherCall), all.scopedToStation(otherCall.stationSelectionKey()))
        assertEquals(all, all.scopedToStations(emptySet()))
    }

    @Test
    fun legacySelectionsExpandToAllMatchingStationIdentities() {
        val home = qso("A", 10, "OL62").copy(myCallsign = "BA7OPF")
        val portable = qso("B", 20, "OL62").copy(myCallsign = "BA7OPF/P")
        val unrelated = qso("C", 30, "OL63").copy(myCallsign = "BA7OPF")
        val available = setOfNotNull(home.stationSelectionKey(), portable.stationSelectionKey(),
            unrelated.stationSelectionKey())
        assertEquals(setOfNotNull(home.stationSelectionKey(), portable.stationSelectionKey()),
            setOf("OL62").migrateStationSelections(available))
        assertEquals(setOfNotNull(unrelated.stationSelectionKey()),
            setOfNotNull(unrelated.stationSelectionKey()).migrateStationSelections(available))
    }

    @Test
    fun stationIdentityNormalizesEmptyTrailingMetadata() {
        val withoutKey = qso("A", 10, "OL62").copy(myCallsign = "BA7OPF")
        val emptyMetadata = qso("B", 20, "OL62").copy(
            myCallsign = "BA7OPF", stationKey = "BA7OPF||||||"
        )
        assertEquals(withoutKey.stationSelectionKey(), emptyMetadata.stationSelectionKey())
        assertEquals(listOf(withoutKey, emptyMetadata),
            listOf(withoutKey, emptyMetadata).scopedToStation(withoutKey.stationSelectionKey()))
    }
}
