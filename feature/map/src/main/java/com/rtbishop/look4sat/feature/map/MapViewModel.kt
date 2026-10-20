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
package com.rtbishop.look4sat.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.predict.CelestialComputer
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.presentation.RadarPassMapDestination
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.clipLat
import com.rtbishop.look4sat.core.domain.utility.clipLon
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.toMapGeoPos
import com.rtbishop.look4sat.core.domain.utility.toDegrees
import com.rtbishop.look4sat.core.domain.utility.toTimerString
import com.rtbishop.look4sat.core.presentation.getDefaultPass
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs

class MapViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private var passWindow: RadarPassMapDestination? = null
) : ViewModel() {

    private val stationPos = settingsRepo.stationPosition.value
    private val defaultPass = getDefaultPass()
    private val _uiState = MutableStateFlow(
        MapState(
            isLightUi = settingsRepo.otherSettings.value.stateOfLightTheme,
            isUtc = settingsRepo.otherSettings.value.stateOfUtc,
            mapSource = settingsRepo.otherSettings.value.mapSource,
            tiandituKey = settingsRepo.otherSettings.value.tiandituKey,
            orbitalPass = defaultPass,
            mapTimeMs = System.currentTimeMillis()
        )
    )
    private var allPasses = satelliteRepo.passes.value
    private var allSatellites = satelliteRepo.satellites.value
    private var dataUpdateJob: Job? = null
    /** At most one dedicated-pass prediction/ticker may own this MapViewModel. */
    private var passLoadJob: Job? = null
    private var dataUpdateRate = 1000L
    private var selectedOrbitalObject: OrbitalObject? = null
    private var radarPass: OrbitalPass? = null
    @Volatile private var passTimePinned = false
    private val passTimeRequest = MutableStateFlow<Long?>(null)
    /** Time-scrub offset ahead of "now" (0 = live). The tick loop reads it on Default while
     *  slider actions arrive on Main, hence @Volatile. */
    @Volatile
    private var timeOffsetMs = 0L
    /** True while the time slider is under the finger: the tick loop stands down so the scrub
     *  handler is the only writer and the all-satellite batch isn't recomputed per frame. */
    @Volatile
    private var isScrubbing = false

    /**
     * Latest scrub offset, collected by a single worker. StateFlow conflates, so a burst of slider
     * steps can neither run two predictions at once nor land them out of order — an older result
     * arriving after a newer one moved the marker and its footprint back a step, which is what the
     * drag shake turned out to be.
     */
    private val scrubOffset = MutableStateFlow(0L)

    /** Cost of the last full-constellation refresh during a drag, in milliseconds. */
    private var lastFullScrubCostMs = 0L

    /** Wall clock of the last full-constellation refresh during a drag, in milliseconds. */
    private var lastFullScrubAtMs = 0L

    /** Map instant the currently drawn ground track was computed for (0 = none yet). */
    private var trackTimeMs = 0L

    /**
     * Requests for an off-path constellation refresh, conflated so at most one is ever pending.
     * The heavy batch runs on its own worker: putting it on the slider path is what made a drag
     * with a big catalogue move in coarse jumps — one slow batch blocked dozens of steps, so the
     * map advanced roughly once a second instead of following the finger.
     */
    private val fullRefreshRequest = MutableStateFlow(0L)

    /**
     * The two most recent full-constellation snapshots and the scrubbed instants they belong to.
     * Between refreshes the other satellites are moved by extrapolating this pair (see
     * [glidePositions]) instead of re-running SGP4 for every one of them on every slider step.
     */
    @Volatile
    private var snapshotNew: Map<OrbitalObject, GeoPos>? = null

    @Volatile
    private var snapshotNewTimeMs = 0L

    @Volatile
    private var snapshotOld: Map<OrbitalObject, GeoPos>? = null

    @Volatile
    private var snapshotOldTimeMs = 0L

    /** Gates the prediction loop so it doesn't burn CPU while the map isn't on screen */
    private val isScreenVisible = MutableStateFlow(true)
    val uiState: StateFlow<MapState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        isUtc = settings.stateOfUtc,
                        isLightUi = settings.stateOfLightTheme,
                        mapSource = settings.mapSource,
                        tiandituKey = settings.tiandituKey,
                        isGridMode = if (passWindow == null) settings.stateOfMapGrid else false,
                        showFirstCallLabels = settings.stateOfMapFirstCall
                    )
                }
            }
        }
        viewModelScope.launch {
            // Grid + mark data reloads whenever the screen becomes visible (and once at
            // init): the LoTW/Wavelog syncs run from Settings or the app start, so
            // re-entering the map picks their results up.
            isScreenVisible.collect { visible ->
                if (visible) refreshMapGridData()
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            // Serialised scrub pipeline (see scrubOffset): one prediction at a time, always the
            // newest offset, and "now" is read here so a step that arrives mid-prediction is not
            // dropped. Runs on Default for the same reason the tick loop does.
            scrubOffset.collect { offset ->
                if (passWindow != null) return@collect
                val selected = selectedOrbitalObject ?: return@collect
                val nowMs = System.currentTimeMillis()
                val scrubDate = Date(nowMs + offset)
                // Smooth step: the selected satellite is predicted for this instant exactly, every
                // step, so the marker and its footprint follow the finger.
                updateScrubbedState(selected, stationPos, scrubDate)
                // Keep the rest of the constellation on the true track with an off-path refresh.
                // The request rate follows the last batch's cost (quarter-duty budget) so a big
                // catalogue is asked for less often, but it never blocks the steps above.
                val interval = (lastFullScrubCostMs * 4)
                    .coerceIn(MIN_FULL_SCRUB_INTERVAL_MS, MAX_FULL_SCRUB_INTERVAL_MS)
                if (nowMs - lastFullScrubAtMs >= interval) {
                    lastFullScrubAtMs = nowMs
                    fullRefreshRequest.value = nowMs
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            fullRefreshRequest.collect {
                if (passWindow != null) return@collect
                val selected = selectedOrbitalObject ?: return@collect
                val timeMs = System.currentTimeMillis() + timeOffsetMs
                val startedAt = System.nanoTime()
                val (positions, _) = predictConstellation(selected, allSatellites, stationPos, timeMs)
                lastFullScrubCostMs = (System.nanoTime() - startedAt) / 1_000_000L
                snapshotOld = snapshotNew
                snapshotOldTimeMs = snapshotNewTimeMs
                snapshotNew = positions
                snapshotNewTimeMs = timeMs
                // The batch lands late by definition. Re-glide it onto the instant the map is
                // showing now and keep the selected satellite's own (newer, exact) entry, so a
                // refresh corrects the other markers without rewinding the marker, the readout or
                // the camera.
                _uiState.update { state ->
                    val glided = glidePositions(state.positions, selected, null, state.mapTimeMs)
                    if (glided == null) state else state.copy(positions = glided)
                }
                // The track is deliberately NOT touched here. It describes the live pass, not the
                // instant the slider is parked on (user decision 2026-10-16): dragging the time bar
                // moves the satellite, its footprint and the clock, and leaves the line as it was.
                // Re-deriving it per refresh only bought a redraw that disagreed with the marker.
            }
        }
        val (selectedCatNum, _) = satelliteRepo.selectedPass.value
        getStationPosition()
        if (passWindow == null) {
            selectDefaultSatellite(if (selectedCatNum != 0) selectedCatNum else -1)
        } else {
            viewModelScope.launch(Dispatchers.Default) {
                passTimeRequest.collectLatest { at ->
                    val satellite = selectedOrbitalObject
                    if (at != null && satellite != null) {
                        updateScrubbedState(satellite, stationPos, Date(at))
                    }
                }
            }
            passWindow?.let(::openRadarPassMap)
        }
    }

    /** Loads the worked-grid / mark data into the map state (see the visibility collector). */
    private fun refreshMapGridData() {
        val workedGrids = settingsRepo.getWorkedGrids()
        val markedGrids = settingsRepo.getMarkedGridStations()
        // Marked stations auto-clear once their grid becomes worked:
        // the reminder has served its purpose (user req: 网格变绿后标记自动清除).
        val stale = markedGrids.keys.filter { it in workedGrids }
        val activeMarks = if (stale.isEmpty()) markedGrids else markedGrids - stale.toSet()
        if (stale.isNotEmpty()) settingsRepo.setMarkedGridStations(activeMarks)
        _uiState.update {
            it.copy(
                workedGrids = workedGrids,
                workedGridQsos = settingsRepo.getWorkedGridQsos(),
                roamedGrids = settingsRepo.getRoamedGrids(),
                markedGrids = activeMarks
            )
        }
    }

    fun onAction(action: MapAction) {
        when (action) {
            MapAction.SelectPrev -> if (passWindow == null) scrollSelection(true)
            MapAction.SelectNext -> if (passWindow == null) scrollSelection(false)
            is MapAction.SelectItem -> if (passWindow == null) selectSatellite(action.item)
            is MapAction.SelectDefaultItem -> if (passWindow == null) selectDefaultSatellite(action.catnum)
            is MapAction.ToggleGridMode -> if (passWindow == null) {
                settingsRepo.updateOtherSettings { it.copy(stateOfMapGrid = action.value) }
            }
            is MapAction.ToggleFirstCallLabels -> settingsRepo.updateOtherSettings { it.copy(stateOfMapFirstCall = action.value) }
            is MapAction.SetMarkedStation -> {
                val call = action.call.trim().uppercase()
                val existing = settingsRepo.getMarkedGridStations()
                val current = existing[action.grid].orEmpty()
                // 重复呼号忽略: 同一网格已标记过的呼号不再重复添加.
                val updated = if (current.any { it.call == call }) existing
                else existing + (action.grid to current + com.rtbishop.look4sat.core.domain.model.MarkedStation(
                    call = call,
                    epochMs = System.currentTimeMillis()
                ))
                settingsRepo.setMarkedGridStations(updated)
                _uiState.update { it.copy(markedGrids = updated) }
            }
            is MapAction.RemoveMarkedStation -> {
                val existing = settingsRepo.getMarkedGridStations()
                val current = existing[action.grid].orEmpty()
                val remaining = current.filterNot { it.call == action.call }
                val updated = if (remaining.isEmpty()) existing - action.grid
                else existing + (action.grid to remaining)
                settingsRepo.setMarkedGridStations(updated)
                _uiState.update { it.copy(markedGrids = updated) }
            }
            is MapAction.PinMarkedStation -> {
                val existing = settingsRepo.getMarkedGridStations()
                val current = existing[action.grid].orEmpty()
                val target = current.firstOrNull { it.call == action.call }
                if (target != null) {
                    val updated = existing + (action.grid to listOf(target) + current.filterNot { it.call == action.call })
                    settingsRepo.setMarkedGridStations(updated)
                    _uiState.update { it.copy(markedGrids = updated) }
                }
            }
            is MapAction.SetVisible -> isScreenVisible.value = action.isVisible
            is MapAction.SetTimeOffset -> if (passWindow == null) setTimeOffset(action.offsetMs)
            is MapAction.SetPassTime -> passWindow?.let { pass ->
                passTimePinned = true
                passTimeRequest.value = action.timeMs.coerceIn(pass.aosTime, pass.losTime)
            }
            MapAction.ResetTimeOffset -> {
                val pass = passWindow
                if (pass == null) {
                    setTimeOffset(0L)
                } else {
                    val nowMs = System.currentTimeMillis()
                    val isPassActive = nowMs in pass.aosTime..pass.losTime
                    // During the pass, reset returns to live time. Outside it, reset to AOS and
                    // pin there instead of being clamped to LOS by the live update loop.
                    passTimePinned = !isPassActive
                    isScrubbing = false
                    _uiState.update { it.copy(isScrubbing = false) }
                    passTimeRequest.value = radarPassResetTime(nowMs, pass.aosTime, pass.losTime)
                }
            }
            is MapAction.SetScrubbing -> {
                isScrubbing = action.isScrubbing
                _uiState.update { it.copy(isScrubbing = action.isScrubbing) }
            }
        }
    }

    /**
     * Moves the satellite-mode clock [offsetMs] ahead of now and re-predicts the selected
     * satellite for that instant. Called on every throttled slider step, so it must stay cheap:
     * see [updateScrubbedState].
     */
    private fun setTimeOffset(offsetMs: Long) {
        timeOffsetMs = offsetMs.coerceAtLeast(0L)
        _uiState.update { it.copy(timeOffsetMs = timeOffsetMs) }
        scrubOffset.value = timeOffsetMs
    }

    private fun getStationPosition() {
        val osmLat = clipLat(stationPos.latitude)
        val osmLon = clipLon(stationPos.longitude)
        _uiState.update { it.copy(stationPosition = GeoPos(osmLat, osmLon)) }
    }

    private fun selectDefaultSatellite(catnum: Int) {
        if (allSatellites.isNotEmpty()) {
            if (catnum == -1) {
                allPasses.find { pass -> pass.progress < 100 && !pass.isDeepSpace }
                    ?.let { pass -> selectSatellite(pass.orbitalObject) }
            } else {
                allSatellites.find { it.data.catnum == catnum }?.let { selectSatellite(it) }
            }
        }
    }

    private fun scrollSelection(decrement: Boolean) {
        if (allSatellites.isNotEmpty()) {
            val index = allSatellites.indexOf(selectedOrbitalObject)
            if (decrement) {
                if (index > 0) selectSatellite(allSatellites[index - 1])
                else selectSatellite(allSatellites[allSatellites.size - 1])
            } else {
                if (index < allSatellites.size - 1) selectSatellite(allSatellites[index + 1])
                else selectSatellite(allSatellites[0])
            }
        }
    }

    /**
     * Replace pass contents without recreating the MapView or NavEntry.
     * A new request cancels the old track computation before its result can
     * overwrite the new pass. Empty layers remove stale markers while loading.
     */
    fun switchPassWindow(next: RadarPassMapDestination) {
        if (passWindow == null || passWindow == next) return
        passLoadJob?.cancel()
        passWindow = next
        selectedOrbitalObject = null
        radarPass = null
        passTimePinned = false
        isScrubbing = false
        passTimeRequest.value = null
        _uiState.update {
            it.copy(
                mapData = null,
                orbitalPass = getDefaultPass(),
                track = emptyList(),
                footprint = null,
                positions = emptyMap(),
                mapTimeMs = System.currentTimeMillis().coerceIn(next.aosTime, next.losTime),
                isScrubbing = false
            )
        }
        openRadarPassMap(next)
    }

    /** Build one selected pass, canceling any old prediction loop. */
    private fun openRadarPassMap(pass: RadarPassMapDestination) {
        passLoadJob?.cancel()
        passLoadJob = viewModelScope.launch(Dispatchers.Default) {
            if (pass.losTime <= pass.aosTime) return@launch
            // Navigation can reach this page while the repository is still loading its selected
            // satellite catalogue. Reading .value once here could see an empty list and return
            // permanently, leaving the map without either the track or the satellite marker.
            // A pass already carries its satellite object. Prefer it when the selected
            // satellite list is briefly being rebuilt after switching/filtering.
            val satellite = satelliteRepo.passes.value.firstOrNull {
                it.catNum == pass.catNum && it.aosTime == pass.aosTime
            }?.orbitalObject ?: satelliteRepo.satellites.first { satellites ->
                satellites.any { it.data.catnum == pass.catNum }
            }.first { it.data.catnum == pass.catNum }
            val foundPass = satelliteRepo.passes.value.find {
                it.catNum == pass.catNum && it.aosTime == pass.aosTime
            }
            radarPass = foundPass ?: OrbitalPass(
                aosTime = pass.aosTime, losTime = pass.losTime, orbitalObject = satellite
            )
            selectedOrbitalObject = satellite
            // Show the newly selected satellite immediately; ground-track prediction can
            // take much longer for deep-space passes. The marker/time need not wait.
            val initialTime = System.currentTimeMillis().coerceIn(pass.aosTime, pass.losTime)
            passTimePinned = false
            isScrubbing = false
            updateScrubbedState(satellite, stationPos, Date(initialTime))
            passTimeRequest.value = initialTime
            // Repository getTrack samples [AOS, LOS); append LOS exactly.
            val duration = pass.losTime - pass.aosTime
            val predicted = if (duration <= 4 * 60 * 60_000L) {
                satelliteRepo.getTrack(satellite, stationPos, pass.aosTime, pass.losTime)
                    .map { it.toMapGeoPos() }.toMutableList()
            } else {
                // Deep-space passes may span days. Cap the drawn samples so the map
                // doesn't run thousands of 15-second orbital predictions.
                val stepMs = (duration / 1_200L).coerceAtLeast(15_000L)
                val points = ArrayList<GeoPos>()
                var time = pass.aosTime
                while (time < pass.losTime) {
                    points.add(satelliteRepo.getPosition(satellite, stationPos, time).toMapGeoPos())
                    time += stepMs
                }
                points
            }
            predicted.add(
                satelliteRepo.getPosition(satellite, stationPos, pass.losTime).toMapGeoPos()
            )
            _uiState.update {
                it.copy(
                    isGridMode = false,
                    track = splitGroundTrackAtDateLine(predicted),
                    orbitalPass = radarPass ?: it.orbitalPass
                )
            }
            // Preserve live tracking while the pass is underway. Once the operator
            // seeks an absolute time, keep that instant rather than snapping back.
            while (isActive) {
                isScreenVisible.first { it }
                if (!passTimePinned && !isScrubbing) {
                    passTimeRequest.value =
                        System.currentTimeMillis().coerceIn(pass.aosTime, pass.losTime)
                }
                delay(1_000L)
            }
        }
    }

    private fun selectSatellite(orbitalObject: OrbitalObject, updateFreq: Long = dataUpdateRate) {
        selectedOrbitalObject = orbitalObject
        viewModelScope.launch {
            dataUpdateJob?.cancelAndJoin()
            // Default dispatcher is mandatory: viewModelScope is Main.immediate, and every
            // satelliteRepo call internally hops to Default and resumes back on the caller's
            // dispatcher. On Main that posts one continuation per satellite per tick, which
            // floods the looper and ANRs for users tracking thousands of objects.
            dataUpdateJob = launch(Dispatchers.Default) {
                // Live wall-clock now: the track describes the pass the map is tracking live, and a
                // scrub offset must not move it (see syncTrack and the refresh worker).
                val dateNow = Date()
                getStationPosition()
                getSatTrack(orbitalObject, stationPos, dateNow)
                // Scale update interval based on satellite count to avoid excessive CPU usage
                val effectiveRate = when {
                    allSatellites.size > 5000 -> maxOf(updateFreq, 3000L)
                    allSatellites.size > 1000 -> maxOf(updateFreq, 2000L)
                    else -> updateFreq
                }
                while (isActive) {
                    // Suspends while the map is off-screen instead of predicting into the void
                    isScreenVisible.first { it }
                    if (isScrubbing) {
                        // The scrub handler owns the map while the finger is down; the full
                        // constellation batch would only fight it (and burn CPU).
                        delay(SCRUB_IDLE_DELAY)
                        continue
                    }
                    dateNow.time = System.currentTimeMillis() + timeOffsetMs
                    updateMapState(orbitalObject, allSatellites, stationPos, dateNow)
                    delay(effectiveRate)
                }
            }
        }
    }

    /**
     * Combined update: computes all satellite positions in parallel, then derives
     * the selected satellite's footprint and info panel data from the same batch,
     * and emits a single state update to avoid redundant recompositions.
     */
    private suspend fun updateMapState(
        selected: OrbitalObject,
        orbitalObjects: List<OrbitalObject>,
        pos: GeoPos,
        date: Date
    ) {
        // 1. Compute all positions in parallel, collecting full OrbitalPos for the selected sat
        val (positionsMap, predicted) = predictConstellation(selected, orbitalObjects, pos, date.time)

        // 2. Derive footprint, info data, sun and moon position from already-computed state
        val satPos = predicted ?: satelliteRepo.getPosition(selected, pos, date.time)
        val footprint = satPos
        val mapData = buildMapData(selected, satPos, date)
        val sunPos = CelestialComputer.getSunPosition(stationPos, date.time)
        val moonPos = CelestialComputer.getMoonPosition(stationPos, date.time)

        // 3. Single atomic state update — one recomposition per cycle
        snapshotOld = snapshotNew
        snapshotOldTimeMs = snapshotNewTimeMs
        snapshotNew = positionsMap
        snapshotNewTimeMs = date.time
        _uiState.update {
            it.copy(
                positions = positionsMap,
                footprint = footprint,
                mapData = mapData.first,
                orbitalPass = mapData.second,
                sunLatDeg = sunPos.latitude,
                sunLonDeg = sunPos.longitude,
                moonLatDeg = moonPos.declination, // sub-lunar latitude = declination
                moonLonDeg = if (moonPos.gha <= 180.0) -moonPos.gha else 360.0 - moonPos.gha,
                mapTimeMs = date.time
            )
        }
        // The track belongs to live tracking: keep it on wall-clock now, never on the scrubbed
        // instant (see the refresh worker), so a scrub leaves the line untouched.
        syncTrack(selected, pos, System.currentTimeMillis())
    }

    /**
     * Time-scrub update: re-predicts only the selected satellite, its footprint and the
     * celestial markers for [date], and patches that one entry of the marker map. Used for the
     * slider steps that fall between the full-constellation refreshes (see the scrub collector):
     * one satellite per step is what keeps 25 steps a second affordable.
     */
    private suspend fun updateScrubbedState(selected: OrbitalObject, pos: GeoPos, date: Date) {
        val satPos = satelliteRepo.getPosition(selected, pos, date.time)
        val (predictedData, detectedPass) = buildMapData(selected, satPos, date)
        val chosenPass = radarPass ?: detectedPass
        val mapData = if (passWindow != null) {
            predictedData.copy(
                aosTime = (chosenPass.losTime - date.time).coerceAtLeast(0L).toTimerString(),
                isTimeAos = false
            )
        } else predictedData
        val sunPos = CelestialComputer.getSunPosition(stationPos, date.time)
        val moonPos = CelestialComputer.getMoonPosition(stationPos, date.time)
        _uiState.update { state ->
            val positions = glidePositions(state.positions, selected, satPos.toMapGeoPos(), date.time)
            state.copy(
                positions = positions ?: mapOf(selected to satPos.toMapGeoPos()),
                footprint = satPos,
                mapData = mapData,
                orbitalPass = chosenPass,
                sunLatDeg = sunPos.latitude,
                sunLonDeg = sunPos.longitude,
                moonLatDeg = moonPos.declination, // sub-lunar latitude = declination
                moonLonDeg = if (moonPos.gha <= 180.0) -moonPos.gha else 360.0 - moonPos.gha,
                mapTimeMs = date.time,
                // Auto playback does not seize the camera from the user's panning.
                // A manual seek still follows the satellite as the thumb moves.
                scrubSerial = state.scrubSerial +
                    if (passWindow == null || passTimePinned) 1 else 0
            )
        }
    }

    /**
     * Marker positions for a slider step that is not a full refresh. The selected satellite is
     * either the freshly predicted one ([selectedPos]) or, when a background refresh lands late,
     * the one the map is already showing; every other satellite keeps following the scrubbed time
     * by extrapolating the two latest full snapshots — so the whole constellation glides at the
     * slider's cadence (tens of steps a second) instead of jumping at the much coarser refresh
     * cadence. The extrapolation window is a single refresh interval, so the drift stays well
     * under the marker size, and the next refresh puts everything back on the true track.
     */
    private fun glidePositions(
        current: Map<OrbitalObject, GeoPos>?,
        selected: OrbitalObject,
        selectedPos: GeoPos?,
        timeMs: Long
    ): Map<OrbitalObject, GeoPos>? {
        val base = current ?: return null
        val newest = snapshotNew ?: return base.toMutableMap().apply {
            selectedPos?.let { put(selected, it) }
        }
        val older = snapshotOld
        val span = snapshotNewTimeMs - snapshotOldTimeMs
        val step = timeMs - snapshotNewTimeMs
        val glide = HashMap<OrbitalObject, GeoPos>(base.size + 1)
        for ((satellite, pos) in base) {
            val anchor = newest[satellite]
            val previous = older?.get(satellite)
            if (satellite === selected || anchor == null || previous == null || span == 0L) {
                glide[satellite] = anchor ?: pos
                continue
            }
            val deltaLat = anchor.latitude - previous.latitude
            var deltaLon = anchor.longitude - previous.longitude
            if (deltaLon > 180.0) deltaLon -= 360.0 else if (deltaLon < -180.0) deltaLon += 360.0
            // A date-line crossing (or a catalogue change) makes the difference meaningless:
            // park the marker instead of flinging it across the map; the next refresh fixes it.
            if (abs(deltaLon) > 90.0) {
                glide[satellite] = anchor
                continue
            }
            val factor = step.toDouble() / span.toDouble()
            var longitude = anchor.longitude + deltaLon * factor
            if (longitude > 180.0) longitude -= 360.0 else if (longitude < -180.0) longitude += 360.0
            glide[satellite] = anchor.copy(
                latitude = anchor.latitude + deltaLat * factor,
                longitude = longitude
            )
        }
        glide[selected] = selectedPos ?: base[selected] ?: newest[selected] ?: return glide
        return glide
    }

    /**
     * Predicts every object in [objects] for [timeMs] in parallel chunks, returning the marker
     * positions plus the selected satellite's full [OrbitalPos] (null when it is not in the list).
     * Shared by the tick path and the off-path scrub refresh — the latter runs it on its own
     * worker so a slow catalogue cannot stall the slider's single-satellite steps.
     */
    private suspend fun predictConstellation(
        selected: OrbitalObject,
        objects: List<OrbitalObject>,
        pos: GeoPos,
        timeMs: Long
    ): Pair<Map<OrbitalObject, GeoPos>, OrbitalPos?> {
        val positionsMap = HashMap<OrbitalObject, GeoPos>(objects.size)
        var selectedSatPos: OrbitalPos? = null
        coroutineScope {
            // Chunk satellites to balance parallelism vs coroutine overhead
            val chunkSize = maxOf(1, objects.size / PARALLEL_CHUNKS)
            val deferreds = objects.chunked(chunkSize).map { chunk ->
                async {
                    val localPositions = ArrayList<Pair<OrbitalObject, GeoPos>>(chunk.size)
                    var localSelectedPos: OrbitalPos? = null
                    for (satellite in chunk) {
                        val satPos = satelliteRepo.getPosition(satellite, pos, timeMs)
                        localPositions.add(satellite to satPos.toMapGeoPos())
                        if (satellite === selected) {
                            localSelectedPos = satPos
                        }
                    }
                    Pair(localPositions, localSelectedPos)
                }
            }
            for ((localPositions, localSelectedPos) in deferreds.awaitAll()) {
                for (pair in localPositions) {
                    positionsMap[pair.first] = pair.second
                }
                if (localSelectedPos != null) {
                    selectedSatPos = localSelectedPos
                }
            }
        }
        return positionsMap to selectedSatPos
    }

    /**
     * Builds the map info panel data from an already-computed OrbitalPos.
     * No additional getPosition() call needed.
     */
    private fun buildMapData(
        sat: OrbitalObject,
        satPos: OrbitalPos,
        date: Date
    ): Pair<MapData, OrbitalPass> {
        var orbitalPass = defaultPass
        var aosTime = 0L.toTimerString()
        var isTimeAos = true
        allPasses.find { pass -> pass.catNum == sat.data.catnum && pass.progress < 1 }
            ?.let { satPass ->
                orbitalPass = satPass
                if (!satPass.isDeepSpace) {
                    when {
                        date.time < satPass.aosTime -> {
                            // Pass hasn't started yet — count down to AOS
                            isTimeAos = true
                            aosTime = (satPass.aosTime - date.time).toTimerString()
                        }

                        date.time < satPass.losTime -> {
                            // Pass is in progress — count down to LOS
                            isTimeAos = false
                            aosTime = (satPass.losTime - date.time).toTimerString()
                        }
                        // else: pass has ended (losTime <= date.time) — keep default "00:00:00"
                    }
                }
            }
        val azimuth = satPos.azimuth.toDegrees()
        val elevation = satPos.elevation.toDegrees()
        val osmPos = satPos.toMapGeoPos()
        val qthLoc = positionToQth(osmPos.latitude, osmPos.longitude) ?: "-- --"
        val velocity = satPos.getOrbitalVelocity()
        val phase = satPos.phase.toDegrees()
        val visibility = satPos.eclipsed
        val satData = MapData(
            sat.data.catnum,
            sat.data.name,
            aosTime,
            isTimeAos,
            azimuth,
            elevation,
            satPos.distance,
            satPos.altitude,
            velocity,
            qthLoc,
            osmPos,
            sat.data.orbitalPeriod,
            phase,
            visibility
        )
        return satData to orbitalPass
    }

    /**
     * Sample step of the repository's track prediction (mirrors the 15 s loop in SatelliteRepo),
     * used to timestamp the samples so the window can be slid instead of re-predicted.
     */
    private val TRACK_SAMPLE_MS = 15_000L

    /**
     * Largest drift the drawn track is slid through. Anything longer (any real scrub, a page
     * reentry after minutes off-screen) is cheaper to re-predict from scratch than to extend.
     */
    private val TRACK_SLIDE_MAX_MS = 5 * 60_000L

    /** Satellite the samples below belong to, null when there is no track yet. */
    private var trackSatellite: OrbitalObject? = null

    /** Ground-track samples, oldest first: instant -> subpoint. */
    private var trackSamples = ArrayList<Pair<Long, GeoPos>>()

    /** Length of the drawn track: 2.4 orbital periods from the map instant. */
    private fun trackWindowMs(sat: OrbitalObject) = (sat.data.orbitalPeriod * 2.4 * 60_000.0).toLong()

    /** Predicts the whole ground-track window that starts at [date]. */
    private suspend fun getSatTrack(orbitalObject: OrbitalObject, pos: GeoPos, date: Date) {
        val startMs = date.time
        val endMs = startMs + trackWindowMs(orbitalObject)
        val predicted = satelliteRepo.getTrack(orbitalObject, pos, startMs, endMs)
        val samples = ArrayList<Pair<Long, GeoPos>>(predicted.size + 1)
        predicted.forEachIndexed { index, satPos ->
            samples.add(startMs + index * TRACK_SAMPLE_MS to satPos.toMapGeoPos())
        }
        trackSatellite = orbitalObject
        trackSamples = samples
        publishTrack(samples, startMs)
    }

    /**
     * Puts the ground track on [timeMs] the cheap way: the head is re-anchored on the satellite
     * itself (one prediction) and, every 15 s of map time, one more sample is appended at the
     * tail — instead of re-deriving the whole 2.4-lap curve.
     *
     * [timeMs] is always the live wall clock: the line describes the pass being tracked live, so a
     * scrub offset never moves it (user decision 2026-10-16). While the map is live this is what
     * keeps the head of the line on the satellite instead of letting the selection instant drift
     * away from it.
     */
    private suspend fun syncTrack(orbitalObject: OrbitalObject, pos: GeoPos, timeMs: Long) {
        val samples = trackSamples
        val rebuild = trackSatellite !== orbitalObject || samples.isEmpty() ||
            abs(timeMs - trackTimeMs) > TRACK_SLIDE_MAX_MS
        if (rebuild) {
            getSatTrack(orbitalObject, pos, Date(timeMs))
            return
        }
        if (timeMs == trackTimeMs) return
        val endMs = timeMs + trackWindowMs(orbitalObject)
        var head = 0
        while (head < samples.size && samples[head].first < timeMs) head++
        if (head > 0) samples.subList(0, head).clear()
        val tailMs = samples.lastOrNull()?.first ?: timeMs
        if (tailMs + TRACK_SAMPLE_MS <= endMs) {
            val from = tailMs + TRACK_SAMPLE_MS
            satelliteRepo.getTrack(orbitalObject, pos, from, endMs).forEachIndexed { index, satPos ->
                samples.add(from + index * TRACK_SAMPLE_MS to satPos.toMapGeoPos())
            }
        }
        val exact = satelliteRepo.getPosition(orbitalObject, pos, timeMs).toMapGeoPos()
        // Do not replace the next predicted point: doing so each live tick
        // progressively removes 15-second samples and turns the track into chords.
        if (samples.firstOrNull()?.first == timeMs) {
            samples[0] = timeMs to exact
        } else {
            samples.add(0, timeMs to exact)
        }
        publishTrack(samples, timeMs)
    }

    /** Splits the samples into date-line-safe laps and publishes them to the map. */
    private fun publishTrack(samples: List<Pair<Long, GeoPos>>, anchorMs: Long) {
        // Close both sides of each ±180° crossing at the same interpolated latitude.
        // A split on just one side leaves a visible gap before the next 15-second sample.
        val satTracks = splitGroundTrackAtDateLine(samples.map { it.second })
        trackTimeMs = anchorMs
        _uiState.update { it.copy(track = satTracks) }
    }

    companion object {
        /** Number of parallel chunks for satellite position computation */
        private const val PARALLEL_CHUNKS = 4

        /** Poll interval while the time slider is under the finger (the tick loop is paused then). */
        private const val SCRUB_IDLE_DELAY = 100L

        /** Floor for the off-path constellation refresh interval while scrubbing, in ms. */
        private const val MIN_FULL_SCRUB_INTERVAL_MS = 300L

        /** Ceiling: a catalogue too slow to refresh often is still corrected, just less often. */
        private const val MAX_FULL_SCRUB_INTERVAL_MS = 2000L

        fun factory(
            container: IMainContainer,
            passWindow: RadarPassMapDestination? = null
        ) = viewModelFactory {
            initializer {
                MapViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo,
                    passWindow = passWindow
                )
            }
        }
    }
}
