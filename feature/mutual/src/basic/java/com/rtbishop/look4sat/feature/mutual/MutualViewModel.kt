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
package com.rtbishop.look4sat.feature.mutual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt

private const val TAG = "Look4SatMutual"

data class MutualUiState(
    val stationALat: String = "",
    val stationALon: String = "",
    val stationAGrid: String = "",
    val stationAMinElev: Double = 10.0,
    val stationBLat: String = "",
    val stationBLon: String = "",
    val stationBGrid: String = "",
    val stationBMinElev: Double = 10.0,
    val hoursAhead: Int = 24,
    /** 历史过境回看窗口(小时), 跟随主页面 Passes 设置; 匹配查询从
     *  now-hoursBefore 开始, 使设置过历史回看时匹配页也包含历史过境. */
    val hoursBefore: Int = 0,
    /** 转发器类型筛选: 只匹配搭载 FM 话音转发器的卫星. 默认开启. */
    val filterFM: Boolean = true,
    /** 转发器类型筛选: 只匹配搭载线性(USB/LSB/CW/SSB)转发器的卫星. 默认开启. */
    val filterLinear: Boolean = true,
    val mutualPasses: List<MutualPass> = emptyList(),
    val isCalculating: Boolean = false,
    val hasSearched: Boolean = false,
    val selectedPassIndex: Int = -1,
    val isUtc: Boolean = false,
    val errorMessage: String? = null,
    // One-shot flag set by prefillMatchFromGrid() (map grid → Match button):
    // the page scrolls to the time-range card after the first layout, then
    // consumeScrollToTimeRange() clears it.
    val scrollToTimeRange: Boolean = false,
    // One-shot flag set by prefillMatchFromGrid(): the map page waits for the
    // pre-filled query to finish, then navigates to the match page so the very
    // first frame already contains the results (time-range card + pass curves,
    // no scroll flicker). consumePendingNavigation() clears it.
    val pendingNavigation: Boolean = false
)

class MutualViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    // Injected so unit tests can run the pass computation on a test dispatcher;
    // production callers keep the CPU-bound pool default.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Injected so unit tests can pin the wall clock to the fixture TLE epoch and
    // stay deterministic; production callers keep the real clock default.
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _uiState = MutableStateFlow(MutualUiState())
    val uiState: StateFlow<MutualUiState> = _uiState.asStateFlow()

    // Session-scoped scroll position of the pass list (Activity-scoped VM, so it
    // survives switching to another page and back). Reset to the top whenever a
    // new query replaces the results.
    var listScrollIndex: Int = 0
    var listScrollOffset: Int = 0

    /** Bumped on every new query so the list scrolls back to the top. */
    var queryGeneration: Int = 0

    init {
        // Pre-fill station A with the user's current station position (as grid),
        // and default min elevation to the same value used by the main radar passes
        val pos = settingsRepo.stationPosition.value
        val grid = positionToQth(pos.latitude, pos.longitude) ?: ""
        _uiState.update { it.copy(
            stationAGrid = grid,
            stationALat = "%.4f".format(pos.latitude),
            stationALon = "%.4f".format(pos.longitude),
            stationAMinElev = 0.0,
            stationBMinElev = 0.0,
            isUtc = settingsRepo.otherSettings.value.stateOfUtc
        ) }
        // Keep the time display in sync with the UTC toggle in Settings
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update { it.copy(isUtc = settings.stateOfUtc) }
            }
        }
        // 历史过境回看窗口跟随主页面 Passes 设置 (设置里改小时数, 匹配页同步生效)
        viewModelScope.launch {
            settingsRepo.passesSettings.collectLatest { settings ->
                _uiState.update { it.copy(hoursBefore = settings.hoursBefore) }
            }
        }
    }

    fun onStationALat(value: String) {
        _uiState.update { it.copy(stationALat = value) }
        val lat = value.toDoubleOrNull()
        val lon = _uiState.value.stationALon.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationAGrid = grid) }
            }
        }
    }

    fun onStationALon(value: String) {
        _uiState.update { it.copy(stationALon = value) }
        val lat = _uiState.value.stationALat.toDoubleOrNull()
        val lon = value.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationAGrid = grid) }
            }
        }
    }

    fun onStationAGrid(value: String) {
        val old = _uiState.value.stationAGrid
        val newGrid = value.trim().uppercase()
        _uiState.update { it.copy(stationAGrid = newGrid) }
        if (newGrid == old.trim().uppercase()) return
        val pos = qthToPosition(newGrid)
        if (pos != null) {
            _uiState.update { it.copy(
                stationALat = "%.4f".format(pos.latitude),
                stationALon = "%.4f".format(pos.longitude)
            )}
        }
    }

    fun onStationBLat(value: String) {
        _uiState.update { it.copy(stationBLat = value) }
        val lat = value.toDoubleOrNull()
        val lon = _uiState.value.stationBLon.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationBGrid = grid) }
            }
        }
    }

    fun onStationBLon(value: String) {
        _uiState.update { it.copy(stationBLon = value) }
        val lat = _uiState.value.stationBLat.toDoubleOrNull()
        val lon = value.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationBGrid = grid) }
            }
        }
    }

    fun onStationBGrid(value: String) {
        val old = _uiState.value.stationBGrid
        val newGrid = value.trim().uppercase()
        _uiState.update { it.copy(stationBGrid = newGrid) }
        if (newGrid == old.trim().uppercase()) return
        val pos = qthToPosition(newGrid)
        if (pos != null) {
            _uiState.update { it.copy(
                stationBLat = "%.4f".format(pos.latitude),
                stationBLon = "%.4f".format(pos.longitude)
            )}
        }
    }
    fun onStationAMinElev(value: Double) = _uiState.update { it.copy(stationAMinElev = value) }
    fun onStationBMinElev(value: Double) = _uiState.update { it.copy(stationBMinElev = value) }
    fun onUseCurrentPosition() {
        val pos = settingsRepo.stationPosition.value
        _uiState.update { it.copy(
            stationALat = "%.4f".format(pos.latitude),
            stationALon = "%.4f".format(pos.longitude),
            stationAGrid = positionToQth(pos.latitude, pos.longitude) ?: ""
        ) }
    }
    fun onHoursAhead(value: Int) = _uiState.update { it.copy(hoursAhead = value) }
    fun onFilterFM(value: Boolean) = _uiState.update { it.copy(filterFM = value) }
    fun onFilterLinear(value: Boolean) = _uiState.update { it.copy(filterLinear = value) }
    fun onSelectPass(index: Int) = _uiState.update { it.copy(selectedPassIndex = index) }

    /**
     * Pre-fill the match page for a target grid picked from the map's grid-QSO
     * dialog ("Match" button): set the opposite-station grid (with its
     * coordinates), reset the time range to 24h, start the query immediately
     * so results are ready when the page opens, and flag the page to scroll
     * straight to the time-range card once it is laid out.
     */
    fun prefillMatchFromGrid(grid: String) {
        val g = grid.trim().uppercase()
        val pos = qthToPosition(g)
        android.util.Log.d(TAG, "prefillMatchFromGrid('$g')")
        _uiState.update {
            it.copy(
                stationBGrid = g,
                stationBLat = pos?.let { p -> "%.4f".format(p.latitude) } ?: it.stationBLat,
                stationBLon = pos?.let { p -> "%.4f".format(p.longitude) } ?: it.stationBLon,
                hoursAhead = 24,
                scrollToTimeRange = true,
                pendingNavigation = true
            )
        }
        queryMutualPasses()
    }

    fun consumePendingNavigation() {
        android.util.Log.d(TAG, "consumePendingNavigation")
        _uiState.update { it.copy(pendingNavigation = false) }
    }

    fun consumeScrollToTimeRange() {
        android.util.Log.d(TAG, "consumeScrollToTimeRange")
        _uiState.update { it.copy(scrollToTimeRange = false) }
    }

    /**
     * Run a mutual-pass query. `initialScrollIndex` non-null forces the list
     * to rebuild at that position (legacy callers); null keeps the current
     * scroll position, so re-running a query from the match page does not
     * jump back to the top. The previous results stay on screen while the
     * query runs (the list keeps its height, so the scroll position is not
     * clamped away); they are replaced — possibly with empty — when the new
     * results arrive.
     */
    fun queryMutualPasses(initialScrollIndex: Int? = null) {
        val state = _uiState.value

        // Resolve positions from lat/lon or grid
        var posA = resolvePosition(state.stationALat, state.stationALon, state.stationAGrid)
        var posB = resolvePosition(state.stationBLat, state.stationBLon, state.stationBGrid)

        if (posA == null || posB == null) {
            _uiState.update { it.copy(errorMessage = "Please enter valid coordinates or a Maidenhead grid (4/6/8 chars)") }
            return
        }

        val satellites = satelliteRepo.satellites.value
        if (satellites.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No satellite data. Select satellites from the list first.") }
            return
        }

        // 转发器类型筛选: 至少勾选一种(默认 FM + Linear 都开), 否则提示不查询.
        // 防止用户选入大量无转发器卫星占用匹配页.
        if (!state.filterFM && !state.filterLinear) {
            _uiState.update { it.copy(errorMessage = "Select at least one transponder type (FM / Linear) to run the match.") }
            return
        }
        // AMSAT Live 清单(同步自 AMSAT "Live FM/Linear Satellites" 页面)是本过滤的
        // 权威来源: 只匹配当前在轨且话音/线性转发器实际工作的卫星. 清单为空
        // (从未成功同步过)时回退到数据库 downlinkMode 过滤, 避免首启匹配页空结果.
        // 过滤本身在下面的协程里执行 (getSatelliteIdsWithModes 是 suspend).

        _uiState.update {
            it.copy(
                isCalculating = true,
                hasSearched = true,
                errorMessage = null,
                // Deliberately keep mutualPasses: clearing it would shrink the
                // list below one screen and clamp the scroll position away.
                // The stale results are replaced when the new ones arrive.
                selectedPassIndex = -1
            )
        }
        // Scroll position: an explicit initialScrollIndex overrides (legacy
        // callers start at a fixed spot); null keeps the current position so
        // re-querying does not jump back to the top.
        if (initialScrollIndex != null) {
            listScrollIndex = initialScrollIndex
            listScrollOffset = 0
        }
        queryGeneration += 1

        viewModelScope.launch {
            val now = nowMillis()
            // 转发器类型筛选: 使用硬编码 AMSAT Live 清单 (见 Sources):
            // FM = {SO-50, ISS ZARYA, AO-123}, Linear = {RS-44, FO-29,
            // AO-7, AO-73, JO-97}. 不再依赖 AMSAT 网页同步: 任何网络下都
            // 稳定, 没有旧脏列表/超时/同步失败清空等问题.
            val allowed = buildSet {
                if (state.filterFM) addAll(Sources.amSatFmCatnums)
                if (state.filterLinear) addAll(Sources.amSatLinearCatnums)
            }
            val filteredSatellites = satellites.filter { it.data.catnum in allowed }
            // 历史回看: 查询窗口起点 = now - hoursBefore (跟随主页面 Passes 设置),
            // 终点 = now + hoursAhead 不变.
            val time = now - state.hoursBefore * 60L * 60L * 1000L
            val minElevA = state.stationAMinElev
            val minElevB = state.stationBMinElev
            val hours = state.hoursAhead

            val results = try {
                withContext(computeDispatcher) {
                    findMutualPasses(filteredSatellites, posA, posB, minElevA, minElevB, time, hours, state.hoursBefore)
                }
            } catch (t: Throwable) {
                // Never leave the query stuck in "calculating" (which would also
                // block the pending map→match navigation forever).
                android.util.Log.e(TAG, "findMutualPasses threw", t)
                emptyList()
            }

            val errorMsg = if (results.isEmpty()) {
                // Debug: check if the main pass list is the culprit
                val passCount = satelliteRepo.passes.value.size
                val satCount = satellites.size
                when {
                    passCount == 0 -> "Pass list is empty. Select satellites from the list first."
                    satCount == 0 -> "No satellites selected."
                    else -> "No mutual passes found (${passCount} passes, ${satCount} satellites)."
                }
            } else null

            _uiState.update {
                it.copy(
                    mutualPasses = results.sortedBy { mp -> mp.startTime },
                    isCalculating = false,
                    errorMessage = errorMsg
                )
            }
        }
    }

    /** Resolve a position from lat/lon text or grid square text. */
    private fun resolvePosition(latText: String, lonText: String, gridText: String): GeoPos? {
        val trimmed = gridText.trim().uppercase()
        if (trimmed.length in listOf(4, 6, 8) && trimmed.all { it.isLetterOrDigit() }) {
            return qthToPosition(trimmed)
        }
        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()
        return if (lat != null && lon != null) GeoPos(lat, lon) else null
    }

    private fun findMutualPasses(
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, hours: Int, hoursBefore: Int
    ): List<MutualPass> {
        // 终点始终是 now + hoursAhead; 起点已由调用方下移 hoursBefore 小时,
        // 因此这里的 endTime 要在起点之上补回 hoursBefore, 保证窗口 = [now-hb, now+h].
        val endTime = time + (hoursBefore + hours) * 60L * 60L * 1000L
        val sampleInterval = 5_000L

        // Use the main page's pass list for AOS/LOS times, then sample the curves
        // using the actual positions. The passes list is already computed by getLeoPass
        // and its AOS/LOS times match the Passes page exactly.
        // The satellites argument may be a subset (transponder-type filter), so
        // only passes belonging to the filtered satellites are reused.
        val allowedCatnums = satellites.map { it.data.catnum }.toSet()
        val existingPasses = satelliteRepo.passes.value.filter { it.catNum in allowedCatnums }
        val results = findMutualPassesFromList(existingPasses, satellites, posA, posB,
            minElevADeg, minElevBDeg, time, endTime, sampleInterval)
        if (results.isNotEmpty()) return results

        // Fallback: try the independent search.
        return findMutualPassesFallback(satellites, posA, posB,
            minElevADeg, minElevBDeg, time, endTime, sampleInterval)
    }

    /** Reuse the main page's pass list. Uses AOS/LOS times directly (no refineEdge) */
    private fun findMutualPassesFromList(
        existingPasses: List<OrbitalPass>,
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, endTime: Long, sampleInterval: Long
    ): List<MutualPass> {
        val results = mutableListOf<MutualPass>()
        for (pass in existingPasses) {
            if (pass.losTime <= time || pass.aosTime >= endTime) continue
            if (pass.isDeepSpace) continue
            if (pass.orbitalObject.data.meanmo < 1e-8) continue

            val sat = pass.orbitalObject
            // Refine the AOS/LOS to the actual posA/posB (0° horizon).
            // This ensures the elevation curve starts from ~0°.
            val refinedAos = refineEdge(sat, posA, posB, pass.aosTime, 1_000L, goingUp = true)
            val refinedLos = refineEdge(sat, posA, posB, pass.losTime, 1_000L, goingUp = false)
            if (refinedLos <= refinedAos) continue

            val samples = mutableListOf<Pair<Long, Pair<Double, Double>>>()
            val tracks = mutableListOf<TrackSample>()
            var maxElevA = 0.0
            var maxElevB = 0.0

            var tSample = refinedAos
            while (tSample <= refinedLos) {
                // Use getElevation (same function used by getLeoPass) for elevation,
                // and getFullPosition only for azimuth.
                val elevA = sat.getElevation(posA, tSample) * 180.0 / PI
                val elevB = sat.getElevation(posB, tSample) * 180.0 / PI
                val fullA = sat.getFullPosition(posA, tSample)
                val fullB = sat.getFullPosition(posB, tSample)
                if (elevA > maxElevA) maxElevA = elevA
                if (elevB > maxElevB) maxElevB = elevB
                samples.add(tSample to (elevA to elevB))
                tracks.add(
                    TrackSample(
                        time = tSample,
                        azimuthA = fullA.azimuth * 180.0 / PI,
                        elevationA = elevA,
                        azimuthB = fullB.azimuth * 180.0 / PI,
                        elevationB = elevB
                    )
                )
                tSample += sampleInterval
            }

            // Search uses the 0° horizon as the pass boundary, but the user sliders are
            // a final visibility filter. Both stations must exceed their own threshold;
            // otherwise the result would expand to an empty filtered chart.
            if (maxElevA > minElevADeg && maxElevB > minElevBDeg) {
                results.add(
                    MutualPass(
                        catNum = pass.catNum,
                        name = pass.orbitalObject.data.name,
                        startTime = refinedAos,
                        endTime = refinedLos,
                        maxElevationA = (maxElevA * 10).roundToInt() / 10.0,
                        maxElevationB = (maxElevB * 10).roundToInt() / 10.0,
                        elevationSamples = samples,
                        trackSamples = tracks
                    )
                )
            }
        }
        return results
    }

    /** Fallback: search passes independently (same logic as original findMutualPasses). */
    private fun findMutualPassesFallback(
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, endTime: Long, sampleInterval: Long
    ): List<MutualPass> {
        val results = mutableListOf<MutualPass>()
        for (sat in satellites) {
            if (sat.data.meanmo < 1e-8) continue
            var searchStart = time
            while (true) {
                val t = findNextMutualPass(sat, posA, posB, searchStart, endTime)
                if (t == null) break
                val (aos, los) = t

                val refinedAos = refineEdge(sat, posA, posB, aos, 1_000L, true)
                val refinedLos = refineEdge(sat, posA, posB, los, 1_000L, false)
                if (refinedLos <= refinedAos) continue

                val mutualPass = sampleMutualPass(sat, posA, posB, refinedAos, refinedLos,
                    minElevADeg, minElevBDeg, sampleInterval)
                if (mutualPass != null) results.add(mutualPass)
                searchStart = refinedLos + 120_000L
            }
        }
        return results
    }

    /** Sample elevation/azimuth data for one mutual pass window. */
    private fun sampleMutualPass(
        sat: OrbitalObject, posA: GeoPos, posB: GeoPos,
        refinedAos: Long, refinedLos: Long,
        minElevADeg: Double, minElevBDeg: Double,
        sampleInterval: Long
    ): MutualPass? {
        val samples = mutableListOf<Pair<Long, Pair<Double, Double>>>()
        val tracks = mutableListOf<TrackSample>()
        var maxElevA = 0.0
        var maxElevB = 0.0

        var tSample = refinedAos
        while (tSample <= refinedLos) {
            // Use getElevation for elevation, matching the main pass predictor; use
            // getFullPosition only for azimuth needed by the radar plot.
            val elevA = elevationDeg(sat, posA, tSample)
            val elevB = elevationDeg(sat, posB, tSample)
            val fullA = sat.getFullPosition(posA, tSample)
            val fullB = sat.getFullPosition(posB, tSample)
            if (elevA > maxElevA) maxElevA = elevA
            if (elevB > maxElevB) maxElevB = elevB
            samples.add(tSample to (elevA to elevB))
            tracks.add(
                TrackSample(
                    time = tSample,
                    azimuthA = fullA.azimuth * 180.0 / PI,
                    elevationA = elevA,
                    azimuthB = fullB.azimuth * 180.0 / PI,
                    elevationB = elevB
                )
            )
            tSample += sampleInterval
        }

        if (maxElevA > minElevADeg && maxElevB > minElevBDeg) {
            return MutualPass(
                catNum = sat.data.catnum,
                name = sat.data.name,
                startTime = refinedAos,
                endTime = refinedLos,
                maxElevationA = (maxElevA * 10).roundToInt() / 10.0,
                maxElevationB = (maxElevB * 10).roundToInt() / 10.0,
                elevationSamples = samples,
                trackSamples = tracks
            )
        }
        return null
    }

    /** Refine the AOS (goingUp=true) or LOS (goingUp=false) to ~1s precision at the 0° horizon. */
    private fun refineEdge(
        sat: OrbitalObject, posA: GeoPos, posB: GeoPos,
        approxTime: Long, step: Long, goingUp: Boolean
    ): Long {
        if (goingUp) {
            // AOS: walk backward from approxTime to find the last sample where either is below
            // the horizon, then AOS is the next step after that.
            var t = approxTime
            while (t > approxTime - 70_000L) {
                val eA = elevationDeg(sat, posA, t)
                val eB = elevationDeg(sat, posB, t)
                if (eA > 0.0 && eB > 0.0) {
                    t -= step
                } else {
                    return t + step
                }
            }
            return approxTime - 70_000L + step
        } else {
            // LOS: walk forward from approxTime to find the first sample where either drops
            // below the horizon, then LOS is the step before that.
            var t = approxTime
            while (t < approxTime + 70_000L) {
                val eA = elevationDeg(sat, posA, t)
                val eB = elevationDeg(sat, posB, t)
                if (eA > 0.0 && eB > 0.0) {
                    t += step
                } else {
                    return t - step
                }
            }
            return approxTime + 70_000L - step
        }
    }

    private fun elevationDeg(sat: OrbitalObject, pos: GeoPos, time: Long): Double {
        return sat.getElevation(pos, time) * 180.0 / PI
    }

    private fun findNextMutualPass(
        sat: OrbitalObject,
        posA: GeoPos, posB: GeoPos,
        startTime: Long, endTime: Long
    ): Pair<Long, Long>? {
        var t = startTime
        val step = 60_000L

        // Skip an in-progress mutual window at the search start (same as getLeoPass):
        // walk forward until either station drops below the horizon, then keep searching.
        if (elevationDeg(sat, posA, t) > 0.0 && elevationDeg(sat, posB, t) > 0.0) {
            while (t < endTime) {
                if (elevationDeg(sat, posA, t) <= 0.0 || elevationDeg(sat, posB, t) <= 0.0) break
                t += step
            }
        }

        while (t < endTime) {
            val elevA = elevationDeg(sat, posA, t)
            val elevB = elevationDeg(sat, posB, t)

            if (elevA > 0.0 && elevB > 0.0) {
                var aos = t
                var rew = t
                while (rew > startTime - 600_000L) {
                    val eA = elevationDeg(sat, posA, rew)
                    val eB = elevationDeg(sat, posB, rew)
                    if (eA <= 0.0 || eB <= 0.0) {
                        aos = rew + step
                        break
                    }
                    rew -= step
                }

                var los = t
                var fwd = t
                while (fwd < endTime + 600_000L) {
                    val eA = elevationDeg(sat, posA, fwd)
                    val eB = elevationDeg(sat, posB, fwd)
                    if (eA <= 0.0 || eB <= 0.0) {
                        los = fwd
                        break
                    }
                    fwd += step
                }

                if (los > aos) return Pair(aos, los)
            }
            t += step
        }
        return null
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    companion object {
        fun factory(container: IMainContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MutualViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo
                ) as T
        }
    }
}