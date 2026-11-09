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
package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwardCalculatorTest {

    private fun qso(
        call: String,
        dxcc: Int? = null,
        cqz: Int? = null,
        state: String? = null,
        grid: String = "OL62",
        fineGrids: List<String> = emptyList(),
        iota: String? = null
    ) = GridQso(
        call = call, epochMs = 0L, satName = "SO-50", mode = "FM",
        bandUp = "70CM", bandDown = "2M", dxcc = dxcc, cqz = cqz, state = state,
        theirFineGrids = fineGrids, iota = iota
    )

    private fun byGrid(vararg qsos: GridQso) =
        qsos.groupBy { "OL62" } // all share one grid for award-math tests

    @Test
    fun `vucc counts distinct grid keys`() {
        val qsos = mapOf(
            "OL62" to listOf(qso("BG7XYZ")),
            "PM95" to listOf(qso("JH0ABC")),
            "OL62" to listOf(qso("BA7AAA"))
        )
        // map literal dedupes keys: OL62/PM95 -> 2 grids
        val progress = AwardCalculator.calculate(qsos).first { it.type == AwardType.VUCC }
        assertEquals(2, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("OL62", "PM95")))
    }


    @Test
    fun `gridless confirmed QSOs count for entity awards but not VUCC`() {
        val china = qso("BA7ABC", dxcc = 318, state = "GD", cqz = 24)
        val japan = qso("JA1ABC", dxcc = 339, state = "13", cqz = 25)
        val indonesia = qso("YB0ABC", dxcc = 327, state = "JK", cqz = 28)
        val usa = qso("K1ABC", dxcc = 291, state = "CA", cqz = 3)
        val store = mapOf(GRIDLESS_QSO_BUCKET to listOf(china, japan, indonesia, usa))

        val progress = AwardCalculator.calculate(store).associateBy { it.type }
        assertEquals(emptySet<String>(), progress.getValue(AwardType.VUCC).workedKeys)
        assertEquals(0, progress.getValue(AwardType.VUCC).count)
        assertEquals(setOf("318", "339", "327", "291"), progress.getValue(AwardType.DXCC).workedKeys)
        assertEquals(setOf("GD"), progress.getValue(AwardType.WAPC).workedKeys)
        assertEquals(setOf("13"), progress.getValue(AwardType.WAJA).workedKeys)
        assertEquals(setOf("JK"), progress.getValue(AwardType.WAIPA).workedKeys)
        assertEquals(setOf("24", "25", "28", "3"), progress.getValue(AwardType.WAZ).workedKeys)
        assertEquals(setOf("CA"), progress.getValue(AwardType.WAS).workedKeys)
        assertEquals(listOf(china), AwardCalculator.regionQsos(store, AwardType.WAPC, "GD"))
        assertEquals(listOf(indonesia), AwardCalculator.regionQsos(store, AwardType.WAIPA, "JK"))
    }

    @Test
    fun `dxcc counts distinct entities from dxcc field`() {
        val all = listOf(
            qso("BG7XYZ", dxcc = 318),
            qso("JH0ABC", dxcc = 339),
            qso("YD1XYZ", dxcc = 327),
            qso("BG7ABC", dxcc = 318)
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.DXCC }
        assertEquals(3, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("318", "339", "327")))
    }

    @Test
    fun `dxcc falls back to prefix when dxcc missing`() {
        val all = listOf(qso("JH0ABC"), qso("BG7XYZ"), qso("VR2XYZ"), qso("XX9A"))
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.DXCC }
        assertEquals(4, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("339", "318", "321", "152")))
    }

    @Test
    fun `wapc counts mainland provinces plus hk mo tw`() {
        val all = listOf(
            qso("BG7XYZ", dxcc = 318, state = "GD"),   // Guangdong
            qso("BD5ABC", dxcc = 318, state = "ZJ"),   // Zhejiang
            qso("BD5ABC", dxcc = 318, state = "ZJ"),   // dup
            qso("VR2X", dxcc = 321),                   // Hong Kong
            qso("XX9A", dxcc = 152),                   // Macao
            qso("BM4X", dxcc = 386)                    // Taiwan
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAPC }
        assertEquals(5, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("GD", "ZJ", "HK", "MO", "TW")))
    }

    @Test
    fun `wapc ignores non-china states and missing states`() {
        val all = listOf(
            qso("JH0ABC", dxcc = 339, state = "34"),   // Japan state ignored
            qso("BG7XYZ", dxcc = 318, state = null),   // missing province
            qso("K1ABC", dxcc = 291, state = "HI")     // Hawaii != Hainan
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAPC }
        assertEquals(0, progress.count)
        assertTrue(progress.workedKeys.isEmpty())
    }

    @Test
    fun `waja counts japanese prefectures only`() {
        val all = listOf(
            qso("JH0ABC", dxcc = 339, state = "10"),   // Tokyo
            qso("JH0ABC", dxcc = 339, state = "47"),   // Okinawa
            qso("JH0ABC", dxcc = 339, state = "10"),   // dup
            qso("BG7XYZ", dxcc = 318, state = "10"),   // Chinese call, state=10 must NOT count
            qso("JE2XYZ", dxcc = 339, state = null)    // missing
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAJA }
        assertEquals(2, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("10", "47")))
    }


    @Test
    fun `waipa counts confirmed Indonesian provinces without duplicates`() {
        val all = listOf(
            qso("YB0AAA", dxcc = 327, state = "JK"),
            qso("YB9ABC", dxcc = 327, state = "ba"),  // lowercase Bali
            qso("YB9AAA", dxcc = 327, state = " ID-PB "), // ISO-prefixed West Papua
            qso("YB9BBB", dxcc = 327, state = "PD"),  // Southwest Papua is distinct from PB
            qso("YD6BBB", dxcc = 327, state = "AC"),
            qso("YB0AAA", dxcc = 327, state = "JK"), // same province
            qso("BG7XYZ", dxcc = 318, state = "JK"), // other DXCC, matching text
            qso("YB7UAA", dxcc = 327, state = null), // ambiguous KI/KU home prefix
            qso("YB9UAA", dxcc = 327, state = "XX") // ambiguous Papua prefixes
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray()))
            .first { it.type == AwardType.WAIPA }
        assertEquals(38, progress.target)
        assertEquals(setOf("JK", "BA", "PB", "PD", "AC"), progress.workedKeys)
        assertEquals(5, progress.count)
    }

    @Test
    fun `waipa matches Indonesian entity and rejects foreign explicit DXCC`() {
        assertEquals("YO", AwardCalculator.awardKey(AwardType.WAIPA,
            qso("YB2ABC", state = "YO")))
        assertEquals("KU", AwardCalculator.awardKey(AwardType.WAIPA,
            qso("7A7ABC", state = "KU")))
        assertEquals("PD", AwardCalculator.awardKey(AwardType.WAIPA,
            qso("YB9XYZ", dxcc = 327, state = "ID-PD")))
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAIPA,
            qso("YB9UAA", state = null)))
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAIPA,
            qso("JH0ABC", dxcc = 339, state = "BA")))
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAIPA,
            qso("YB9ABC", dxcc = 291, state = "BA")))
    }

    @Test
    fun `waipa region QSO dialog matches worked province and dedupes grids`() {
        val bali = qso("YB9AAA", dxcc = 327, state = "BA")
        val java = qso("YB2AAA", dxcc = 327, state = "JT")
        val store = mapOf(
            "OI71" to listOf(bali, java),
            "OI72" to listOf(bali)
        )
        assertEquals(listOf(bali), AwardCalculator.regionQsos(store, AwardType.WAIPA, "BA"))
        assertEquals(emptyList<GridQso>(), AwardCalculator.regionQsos(store, AwardType.WAIPA, "PB"))
    }

    @Test
    fun `waipa identifies province from 6-digit grid before home callsign and IOTA`() {
        val locations = mapOf("PI59PC" to "PD", "OI71OI" to "BA")
        val resolver: (String) -> String? = { locations[it] }
        val rover = qso("YB0ABC", dxcc = 327, fineGrids = listOf("PI59PC"), iota = "OC-022")
        assertEquals("PD", AwardCalculator.awardKey(AwardType.WAIPA, rover, resolver))
        val worked = mapOf("PI59" to listOf(rover))
        assertEquals(setOf("PD"), AwardCalculator.calculate(worked, resolver)
            .first { it.type == AwardType.WAIPA }.workedKeys)
        assertEquals(listOf(rover),
            AwardCalculator.regionQsos(worked, AwardType.WAIPA, "PD", resolver))
        assertTrue(AwardCalculator.regionQsos(worked, AwardType.WAIPA, "BA", resolver).isEmpty())
    }

    @Test
    fun `waipa uses explicit STATE before geographic and IOTA hints`() {
        val q = qso("YB0ABC", dxcc = 327, state = "ID-JK",
            fineGrids = listOf("PI59PC"), iota = "OC-022")
        assertEquals("JK", AwardCalculator.awardKey(AwardType.WAIPA, q, { "PD" }))
    }

    @Test
    fun `waipa uses IOTA for small islands without usable STATE or grid`() {
        val island = qso("YB7XYZ", dxcc = 327, iota = " oc-177 ")
        assertEquals("JK", AwardCalculator.awardKey(AwardType.WAIPA, island))
        assertEquals("JI", WaipaIotaLookup.province("oc-197"))
        assertEquals("KR", WaipaIotaLookup.province("OC-108"))
        assertEquals("AC", WaipaIotaLookup.province("OC-270"))
        assertEquals(null, WaipaIotaLookup.province("OC-021")) // multi-province Java
        assertEquals(null, WaipaIotaLookup.province("OC-034")) // multi-province Papua
        assertEquals(null, WaipaIotaLookup.province("OC-088")) // multi-province Borneo
        assertEquals(null, WaipaIotaLookup.province("OC-999"))
    }

    @Test
    fun `waipa does not credit conflicting fine-grid provinces or foreign entity`() {
        val resolver: (String) -> String? = {
            when (it) { "PI59PC" -> "PD"; "PI79AD" -> "PB"; else -> null }
        }
        val conflict = qso("YB9XYZ", dxcc = 327,
            fineGrids = listOf("PI59PC", "PI79AD"), iota = "OC-022")
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAIPA, conflict, resolver))
        val notIndonesian = qso("YB0ABC", dxcc = 291, iota = "OC-177")
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAIPA, notIndonesian, resolver))
    }

    @Test
    fun `waipa uses unique coarse grid and unambiguous IIHG home prefix as fallback`() {
        val coarse = qso("YB0ABC", dxcc = 327)
        assertEquals("KT", AwardCalculator.awardKey(
            AwardType.WAIPA, coarse, { if (it == "OK57") "KT" else null }, "OK57"
        ))
        assertEquals("JK", AwardCalculator.awardKey(AwardType.WAIPA, coarse))
        assertEquals(null, WaipaCallsignLookup.province("YB7UAA")) // KI/KU overlap
        assertEquals(setOf("KI", "KU"), WaipaCallsignLookup.candidates("YB7UAA"))
        assertEquals(null, WaipaCallsignLookup.province("YB9UAA")) // four Papua regions
        assertEquals(setOf("PA", "PP", "PS", "PT"), WaipaCallsignLookup.candidates("YB9UAA"))
        assertEquals(null, WaipaCallsignLookup.province("YB0ABC/P"))
        assertEquals(null, WaipaCallsignLookup.province("7A7ABC"))
    }

    @Test
    fun `waipa trusts PI59 operating geography over YE9RAS West Papua home allocation`() {
        val station = qso("YE9RAS", dxcc = 327)
        // The IIHG prefix/suffix home allocation puts YE9RAS in PB, but the
        // actual QSO's PI59 operating locator has Indonesian land only in PD.
        assertEquals("PB", WaipaCallsignLookup.province("YE9RAS"))
        val provinceForGrid: (String) -> String? = { if (it == "PI59") "PD" else null }
        assertEquals("PD", AwardCalculator.awardKey(
            AwardType.WAIPA, station, provinceForGrid, workedGrid = "PI59"
        ))
        val store = mapOf("PI59" to listOf(station))
        assertEquals(setOf("PD"), AwardCalculator.calculate(store, provinceForGrid)
            .first { it.type == AwardType.WAIPA }.workedKeys)
        assertEquals(listOf(station), AwardCalculator.regionQsos(
            store, AwardType.WAIPA, "PD", provinceForGrid
        ))
    }

    @Test
    fun `waipa multi-grid QSO does not credit two provinces across a border`() {
        val q = qso("YB7UAA", dxcc = 327)
        val store = mapOf("OK57" to listOf(q), "OI71" to listOf(q))
        val province: (String) -> String? = {
            when (it) { "OK57" -> "KT"; "OI71" -> "BA"; else -> null }
        }
        val progress = AwardCalculator.calculate(store, province)
            .first { it.type == AwardType.WAIPA }
        assertEquals(0, progress.count)
        assertTrue(AwardCalculator.regionQsos(store, AwardType.WAIPA, "KT", province).isEmpty())
        assertTrue(AwardCalculator.regionQsos(store, AwardType.WAIPA, "BA", province).isEmpty())
        val withIota = q.copy(iota = "OC-177")
        val iotaStore = mapOf("OK57" to listOf(withIota), "OI71" to listOf(withIota))
        assertEquals(setOf("JK"), AwardCalculator.calculate(iotaStore, province)
            .first { it.type == AwardType.WAIPA }.workedKeys)
    }

    @Test
    fun `waz counts distinct cq zones in range`() {
        val all = listOf(
            qso("BG7XYZ", cqz = 24),
            qso("JH0ABC", cqz = 25),
            qso("YD1XYZ", cqz = 28),
            qso("BG7AAA", cqz = 24),
            qso("BG7BBB", cqz = null),
            qso("BG7CCC", cqz = 0)
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAZ }
        assertEquals(3, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("24", "25", "28")))
    }

    @Test
    fun `was counts us states only`() {
        val all = listOf(
            qso("K1ABC", dxcc = 291, state = "CA"),
            qso("W2XYZ", dxcc = 291, state = "NY"),
            qso("N3QRS", dxcc = 291, state = "ca"),    // lowercase normalized
            qso("BG7XYZ", dxcc = 318, state = "CA"),   // not US
            qso("K1AAA", dxcc = 291, state = "XX")     // invalid state
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAS }
        assertEquals(2, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("CA", "NY")))
    }

    @Test
    fun `all seven awards present with correct targets`() {
        val progress = AwardCalculator.calculate(emptyMap())
        assertEquals(7, progress.size)
        assertEquals(100, progress.first { it.type == AwardType.DXCC }.target)
        assertEquals(100, progress.first { it.type == AwardType.VUCC }.target)
        assertEquals(34, progress.first { it.type == AwardType.WAPC }.target)
        assertEquals(47, progress.first { it.type == AwardType.WAJA }.target)
        assertEquals(38, progress.first { it.type == AwardType.WAIPA }.target)
        assertEquals(40, progress.first { it.type == AwardType.WAZ }.target)
        assertEquals(50, progress.first { it.type == AwardType.WAS }.target)
    }

    @Test
    fun `award key matches the region codes for every award type`() {
        val cn = qso("BG7XYZ", dxcc = 318, cqz = 24, state = "GD")
        assertEquals("318", AwardCalculator.awardKey(AwardType.DXCC, cn))
        assertEquals("GD", AwardCalculator.awardKey(AwardType.WAPC, cn))
        assertEquals("24", AwardCalculator.awardKey(AwardType.WAZ, cn))
        // DXCC code falls back to a prefix guess when LoTW omitted the field
        assertEquals("152", AwardCalculator.awardKey(AwardType.DXCC, qso("XX9A")))
        assertEquals(null, AwardCalculator.awardKey(AwardType.DXCC, qso("ZS1ABC")))
        // WAPC special entities
        assertEquals("HK", AwardCalculator.awardKey(AwardType.WAPC, qso("VR2X", dxcc = 321)))
        assertEquals("MO", AwardCalculator.awardKey(AwardType.WAPC, qso("XX9A", dxcc = 152)))
        assertEquals("TW", AwardCalculator.awardKey(AwardType.WAPC, qso("BV2A", dxcc = 386)))
        // WAJA: only Japanese prefecture numbers count
        assertEquals("34", AwardCalculator.awardKey(AwardType.WAJA, qso("JH0ABC", dxcc = 339, state = "34")))
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAJA, qso("BG7XYZ", dxcc = 318, state = "34")))
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAJA, qso("JH0ABC", dxcc = 339, state = "GD")))
        // WAS: only US states count
        assertEquals("CA", AwardCalculator.awardKey(AwardType.WAS, qso("W1AW", dxcc = 291, state = "CA")))
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAS, qso("BG7XYZ", dxcc = 318, state = "CA")))
        // out-of-range CQ zone and VUCC have no key
        assertEquals(null, AwardCalculator.awardKey(AwardType.WAZ, qso("BG7XYZ", cqz = 41)))
        assertEquals(null, AwardCalculator.awardKey(AwardType.VUCC, cn))
    }

    @Test
    fun `region qsos dedupe multi-grid copies and sort oldest first`() {
        val older = GridQso(
            call = "BA7AAA", epochMs = 100L, satName = "SO-50", mode = "FM",
            bandUp = "2M", bandDown = "70CM", dxcc = 318
        )
        val newer = GridQso(
            call = "BA7BBB", epochMs = 200L, satName = "SO-50", mode = "FM",
            bandUp = "2M", bandDown = "70CM", dxcc = 318
        )
        val store = mapOf(
            "OL62" to listOf(newer, older),
            "PM95" to listOf(older), // same QSO recorded under a second worked grid
            "OL62,PM01" to listOf(qso("W1AW", dxcc = 291))
        )
        val result = AwardCalculator.regionQsos(store, AwardType.DXCC, "318")
        assertEquals(listOf(older, newer), result)
        assertEquals(emptyList<GridQso>(), AwardCalculator.regionQsos(store, AwardType.DXCC, "292"))
    }
    @Test
    fun `waipa exposes boundary QSOs for manual assignment and persists the chosen province`() {
        val boundary = qso(
            "YB7UCC", dxcc = 327, fineGrids = listOf("OJ82MA")
        )
        val store = mapOf("OJ82" to listOf(boundary))
        val provinceForGrid: (String) -> String? = { null }
        val candidatesForGrid: (String) -> Set<String> = {
            if (it == "OJ82MA" || it == "OJ82") setOf("KI", "KU") else emptySet()
        }

        val pending = AwardCalculator.waipaAmbiguousQsos(
            store, provinceForGrid, candidatesForGrid
        )
        assertEquals(1, pending.size)
        assertEquals(setOf("KI", "KU"), pending.single().candidateProvinceCodes)
        assertEquals(
            null,
            AwardCalculator.awardKey(
                AwardType.WAIPA,
                boundary,
                provinceForGrid,
                workedGrid = "OJ82",
                waipaCandidatesForGrid = candidatesForGrid
            )
        )
        assertEquals(
            setOf("KU"),
            AwardCalculator.waipaPendingProvinceCodes(pending, setOf("KI"))
        )
        assertEquals(
            setOf("KI", "KU"),
            AwardCalculator.waipaPendingProvinceCodes(pending, emptySet())
        )
        assertEquals(
            emptySet<String>(),
            AwardCalculator.waipaPendingProvinceCodes(pending, setOf("KI", "KU"))
        )
        assertEquals(
            AwardCalculator.waipaQsoKey(boundary),
            AwardCalculator.waipaQsoKey(
                boundary.copy(dxcc = null, myCallsign = "YB0AAA", stationKey = "new-station")
            )
        )
        assertEquals(
            emptySet<String>(),
            AwardCalculator.calculate(store, provinceForGrid, candidatesForGrid)
                .first { it.type == AwardType.WAIPA }.workedKeys
        )

        val overrides = mapOf(AwardCalculator.waipaQsoKey(boundary) to "KU")
        val progress = AwardCalculator.calculate(
            store, provinceForGrid, candidatesForGrid, overrides
        ).first { it.type == AwardType.WAIPA }
        assertEquals(setOf("KU"), progress.workedKeys)
        assertTrue(
            AwardCalculator.waipaAmbiguousQsos(
                store, provinceForGrid, candidatesForGrid, overrides
            ).isEmpty()
        )
        assertEquals(
            listOf(boundary),
            AwardCalculator.regionQsos(
                store, AwardType.WAIPA, "KU", provinceForGrid, candidatesForGrid, overrides
            )
        )
        assertTrue(
            AwardCalculator.regionQsos(
                store, AwardType.WAIPA, "KI", provinceForGrid, candidatesForGrid, overrides
            ).isEmpty()
        )
    }

    @Test
    fun `waipa unassigned list includes QSOs with no candidate provinces`() {
        val ambiguous = qso("YB7UCC", dxcc = 327, fineGrids = listOf("OJ82MA"))
        val insufficient = qso("YB0ABC/P", dxcc = 327)
        val assigned = qso("YB0ABC", dxcc = 327, state = "JK")
        val assignedByCallsign = qso("YB0ABC", dxcc = 327)
        val foreign = qso("JH0ABC", dxcc = 339)
        val store = mapOf(
            GRIDLESS_QSO_BUCKET to listOf(ambiguous, insufficient, assigned, assignedByCallsign, foreign)
        )
        val candidatesForGrid: (String) -> Set<String> = {
            if (it == "OJ82MA") setOf("KI", "KU") else emptySet()
        }

        val unassigned = AwardCalculator.waipaUnassignedQsos(
            store, { null }, candidatesForGrid
        )
        assertEquals(listOf(ambiguous, insufficient), unassigned.map { it.qso })
        assertEquals(setOf("KI", "KU"), unassigned[0].candidateProvinceCodes)
        assertEquals(emptySet<String>(), unassigned[1].candidateProvinceCodes)

        val overrides = mapOf(AwardCalculator.waipaQsoKey(insufficient) to "BA")
        val afterManualAssignment = AwardCalculator.waipaUnassignedQsos(
            store, { null }, candidatesForGrid, overrides
        )
        assertEquals(listOf(ambiguous), afterManualAssignment.map { it.qso })
    }

}
