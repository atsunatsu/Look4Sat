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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.repository.ISelectionRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.Sources
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionRepo(
    private val dispatcher: CoroutineDispatcher,
    private val localSource: ILocalSource,
    private val settingsRepo: ISettingsRepo
) : ISelectionRepo {

    private val currentItems = MutableStateFlow<List<SatItem>>(emptyList())
    private val currentTypes = MutableStateFlow(settingsRepo.selectedTypes.value)
    private val currentQuery = MutableStateFlow("")

    // Resolve type IDs once when types change, then filter items reactively.
    // The HashSet gives O(1) catnum lookups instead of O(n) with a List.
    // The three virtual types ("AMSAT Live FM", "AMSAT Live Linear", "Live SSTV")
    // are resolved from hardcoded FM/Linear catnum sets (Sources) or live
    // SSTV transponder data instead of SharedPreferences.
    private val itemsWithTypes = currentTypes.flatMapLatest { types: List<String> ->
        // "All" includes every satellite stored locally, not just the
        // CelesTrak active source's last downloaded catnum list.
        val catnumSet: Set<Int>? = if (types.isEmpty() || Sources.allSourceType in types) {
            null // null = no filtering
        } else {
            resolveTypeIds(types).toHashSet()
        }
        currentItems.map { items ->
            if (catnumSet == null) items else items.filter { it.catnum in catnumSet }
        }
    }

    // Selected satellites are pinned to the top (matches upstream); ordering
    // within each group stays name/catnum. Sits on the reactive chain, so
    // ticking an entry re-sorts the visible list immediately.
    private val itemsWithQuery = currentQuery.flatMapLatest { query ->
        itemsWithTypes.map { items ->
            filterByQuery(items, query).sortedWith(
                compareByDescending<SatItem> { it.isSelected }
                    .thenBy { it.name }
                    .thenBy { it.catnum }
            )
        }
    }

    override fun getCurrentTypes() = currentTypes.value

    /**
     * Resolves a list of type names to satellite catnums. The three virtual
     * transponder/activity types are resolved from hardcoded FM/Linear lists
     * (Sources.amSatFmCatnums / amSatLinearCatnums) or local SSTV transponder
     * data; all other types come from the per-type ID lists persisted by
     * [ISettingsRepo].
     */
    private suspend fun resolveTypeIds(types: List<String>): List<Int> {
        val idsSet = mutableSetOf<Int>()
        types.forEach { type ->
            when (type) {
                Sources.virtualTypeNames[0] -> idsSet.addAll(Sources.amSatFmCatnums)
                Sources.virtualTypeNames[1] -> idsSet.addAll(Sources.amSatLinearCatnums)
                // Live SSTV: mode=SSTV transponder records whose service class
                // is "Amateur", excluding launcher debris / rocket bodies
                // (names ending in "R/B" or "DEB" — e.g. Ariane 6 R/B) that
                // carry an amateur payload transponder but are not satellites.
                Sources.virtualTypeNames[2] -> {
                    val sstvIds = localSource.getIdsWithModesAndAmateur(listOf("SSTV")).toSet()
                    val inOrbitNames = currentItems.value.associate { it.catnum to it.name }
                    val filtered = sstvIds.filter { catnum ->
                        val name = inOrbitNames[catnum]?.uppercase().orEmpty()
                        !name.endsWith(" R/B") && !name.endsWith(" DEB") &&
                            !name.endsWith("R/B") && !name.endsWith("DEB")
                    }
                    idsSet.addAll(filtered)
                }
                else -> idsSet.addAll(settingsRepo.getSatelliteTypesIds(listOf(type)))
            }
        }
        return idsSet.toList()
    }

    override fun getTypesList() = buildList {
        // 三个转发器/活动虚拟类型排在最前.
        addAll(Sources.virtualTypeNames)
        // 所有真实 TLE 源类型均可选（"All" 不筛选本地数据库）；只排除
        // "Other"（空 URL 占位，无数据）。不要用 removeAt(0) —— 字母序第一个
        // 是 "All"/"ARISS" 这类真实源，删掉会让用户勾不到它们。
        addAll(Sources.satelliteDataUrls.keys.sorted().filterNot { it == "Other" })
    }

    override suspend fun getEntriesFlow() = withContext(dispatcher) {
        val selectedIds = settingsRepo.selectedIds.value.toHashSet()
        currentItems.value = localSource.getEntriesList().map { item ->
            item.copy(isSelected = item.catnum in selectedIds)
        }
        return@withContext itemsWithQuery
    }

    override suspend fun setTypes(types: List<String>) {
        currentTypes.value = types
        settingsRepo.setSelectedTypes(types)
    }

    override suspend fun setQuery(query: String) {
        currentQuery.value = query
    }

    override suspend fun setSelection(selectAll: Boolean) = withContext(dispatcher) {
        val visibleIds = itemsWithQuery.first().mapTo(HashSet()) { it.catnum }
        setSelection(visibleIds, selectAll)
    }

    override suspend fun setSelection(ids: List<Int>, isTicked: Boolean) = withContext(dispatcher) {
        val idSet = ids.toHashSet()
        currentItems.value = currentItems.value.map { item ->
            if (item.catnum in idSet) item.copy(isSelected = isTicked) else item
        }
    }

    override suspend fun saveSelection() = withContext(dispatcher) {
        val currentSelection = currentItems.value.filter { it.isSelected }.map { it.catnum }
        settingsRepo.setSelectedIds(currentSelection)
    }

    /**
     * Bulk selection using a pre-built Set for O(1) lookups.
     */
    private suspend fun setSelection(idSet: Set<Int>, isTicked: Boolean) = withContext(dispatcher) {
        currentItems.value = currentItems.value.map { item ->
            if (item.catnum in idSet) item.copy(isSelected = isTicked) else item
        }
    }

    /**
     * Filters items by query. Uses toIntOrNull() instead of exception-based flow,
     * and lowercases the query once up front instead of per-item.
     *
     * Fuzzy search: the query is split into space-separated tokens and every
     * token must appear in the satellite name after both sides are normalized
     * (lowercased, non-alphanumeric separators such as dashes, spaces, brackets
     * and dots stripped). This makes "ao7" match "AO-7 (AMSAT-OSCAR 7)" and
     * "iss zarya" match "ISS (ZARYA)" — exact continuous-substring matching
     * previously failed whenever the name contained a separator the query lacked.
     */
    private fun filterByQuery(items: List<SatItem>, query: String): List<SatItem> {
        if (query.isBlank()) return items
        val catnum = query.toIntOrNull()
        if (catnum != null) return items.filter { it.catnum == catnum }
        val tokens = query.split(' ')
            .map { normalizeForSearch(it) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return items
        return items.filter { item ->
            val normalizedName = normalizeForSearch(item.name)
            tokens.all { normalizedName.contains(it) }
        }
    }

    /** Lowercases and strips all non-alphanumeric chars for fuzzy matching. */
    private fun normalizeForSearch(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}
