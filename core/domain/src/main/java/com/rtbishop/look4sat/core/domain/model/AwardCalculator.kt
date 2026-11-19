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

/**
 * Award progress computed from the confirmed-QSO store.
 *
 * @param type      which award
 * @param workedKeys matching keys for the boundary assets: each is compared
 *                   against the region "code" field of the award's GeoJSON
 *                   (e.g. WAPC -> "GD", WAJA -> "34", WAZ -> "24",
 *                   WAS -> "CA", DXCC -> "318", VUCC -> "OL62").
 * @param count     distinct worked units (grids / entities / provinces / ...)
 * @param target    units required for the award
 */
data class AwardProgress(
    val type: AwardType,
    val workedKeys: Set<String>,
    val count: Int,
    val target: Int
)

enum class AwardType { DXCC, VUCC, WAPC, WAJA, WAIPA, WAZ, WAS }

data class WaipaAmbiguousQso(
    val qso: GridQso,
    val candidateProvinceCodes: Set<String>,
    val recommendedProvinceCode: String? = null
)

object AwardTargets {
    const val DXCC = 100
    const val VUCC = 100
    const val WAPC = 34
    const val WAJA = 47
    const val WAIPA = 38
    const val WAZ = 40
    const val WAS = 50
}

/**
 * Derives seven-award progress from the confirmed QSO store.
 *
 * Award rules (ADIF / award-program semantics):
 *  - VUCC: distinct 4-char gridsquares.
 *  - DXCC: distinct ARRL DXCC entities (from the QSO's dxcc field; falls back
 *    to a callsign-prefix guess when LoTW omitted it).
 *  - WAPC: distinct Chinese provinces (31 mainland STATE codes) plus the
 *    Hong Kong / Macao / Taiwan entities (counted as their own provinces).
 *  - WAJA: distinct Japanese prefectures (STATE code "01".."47").
 *  - WAIPA: 38 provinces. Uses manual assignments, STATE, operating grids,
 *    IOTA and conservative IIHG callsign allocation.
 *  - WAZ: distinct CQ zones.
 *  - WAS: distinct US states (STATE abbreviations).
 *
 * The ADIF STATE field's meaning depends on the DXCC entity
 * ("STATE depends on DXCC"), so every QSO is first classified by entity.
 */
object AwardCalculator {

    fun calculate(
        qsosByGrid: Map<String, List<GridQso>>,
        waipaProvinceForGrid: (String) -> String? = { null },
        waipaCandidatesForGrid: (String) -> Set<String> = { emptySet() },
        waipaManualOverrides: Map<String, String> = emptyMap()
    ): List<AwardProgress> {
        val all = qsosByGrid.values.flatten()
        return listOf(
            AwardProgress(
                AwardType.VUCC,
                workedKeys = qsosByGrid.keys - GRIDLESS_QSO_BUCKET,
                count = (qsosByGrid.keys - GRIDLESS_QSO_BUCKET).size,
                target = AwardTargets.VUCC
            ),
            calculateDxcc(all),
            calculateWapc(all),
            calculateWaja(all),
            calculateWaipa(qsosByGrid, waipaProvinceForGrid, waipaCandidatesForGrid, waipaManualOverrides),
            calculateWaz(all),
            calculateWas(all)
        )
    }

    /**
     * The award unit one QSO counts toward for [type] — the match key of the
     * award boundary assets' region "code" (WAPC -> "GD"/"HK", WAJA -> "34",
     * WAZ -> "24", WAS -> "CA", DXCC -> "318"). Null when the QSO does not
     * count toward that award (wrong entity or missing field). VUCC has no
     * per-QSO key — its units are the store's grid keys — and returns null.
     *
     * Single source of truth shared by the progress counters above and the
     * map's region-detail dialogs, so a tapped region lists exactly the QSOs
     * that light it up.
     */
    fun awardKey(
        type: AwardType,
        q: GridQso,
        waipaProvinceForGrid: (String) -> String? = { null },
        workedGrid: String? = null,
        waipaCandidatesForGrid: (String) -> Set<String> = { emptySet() },
        waipaManualOverrides: Map<String, String> = emptyMap()
    ): String? = when (type) {
        AwardType.DXCC -> (q.dxcc ?: prefixEntityCode(q.call))?.toString()
        AwardType.WAPC -> wapcKey(q)
        AwardType.WAJA -> if (entityOf(q) == Entity.JAPAN) {
            q.state?.trim()?.takeIf { it.length == 2 && it.all(Char::isDigit) }
        } else null

        AwardType.WAIPA -> resolveWaipaProvince(
            q, waipaProvinceForGrid, listOfNotNull(workedGrid),
            waipaCandidatesForGrid, waipaManualOverrides
        )?.provinceCode
        AwardType.WAZ -> q.cqz?.takeIf { it in 1..40 }?.toString()
        AwardType.WAS -> if (entityOf(q) == Entity.USA) {
            q.state?.trim()?.uppercase()?.takeIf { it in US_STATES }
        } else null

        AwardType.VUCC -> null
    }

    /**
     * Every confirmed QSO counted for award region [code] of [type], oldest
     * first. The store keeps one entry per WORKED GRID, so a QSO spanning
     * several grids arrives as several copies — [distinct] collapses them.
     * Not scoped by 台址: the non-VUCC awards count (and fill the map)
     * globally, so their detail lists do too.
     */
    fun regionQsos(
        qsosByGrid: Map<String, List<GridQso>>,
        type: AwardType,
        code: String,
        waipaProvinceForGrid: (String) -> String? = { null },
        waipaCandidatesForGrid: (String) -> Set<String> = { emptySet() },
        waipaManualOverrides: Map<String, String> = emptyMap()
    ): List<GridQso> =
        if (type == AwardType.WAIPA) {
            resolvedWaipaQsos(qsosByGrid, waipaProvinceForGrid, waipaCandidatesForGrid, waipaManualOverrides)
                .filter { (_, resolution) -> resolution.provinceCode == code }
                .map { (qso, _) -> qso }
                .sortedBy { it.epochMs }
        } else {
            qsosByGrid.values.flatten()
                .filter { awardKey(type, it) == code }
                .distinct().sortedBy { it.epochMs }
        }

    private fun wapcKey(q: GridQso): String? = when (entityOf(q)) {
        Entity.CHINA -> q.state?.trim()?.uppercase()?.takeIf { it in CN_STATES }
        Entity.HONG_KONG -> HK_CODE
        Entity.MACAO -> MO_CODE
        Entity.TAIWAN -> TW_CODE
        else -> null
    }

    private fun calculateDxcc(all: List<GridQso>): AwardProgress {
        val keys = all.mapNotNull { awardKey(AwardType.DXCC, it) }.toSet()
        return AwardProgress(AwardType.DXCC, keys, keys.size, AwardTargets.DXCC)
    }

    private fun calculateWapc(all: List<GridQso>): AwardProgress {
        val keys = all.mapNotNull { awardKey(AwardType.WAPC, it) }.toSet()
        return AwardProgress(AwardType.WAPC, keys, keys.size, AwardTargets.WAPC)
    }

    private fun calculateWaja(all: List<GridQso>): AwardProgress {
        val keys = all.mapNotNull { awardKey(AwardType.WAJA, it) }.toSet()
        return AwardProgress(AwardType.WAJA, keys, keys.size, AwardTargets.WAJA)
    }

    /**
     * A contact can appear under multiple four-character grid keys. Resolve
     * those *together* so one historical multi-grid contact cannot award two
     * provinces just because its coarse squares straddle a border.
     */
    private fun resolvedWaipaQsos(
        byGrid: Map<String, List<GridQso>>,
        provinceForGrid: (String) -> String?,
        candidatesForGrid: (String) -> Set<String> = { emptySet() },
        manualOverrides: Map<String, String> = emptyMap()
    ): List<Pair<GridQso, WaipaResolution>> {
        val gridsByQso = linkedMapOf<GridQso, MutableSet<String>>()
        byGrid.forEach { (grid, qsos) ->
            qsos.forEach { q ->
                val grids = gridsByQso.getOrPut(q) { linkedSetOf() }
                if (grid != GRIDLESS_QSO_BUCKET) grids.add(grid)
            }
        }
        return gridsByQso.mapNotNull { (q, grids) ->
            resolveWaipaProvince(q, provinceForGrid, grids, candidatesForGrid, manualOverrides)
                ?.let { q to it }
        }
    }

    private fun calculateWaipa(
        byGrid: Map<String, List<GridQso>>,
        provinceForGrid: (String) -> String?,
        candidatesForGrid: (String) -> Set<String>,
        manualOverrides: Map<String, String>
    ): AwardProgress {
        val keys = resolvedWaipaQsos(byGrid, provinceForGrid, candidatesForGrid, manualOverrides)
            .mapNotNull { (_, resolution) -> resolution.provinceCode }.toSet()
        return AwardProgress(AwardType.WAIPA, keys, keys.size, AwardTargets.WAIPA)
    }

    private data class WaipaResolution(
        val provinceCode: String? = null,
        val candidates: Set<String> = emptySet(),
        val recommendation: String? = null
    )

    private fun resolveWaipaProvince(
        q: GridQso,
        provinceForGrid: (String) -> String?,
        workedGrids: Collection<String>,
        candidatesForGrid: (String) -> Set<String>,
        manualOverrides: Map<String, String>
    ): WaipaResolution? {
        if (q.dxcc != null && q.dxcc != 327) return null
        if (entityOf(q) != Entity.INDONESIA) return null

        manualOverrides[waipaQsoKey(q)]?.trim()?.uppercase()
            ?.takeIf { it in ID_PROVINCES }?.let { return WaipaResolution(provinceCode = it) }

        val state = q.state?.trim()?.uppercase()?.removePrefix("ID-")
        if (state != null && state in ID_PROVINCES) return WaipaResolution(provinceCode = state)

        val fineGrids = q.theirFineGrids.map { it.trim().uppercase() }
            .filter { it.length == 6 || it.length == 8 }.distinct()
        val fineMatches = fineGrids.mapNotNull(provinceForGrid).filter { it in ID_PROVINCES }.toSet()
        val fineCandidates = fineGrids.flatMap(candidatesForGrid).filter { it in ID_PROVINCES }.toSet() +
            fineMatches
        if (fineCandidates.size <= 1) {
            fineMatches.singleOrNull()?.let { return WaipaResolution(provinceCode = it) }
        }

        val iotaProvince = WaipaIotaLookup.province(q.iota)
        if (iotaProvince != null && (fineCandidates.size <= 1 || iotaProvince in fineCandidates)) {
            return WaipaResolution(provinceCode = iotaProvince)
        }

        val geographicCandidates = if (fineCandidates.size > 1) {
            fineCandidates
        } else {
            val coarseGrids = (q.theirGrids + workedGrids)
                .map { it.trim().uppercase().take(4) }.distinct()
            val coarseMatches = coarseGrids.mapNotNull(provinceForGrid)
                .filter { it in ID_PROVINCES }.toSet()
            val coarseCandidates = coarseGrids.flatMap(candidatesForGrid)
                .filter { it in ID_PROVINCES }.toSet() + coarseMatches
            if (coarseCandidates.size <= 1) {
                coarseMatches.singleOrNull()?.let { return WaipaResolution(provinceCode = it) }
            }
            coarseCandidates.ifEmpty { fineCandidates }
        }

        val callsignCandidates = WaipaCallsignLookup.candidates(q.call)
        if (geographicCandidates.size > 1) {
            return WaipaResolution(
                candidates = geographicCandidates,
                recommendation = geographicCandidates.intersect(callsignCandidates).singleOrNull()
            )
        }
        callsignCandidates.singleOrNull()?.let { return WaipaResolution(provinceCode = it) }
        return WaipaResolution(candidates = geographicCandidates.ifEmpty { callsignCandidates })
    }

    /**
     * QSO fingerprint for a user-confirmed WAIPA province. It deliberately
     * excludes optional DXCC, station and locator metadata so a later sync
     * that enriches a record does not orphan the user's choice. The same QSO is repeated under each
     * worked grid in the map cache, so only stable contact identity belongs here.
     */
    fun waipaQsoKey(q: GridQso): String = listOf(
        q.call.trim().uppercase(),
        q.epochMs.toString(),
        q.satName.trim().uppercase(),
        q.mode.trim().uppercase(),
        q.bandUp.trim().uppercase(),
        q.bandDown.trim().uppercase()
    ).joinToString("|") { "${it.length}:$it" }

    /**
     * QSOs whose province cannot be resolved automatically but have multiple
     * plausible Indonesian provinces. These are used only for the WAIPA map's
     * pending (yellow) province fill; they never contribute to award progress.
     */
    fun waipaAmbiguousQsos(
        qsosByGrid: Map<String, List<GridQso>>,
        waipaProvinceForGrid: (String) -> String?,
        waipaCandidatesForGrid: (String) -> Set<String>,
        waipaManualOverrides: Map<String, String> = emptyMap()
    ): List<WaipaAmbiguousQso> =
        waipaUnassignedQsos(qsosByGrid, waipaProvinceForGrid, waipaCandidatesForGrid, waipaManualOverrides)
            .filter { it.candidateProvinceCodes.size > 1 }

    /**
     * Every confirmed Indonesian QSO without a resolved province, including
     * contacts with no candidates so the user can pick from all provinces.
     */
    fun waipaUnassignedQsos(
        qsosByGrid: Map<String, List<GridQso>>,
        waipaProvinceForGrid: (String) -> String?,
        waipaCandidatesForGrid: (String) -> Set<String>,
        waipaManualOverrides: Map<String, String> = emptyMap()
    ): List<WaipaAmbiguousQso> =
        resolvedWaipaQsos(qsosByGrid, waipaProvinceForGrid, waipaCandidatesForGrid, waipaManualOverrides)
            .mapNotNull { (q, resolution) ->
                if (resolution.provinceCode != null) null
                else WaipaAmbiguousQso(q, resolution.candidates, resolution.recommendation)
            }
            .distinctBy { waipaQsoKey(it.qso) }
            .sortedBy { it.qso.epochMs }

    /** Pending province fills never cover a region that is already worked. */
    fun waipaPendingProvinceCodes(
        pending: List<WaipaAmbiguousQso>,
        workedCodes: Set<String>
    ): Set<String> = pending.flatMap { it.candidateProvinceCodes }.toSet() - workedCodes

    private fun calculateWaz(all: List<GridQso>): AwardProgress {
        val keys = all.mapNotNull { awardKey(AwardType.WAZ, it) }.toSet()
        return AwardProgress(AwardType.WAZ, keys, keys.size, AwardTargets.WAZ)
    }

    private fun calculateWas(all: List<GridQso>): AwardProgress {
        val keys = all.mapNotNull { awardKey(AwardType.WAS, it) }.toSet()
        return AwardProgress(AwardType.WAS, keys, keys.size, AwardTargets.WAS)
    }

    private enum class Entity { CHINA, HONG_KONG, MACAO, TAIWAN, JAPAN, INDONESIA, USA, OTHER }

    private fun entityOf(q: GridQso): Entity = when (q.dxcc) {
        318 -> Entity.CHINA
        321 -> Entity.HONG_KONG
        152 -> Entity.MACAO
        386 -> Entity.TAIWAN
        339 -> Entity.JAPAN
        327 -> Entity.INDONESIA
        291 -> Entity.USA
        else -> prefixEntity(q.call)
    }

    /** Coarse entity from callsign prefix, used when LoTW omitted the DXCC field. */
    private fun prefixEntity(call: String): Entity {
        val c = call.uppercase().substringBefore('/')
        return when {
            CN_PREFIXES.any { c.startsWith(it) } -> Entity.CHINA
            c.startsWith("VR") -> Entity.HONG_KONG
            c.startsWith("XX9") -> Entity.MACAO
            TW_PREFIXES.any { c.startsWith(it) } -> Entity.TAIWAN
            JP_PREFIXES.any { c.startsWith(it) } -> Entity.JAPAN
            ID_PREFIXES.any { c.startsWith(it) } -> Entity.INDONESIA
            US_PREFIXES.any { c.startsWith(it) } -> Entity.USA
            else -> Entity.OTHER
        }
    }

    /** ARRL DXCC entity code guessed from callsign prefix (very coarse). */
    private fun prefixEntityCode(call: String): Int? {
        return when (prefixEntity(call)) {
            Entity.CHINA -> 318
            Entity.HONG_KONG -> 321
            Entity.MACAO -> 152
            Entity.TAIWAN -> 386
            Entity.JAPAN -> 339
            Entity.INDONESIA -> 327
            Entity.USA -> 291
            else -> null
        }
    }

    private val CN_PREFIXES = listOf("BA", "BD", "BG", "BH", "BI", "BY", "BJ", "BK", "BL", "BQ", "BR", "BS")
    private val TW_PREFIXES = listOf("BM", "BV", "BU", "BW", "BX", "BN", "BO", "BT", "BZ")
    private val JP_PREFIXES =
        listOf("7J", "7K", "7L", "7M", "7N") + ('A'..'Z').map { "J$it" }
    // Prefixes identify the Indonesian DXCC entity, not the province.
    private val ID_PREFIXES =
        listOf("YB", "YC", "YD", "YE", "YF", "YG", "YH") +
            ('A'..'I').map { "7$it" } + ('A'..'I').map { "8$it" }
    private val ID_PROVINCES = setOf(
        "AC", "BA", "BB", "BE", "BT", "GO", "JA", "JB", "JI", "JK", "JT", "KB", "KI", "KR",
        "KS", "KT", "KU", "LA", "MA", "MU", "NB", "NT", "PA", "PB", "PD", "PP", "PS", "PT", "RI",
        "SA", "SB", "SG", "SN", "SR", "SS", "ST", "SU", "YO"
    )
    private val US_PREFIXES =
        listOf("AA", "AB", "AC", "AD", "AE", "AF", "AG", "AH", "AI", "AJ", "AK", "AL", "K", "N", "W")

    private val CN_STATES = setOf(
        "AH", "BJ", "CQ", "FJ", "GD", "GS", "GX", "GZ", "HA", "HB", "HE", "HI", "HL", "HN", "JL",
        "JS", "JX", "LN", "NM", "NX", "QH", "SC", "SD", "SH", "SN", "SX", "TJ", "XJ", "XZ", "YN", "ZJ"
    )

    private val US_STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA",
        "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT",
        "VA", "WA", "WV", "WI", "WY"
    )

    private const val HK_CODE = "HK"
    private const val MO_CODE = "MO"
    private const val TW_CODE = "TW"
}
