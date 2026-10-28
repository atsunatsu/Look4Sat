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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.AwardCalculator
import com.rtbishop.look4sat.core.domain.model.AwardProgress
import com.rtbishop.look4sat.core.domain.model.AwardType
import com.rtbishop.look4sat.core.domain.model.firstCallsByGrid
import com.rtbishop.look4sat.core.domain.model.MapSource
import com.rtbishop.look4sat.core.domain.model.myStationGrids
import com.rtbishop.look4sat.core.domain.model.scopedToStation
import com.rtbishop.look4sat.core.domain.model.scopedToStations
import com.rtbishop.look4sat.core.domain.model.stationGridSetKey
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.utility.clipLon
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.RadarPassMapDestination
import com.rtbishop.look4sat.core.presentation.NextPassRow
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SheetDialogTitle
import com.rtbishop.look4sat.core.presentation.TimerRow
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import com.rtbishop.look4sat.core.presentation.layoutPadding
import com.rtbishop.look4sat.core.presentation.sheetDialogContainerColor
import com.rtbishop.look4sat.core.presentation.sheetDialogShape
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.exp

// Overlay indices
private const val OVERLAY_TDT_LABELS = 0
private const val OVERLAY_GRID = 1
private const val OVERLAY_STATION = 2
private const val OVERLAY_TRACK = 3
private const val OVERLAY_FOOTPRINT = 4
private const val OVERLAY_POSITIONS = 5
private const val OVERLAY_TERMINATOR = 6
private const val OVERLAY_SUN = 7
private const val OVERLAY_MOON = 8
private const val OVERLAY_OTHER_STATION = 9
private const val OVERLAY_ELEVATION_CONTOURS = 10
private const val OVERLAY_STATION_ELEVATION = 11
private const val OVERLAY_COUNT = 12

private val minLat = MapView.getTileSystem().minLatitude
private val maxLat = MapView.getTileSystem().maxLatitude
private val offlineTileSource = XYTileSource("tiles", 0, 6, 256, ".webp", emptyArray<String>())
private const val OFFLINE_MAX_ZOOM = 7.0
private const val TIANDITU_MAX_ZOOM = 18.0
private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeWidth = 3f
    style = Paint.Style.STROKE
    color = Color.RED
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val footprintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeWidth = 3f
    style = Paint.Style.FILL_AND_STROKE
    color = "#FFE082".toColorInt()
}
private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 36f
    style = Paint.Style.FILL
    color = "#FFE082".toColorInt()
    setShadowLayer(3f, 3f, 3f, Color.BLACK)
}
private val iconCache = LruCache<String, Drawable>(128)
private val sunIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    colorFilter =
        android.graphics.PorterDuffColorFilter("#FFE082".toColorInt(), android.graphics.PorterDuff.Mode.SRC_IN)
}
private val moonIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    colorFilter =
        android.graphics.PorterDuffColorFilter("#E0E0E0".toColorInt(), android.graphics.PorterDuff.Mode.SRC_IN)
}

/**
 * Accent used for the station marker, footprint outline and map labels. It is recolored per
 * theme (dark amber on light tiles, light amber on dark tiles) so every map element stays
 * readable; [applyMapColors] keeps it in sync with the shared paints.
 */
private var mapAccentColor = "#FFE082".toColorInt()

/** Grayscale + invert filter that turns the offline OSM map dark; unused (and off) in light theme. */
private val darkTileFilter by lazy { createColorFilter() }

/** Theme the shared map paints were last colored for; null = not applied yet this process. */
private var mapIsLightUi: Boolean? = null

/** Theme the tile filter / icon caches were last applied for (drives the per-frame theme check). */
private var appliedLightUi: Boolean? = null

/** True while the offline OSM source is active — satellite imagery (Tianditu) is never filtered. */
private var offlineTilesActive = true

/**
 * Recolor the shared overlay paints and custom overlays for the current theme. Must run before the
 * markers are (re)built each frame so freshly created icons use the new colors; [applyThemeToOverlays]
 * and the icon-cache eviction in the update block handle the already created ones.
 */
private fun applyMapColors(isLightUi: Boolean) {
    mapIsLightUi = isLightUi
    if (isLightUi) {
        // Dark amber (lightScheme primary) reads on light tiles; labels get a white halo.
        mapAccentColor = "#715C0C".toColorInt()
        footprintPaint.color = mapAccentColor
        textPaint.color = "#1E1B13".toColorInt()
        textPaint.setShadowLayer(3f, 3f, 3f, Color.WHITE)
        sunIconPaint.colorFilter =
            android.graphics.PorterDuffColorFilter(mapAccentColor, android.graphics.PorterDuff.Mode.SRC_IN)
        moonIconPaint.colorFilter = android.graphics.PorterDuffColorFilter(
            "#4C4639".toColorInt(), android.graphics.PorterDuff.Mode.SRC_IN
        )
    } else {
        mapAccentColor = "#FFE082".toColorInt()
        footprintPaint.color = mapAccentColor
        textPaint.color = mapAccentColor
        textPaint.setShadowLayer(3f, 3f, 3f, Color.BLACK)
        sunIconPaint.colorFilter =
            android.graphics.PorterDuffColorFilter(mapAccentColor, android.graphics.PorterDuff.Mode.SRC_IN)
        moonIconPaint.colorFilter =
            android.graphics.PorterDuffColorFilter("#E0E0E0".toColorInt(), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    applyThemeToOverlays()
}

/** Push the current theme into the grid / award overlays, which own their own paints. */
private fun applyThemeToOverlays() {
    val isLight = mapIsLightUi == true
    currentMapView?.overlays?.forEach { overlay ->
        when (overlay) {
            is MaidenheadGridOverlay -> overlay.applyTheme(isLight)
            is AwardBoundaryOverlay -> overlay.applyTheme(isLight)
            else -> Unit
        }
    }
}

/** The MapView currently attached to the map page; used to recolor overlays on a theme change. */
private var currentMapView: MapView? = null

@Composable
fun MapDestination(
    mapFilterViewModel: MapFilterViewModel,
    // Invoked with the tapped grid when the QSO dialog's "Match" button is
    // pressed: navigate to the match page pre-filled for that grid.
    onMatchGrid: (String) -> Unit = {},
    // True while the map "Match" query is running (the page only navigates
    // once the query finished, so the match page opens with results ready).
    matchCalculating: Boolean = false,
    // Dedicated picker opened from the Match page's station grid field.
    gridPickerMode: Boolean = false,
    onPickGrid: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mapView = rememberMapViewWithLifecycle(
        mapFilterViewModel,
        restoreViewport = gridPickerMode || uiState.isGridMode
    )
    // The picker needs visible 4-character grid cells immediately; keep the
    // last grid viewport when available, but never open below the grid-label zoom.
    LaunchedEffect(gridPickerMode, mapView) {
        if (gridPickerMode) {
            mapView.post {
                if (mapView.zoomLevelDouble < MaidenheadGridOverlay.GRID_ZOOM_SUB) {
                    mapView.controller.setZoom(MaidenheadGridOverlay.GRID_ZOOM_SUB)
                }
            }
        }
    }
    // The VUCC station selector is saved in the existing SharedPreferences settings
    // store, so it survives page switches and a full app restart. Empty = All.
    var selectedStationIds by remember(container) {
        mutableStateOf(container.settingsRepo.getVuccSelectedStationIds())
    }
    val updateSelectedStationIds: (Set<String>) -> Unit = { ids ->
        selectedStationIds = ids
        container.settingsRepo.setVuccSelectedStationIds(ids)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Save the viewport when leaving the page, but only in grid mode: the
    // satellite mode intentionally keeps its original behavior (default center,
    // follow the selected satellite), so a satellite-mode exit must not
    // overwrite the last grid-mode viewport.
    DisposableEffect(mapView, uiState.isGridMode, gridPickerMode) {
        onDispose {
            if (gridPickerMode || uiState.isGridMode) {
                mapFilterViewModel.saveMapViewState(
                    mapView.mapCenter.latitude,
                    mapView.mapCenter.longitude,
                    mapView.zoomLevelDouble
                )
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAction(MapAction.SetVisible(true))
                Lifecycle.Event.ON_STOP -> viewModel.onAction(MapAction.SetVisible(false))
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onAction(MapAction.SetVisible(false))
        }
    }
    MapScreen(
        sourceState = uiState,
        onAction = viewModel::onAction,
        mapView = mapView,
        mapFilterViewModel = mapFilterViewModel,
        selectedStationIds = selectedStationIds,
        onSelectedStationIdsChange = updateSelectedStationIds,
        onMatchGrid = onMatchGrid,
        matchCalculating = matchCalculating,
        gridPickerMode = gridPickerMode,
        onPickGrid = onPickGrid,
        onNavigateBack = onNavigateBack
    )
}

/**
 * Standalone map tied to the radar's selected pass, not the normal Map tab.
 * Its own ViewModel keeps scrubbing and camera state independent.
 */
@Composable
fun RadarPassMapScreenDestination(
    pass: RadarPassMapDestination,
    onSwitchPass: (RadarPassMapDestination) -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container, pass))
    LaunchedEffect(pass) { viewModel.switchPassWindow(pass) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mutualData by container.mutualPassData.collectAsStateWithLifecycle()
    val otherStationPosition = remember(
        mutualData.endTime, mutualData.stationBLatitude, mutualData.stationBLongitude
    ) {
        if (mutualData.endTime > 0L) {
            val latitude = mutualData.stationBLatitude
            val longitude = mutualData.stationBLongitude
            if (latitude != null && longitude != null) GeoPos(latitude, longitude) else null
        } else null
    }
    val allPasses by container.satelliteRepo.passes.collectAsStateWithLifecycle()
    val passSettings by container.settingsRepo.passesSettings.collectAsStateWithLifecycle()
    // Match the Passes page's visible sequence: deep-space group first (if enabled),
    // followed by normal passes in chronological AOS order across ALL satellites.
    val visiblePasses = remember(allPasses, passSettings) {
        val cutoff = System.currentTimeMillis() - passSettings.hoursBefore * 3600_000L
        val compare = compareBy<com.rtbishop.look4sat.core.domain.predict.OrbitalPass> { it.aosTime }
            .thenBy { it.catNum }.thenBy { it.losTime }
        val filtered = allPasses.filter { candidate ->
            candidate.losTime > candidate.aosTime &&
                (passSettings.showDeepSpace || !candidate.isDeepSpace) &&
                (candidate.isDeepSpace || candidate.losTime >= cutoff)
        }
        (filtered.filter { it.isDeepSpace }.sortedWith(compare) +
            filtered.filterNot { it.isDeepSpace }.sortedWith(compare))
            .map { RadarPassMapDestination(it.catNum, it.aosTime, it.losTime) }
            .distinctBy { it.catNum to it.aosTime }
    }
    val previousPass = adjacentRadarPass(visiblePasses, pass, -1)
    val nextPass = adjacentRadarPass(visiblePasses, pass, 1)
    val mapFilter: MapFilterViewModel = viewModel(factory = MapFilterViewModel.factory())
    val mapView = rememberMapViewWithLifecycle(mapFilter, restoreViewport = false)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAction(MapAction.SetVisible(true))
                Lifecycle.Event.ON_STOP -> viewModel.onAction(MapAction.SetVisible(false))
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onAction(MapAction.SetVisible(false))
        }
    }
    MapScreen(
        sourceState = uiState,
        onAction = viewModel::onAction,
        mapView = mapView,
        mapFilterViewModel = mapFilter,
        selectedStationIds = emptySet(),
        onSelectedStationIdsChange = {},
        onMatchGrid = {},
        matchCalculating = false,
        passWindow = pass,
        previousPass = previousPass,
        nextPass = nextPass,
        onSwitchPass = onSwitchPass,
        otherStationPosition = otherStationPosition,
        otherStationGrid = mutualData.stationBGrid.takeIf { otherStationPosition != null }
    )
}

@Composable
private fun MapScreen(
    sourceState: MapState,
    onAction: (MapAction) -> Unit,
    mapView: MapView,
    mapFilterViewModel: MapFilterViewModel,
    selectedStationIds: Set<String>,
    onSelectedStationIdsChange: (Set<String>) -> Unit,
    onMatchGrid: (String) -> Unit,
    matchCalculating: Boolean,
    passWindow: RadarPassMapDestination? = null,
    previousPass: RadarPassMapDestination? = null,
    nextPass: RadarPassMapDestination? = null,
    onSwitchPass: (RadarPassMapDestination) -> Unit = {},
    otherStationPosition: GeoPos? = null,
    otherStationGrid: String? = null,
    gridPickerMode: Boolean = false,
    onPickGrid: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val uiState = if (gridPickerMode) {
        sourceState.copy(isGridMode = true)
    } else {
        sourceState
    }
    val rotateMod = Modifier.rotate(180f)
    val timeString = uiState.mapData?.aosTime ?: "00:00:00"
    val isTimeAos = uiState.mapData?.isTimeAos ?: true
    val usesTianditu = tiandituLayers(uiState.mapSource) != null && uiState.tiandituKey.isNotBlank()
    val copyrightResId = if (usesTianditu) R.string.map_copyright_tianditu else R.string.map_copyright_osm

    // Slider span = the window the ground track is drawn for (2.4 orbital periods from the
    // selection instant), so a scrubbed marker always lands on that drawn track.
    val scrubSpanMs = remember(uiState.mapData?.period) {
        val periodMin = uiState.mapData?.period ?: 0.0
        (periodMin * 2.4 * 60_000.0).toLong().coerceAtLeast(60_000L)
    }

    // Tapped worked grid -> centered QSO dialog. Local UI state: the map is the
    // only consumer and it resets when leaving the page.
    var selectedGrid by remember { mutableStateOf<String?>(null) }
    // Tapped award region (DXCC/WAPC/WAJA/WAIPA/WAZ/WAS) -> its QSO detail dialog,
    // paired with the award type it was tapped under. Local UI state like the
    // grid selection above: the map is the only consumer.
    var selectedRegion by remember { mutableStateOf<Pair<AwardType, AwardRegion>?>(null) }
    // The contours belong to satellite mode; leave the configured angles alone when changing modes.
    var showElevationContours by rememberSaveable { mutableStateOf(false) }
    var elevationInputs by rememberSaveable { mutableStateOf(listOf("10")) }
    var elevationDraft by remember { mutableStateOf(elevationInputs) }
    var elevationDialogVisible by remember { mutableStateOf(false) }
    var elevationEnabledBeforeDialog by remember { mutableStateOf(false) }
    val elevationAngles = remember(showElevationContours, elevationInputs) {
        if (showElevationContours) elevationInputs.mapNotNull { it.toDoubleOrNull() } else emptyList()
    }
    val closeElevationDialog: () -> Unit = {
        if (!elevationEnabledBeforeDialog) showElevationContours = false
        elevationDialogVisible = false
    }
    // Selected award filter. Lives in an Activity-scoped ViewModel so it
    // survives page switches; defaults to VUCC only once per process (cold start).
    var selectedAward by mapFilterViewModel.selectedAward
    // Grid picking always shows the VUCC grid fill, while keeping the user's saved station selection.
    val activeAward = if (gridPickerMode) AwardType.VUCC else selectedAward
    // 台址名单: QSOs grouped by the 台址's GRID SET. A station location (台址)
    // can span several grids (MY_GRIDSQUARE + MY_VUCC_GRIDS), and QSOs whose
    // location covers the same grid set belong to the same 台址; groups with an
    // identical grid set merge (they are VUCC-equivalent — the MY_* snapshot
    // can differ between records of the same physical 台址 when LoTW omits
    // optional fields). Data synced before multi-grid support falls back to
    // one group per grid, preserving the old selector exactly.
    // Each group's `worked` cells are derived through scopedToStation (the
    // covering rule): a single-grid 台址 also counts the cells worked by any
    // multi-grid 台址 covering it (OL62 ← OL62/63, 四格点同理) — the reverse
    // never leaks (2026-10-05 用户定稿, 方案 A).
    val stationGroups: List<StationGroup> = remember(uiState.workedGridQsos) {
        val gridSets = LinkedHashMap<String, Set<String>>()
        for ((_, qsos) in uiState.workedGridQsos) {
            for (q in qsos) {
                val key = q.stationGridSetKey() ?: continue
                gridSets.getOrPut(key) { q.myStationGrids() }
            }
        }
        gridSets.map { (key, grids) ->
            val worked = uiState.workedGridQsos.filterValues { qsos ->
                qsos.scopedToStation(key).isNotEmpty()
            }.keys
            StationGroup(id = key, grids = grids, worked = worked)
        }.sortedByDescending { it.workedCount }
    }
    // Restore valid saved station IDs when data arrives or is refreshed.
    // Do not clear a saved selection while the station list is still loading.
    LaunchedEffect(stationGroups, selectedStationIds) {
        if (stationGroups.isNotEmpty() && selectedStationIds.isNotEmpty()) {
            val valid = selectedStationIds.intersect(stationGroups.map { it.id }.toSet())
            if (valid != selectedStationIds) onSelectedStationIdsChange(valid)
        }
    }
    // Union of worked squares across the selected stations. Each station's
    // existing covering rule is preserved by StationGroup.worked.
    val workedGrids = remember(uiState.workedGrids, stationGroups, selectedStationIds) {
        if (selectedStationIds.isEmpty()) uiState.workedGrids
        else stationGroups.asSequence()
            .filter { it.id in selectedStationIds }
            .flatMap { it.worked.asSequence() }
            .toSet()
    }
    // The same cached WAIPA boundary polygons drive map fill and award
    // geolocation. Resolves saved QSOs locally, without a new LoTW/Wavelog sync.
    val waipaGridResolver = remember(mapView.context) {
        WaipaGridResolver(AwardBoundaryData.load(mapView.context, AwardBoundaryData.AwardAsset.WAIPA))
    }
    val awardProgress: List<AwardProgress> = remember(
        uiState.workedGridQsos, workedGrids, selectedStationIds, waipaGridResolver
    ) {
        val base = AwardCalculator.calculate(uiState.workedGridQsos, waipaGridResolver)
        if (selectedStationIds.isEmpty()) base
        else base.map { p ->
            if (p.type == AwardType.VUCC) p.copy(workedKeys = workedGrids, count = workedGrids.size) else p
        }
    }
    // First callsign must be recomputed within the UNION of selected station
    // scopes, not from the globally earliest contact or one arbitrary station.
    val firstCallsByGrid: Map<String, String> = remember(uiState.workedGridQsos, selectedStationIds) {
        uiState.workedGridQsos.firstCallsByGrid(selectedStationIds)
    }
    val isGridMode = uiState.isGridMode
    // True when this composition restored a saved viewport. Only grid mode
    // restores the viewport the user left (satellite mode keeps its original
    // follow-the-satellite behavior); this implies grid mode was already on
    // when re-entering, which is why the grid-mode auto-centering below can
    // treat the entry as already centered.
    val restoredViewport = remember {
        isGridMode && mapFilterViewModel.mapCenterLat != null && mapFilterViewModel.mapZoom != null
    }
    DisposableEffect(isGridMode, workedGrids, gridPickerMode, activeAward, onPickGrid) {
        val receiver = object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (!isGridMode || p == null) return false
                // Award views (DXCC/WAPC/WAJA/WAIPA/WAZ/WAS): the tap resolves to the
                // boundary region under the finger and opens its QSO detail
                // dialog. No zoom gate here — unlike the grid path below — so
                // tiny islands stay reachable while zoomed out (Tianditu tiles
                // have no zoom cap, so "just zoom in" is not an answer).
                val awardMode = activeAward?.takeIf { it != AwardType.VUCC }
                if (awardMode != null && !gridPickerMode) {
                    val overlay = mapView.overlays.getOrNull(OVERLAY_GRID) as? AwardBoundaryOverlay
                        ?: return false
                    val region = overlay.regionAt(p.latitude, p.longitude, mapView.zoomLevelDouble)
                        ?: return false
                    selectedRegion = awardMode to region
                    return true
                }
                val zoom = mapView.zoomLevelDouble
                // Allow tapping worked cells as soon as the 4-char grid LINES
                // appear (GRID_ZOOM_SUB); grid labels now appear at that zoom too.
                if (zoom < MaidenheadGridOverlay.GRID_ZOOM_SUB) return false
                val grid = gridOfPoint(p.latitude, p.longitude) ?: return false
                // Every grid is tappable: worked grids show their confirmed QSOs,
                // unworked grids open the same dialog with an empty list.
                if (gridPickerMode) {
                    onPickGrid(grid)
                } else {
                    selectedGrid = grid
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        val eventsOverlay = org.osmdroid.views.overlay.MapEventsOverlay(receiver)
        mapView.overlays.add(eventsOverlay)
        onDispose { mapView.overlays.remove(eventsOverlay) }
    }
    // Keep the overlay's selected highlight in sync with the dialog.
    LaunchedEffect(selectedGrid) {
        (mapView.overlays.getOrNull(OVERLAY_GRID) as? MaidenheadGridOverlay)?.selectedGrid = selectedGrid
        mapView.invalidate()
    }
    // Same for the award boundary overlay's selected region.
    LaunchedEffect(selectedRegion) {
        (mapView.overlays.getOrNull(OVERLAY_GRID) as? AwardBoundaryOverlay)?.selectedCode =
            selectedRegion?.second?.code
        mapView.invalidate()
    }
    // Camera: go to the selected satellite once per entry and once per selection change.
    // Keyed on the satellite, NOT on the track: keying it on the track meant (a) on a re-entry the
    // track instance was unchanged, so the effect never ran and the camera stayed on the opening
    // world view instead of the satellite, and (b) once the track started being kept up to date,
    // every refresh re-issued the pan and fought the user's own panning. Keying it on the catnum
    // fixes both: a fresh composition always runs it (entry), a satellite change runs it, a track
    // refresh does not. Issued from post{} because a camera call before the first layout is
    // discarded (same trap as the restored viewport below).
    val followedCatNum = uiState.mapData?.catNum ?: 0
    LaunchedEffect(followedCatNum) {
        if (uiState.isGridMode || followedCatNum == 0) return@LaunchedEffect
        mapView.post {
            uiState.mapData?.osmPos?.let { point ->
                mapView.controller.animateTo(GeoPoint(point.latitude, point.longitude))
            }
        }
    }
    // Time scrub: keep the scrubbed satellite in view. Only its own entry is patched into
    // `positions`, so without this the camera would stay where the live map left it and the
    // marker would walk off-screen mid-drag. The camera is moved inside the map update pass
    // below, not here: a separate effect ran after the markers were applied, so the two
    // alternated on screen and the marker and its footprint looked like they were shaking.
    LaunchedEffect(uiState.mapSource, uiState.tiandituKey, uiState.isLightUi) {
        configureTileSources(mapView, uiState.mapSource, uiState.tiandituKey, uiState.isLightUi)
    }
    Column(modifier = Modifier.layoutPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val isVertical = isVerticalLayout()
        if (passWindow != null) {
            TopBar {
                IconCard(
                    action = { previousPass?.let(onSwitchPass) },
                    resId = R.drawable.ic_arrow,
                    modifier = rotateMod,
                    enabled = previousPass != null
                )
                TimerRow(timeString = timeString, isTimeAos = isTimeAos)
                IconCard(
                    action = { nextPass?.let(onSwitchPass) },
                    resId = R.drawable.ic_arrow,
                    enabled = nextPass != null
                )
            }
            TopBar { NextPassRow(pass = uiState.orbitalPass, isUtc = uiState.isUtc) }
        } else if (gridPickerMode) {
            TopBar {
                IconCard(action = onNavigateBack, resId = R.drawable.ic_back)
                Text(
                    text = stringResource(R.string.mutual_pick_grid_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        } else if (isGridMode) {
            // Grid mode: replace the satellite-selection top bar with the
            // award filter chips (All / DXCC / VUCC / WAPC / WAJA / WAIPA / WAZ / WAS).
            AwardChipsRow(
                progress = awardProgress,
                selected = selectedAward,
                onSelect = { selectedAward = it }
            )
        } else if (isVertical) {
            TopBar {
                IconCard(action = { onAction(MapAction.SelectPrev) }, resId = R.drawable.ic_arrow, modifier = rotateMod)
                TimerRow(timeString = timeString, isTimeAos = isTimeAos)
                IconCard(action = { onAction(MapAction.SelectNext) }, resId = R.drawable.ic_arrow)
            }
            TopBar { NextPassRow(pass = uiState.orbitalPass, isUtc = uiState.isUtc) }
        } else {
            TopBar {
                IconCard(action = { onAction(MapAction.SelectPrev) }, resId = R.drawable.ic_arrow, modifier = rotateMod)
                TimerRow(timeString = timeString, isTimeAos = isTimeAos)
                NextPassRow(pass = uiState.orbitalPass, modifier = Modifier.weight(1f), isUtc = uiState.isUtc)
                IconCard(action = { onAction(MapAction.SelectNext) }, resId = R.drawable.ic_arrow)
            }
        }
        ElevatedCard(modifier = Modifier.weight(1f)) {
            Box(contentAlignment = Alignment.BottomCenter) {
                // Track grid-mode transitions. When the map was just restored to
                // the viewport the user left (re-entry after a page switch) and
                // grid mode is already on, treat the entry as already centered so
                // the restored position survives. Cold-start entries and in-page
                // toggles still center on the station grid as before.
                var prevGridMode by remember {
                    mutableStateOf(restoredViewport && uiState.isGridMode)
                }
                AndroidView({ mapView }) { view ->
                    // Theme change: recolor the paints/tile filter/overlays and drop the icon
                    // caches, which baked the previous theme's colors into their bitmaps.
                    if (appliedLightUi != uiState.isLightUi) {
                        appliedLightUi = uiState.isLightUi
                        applyMapColors(uiState.isLightUi)
                        iconCache.evictAll()
                        dotIcon = null
                        view.overlayManager.tilesOverlay.setColorFilter(
                            if (!uiState.isLightUi && offlineTilesActive) darkTileFilter else null
                        )
                        requestTileRefresh(view)
                    }
                    // Award filter mode: show the selected award's regions with
                    // worked ones filled green; hide satellite layers like grid mode.
                    val awardMode = activeAward?.takeIf { it != AwardType.VUCC }
                    if (uiState.isGridMode && awardMode != null) {
                        setAwardMode(
                            awardMode,
                            awardProgress.firstOrNull { it.type == awardMode }?.workedKeys.orEmpty(),
                            view
                        )
                        prevGridMode = uiState.isGridMode
                    } else {
                        // Center on the station grid whenever entering grid mode.
                        // (prevGridMode is initialized to "already centered" when
                        // the map was just restored to the viewport the user left,
                        // so a re-entry keeps the restored position; in-page
                        // toggles still center normally.)
                        // Only mark the transition as consumed once a real position
                        // was available; otherwise a first frame with a null
                        // stationPosition would swallow the centering forever.
                        val shouldCenter = uiState.isGridMode && !prevGridMode
                        setGridMode(
                            uiState.isGridMode, workedGrids, uiState.roamedGrids, view,
                            uiState.stationPosition,
                            centerOnStation = shouldCenter,
                            // 首通呼号标签只作用于 VUCC 视图(用户要求); "All"/其他奖状不过滤.
                            showFirstCallLabels = uiState.showFirstCallLabels && activeAward == AwardType.VUCC,
                            firstCallsByGrid = firstCallsByGrid,
                            markedGrids = uiState.markedGrids
                        )
                        if (!shouldCenter || uiState.stationPosition != null) {
                            prevGridMode = uiState.isGridMode
                        }
                    }
                    if (!uiState.isGridMode) {
                        uiState.stationPosition?.let { setStationPosition(it, view, uiState.isLightUi) }
                        setOtherStationPosition(otherStationPosition, otherStationGrid, view)
                        if (passWindow != null) {
                            // Clear the previous pass immediately while the replacement
                            // track/position prediction runs on the same MapView.
                            setSatelliteTrack(uiState.track.orEmpty(), view)
                            val footprint = uiState.footprint
                            if (footprint != null) setFootprint(footprint, view)
                            else (view.overlays.getOrNull(OVERLAY_FOOTPRINT) as? FolderOverlay)
                                ?.items?.clear()
                            setPositions(uiState.positions.orEmpty(), view) {
                                item -> onAction(MapAction.SelectItem(item))
                            }
                        } else {
                            uiState.track?.let { setSatelliteTrack(it, view) }
                            uiState.footprint?.let { setFootprint(it, view) }
                            uiState.positions?.let { setPositions(it, view) { item -> onAction(MapAction.SelectItem(item)) } }
                        }
                        setTerminator(uiState.sunLatDeg, uiState.sunLonDeg, view)
                        setSubSolarPoint(uiState.sunLatDeg, uiState.sunLonDeg, view, uiState.isLightUi)
                        setMoonPosition(uiState.moonLatDeg, uiState.moonLonDeg, view, uiState.isLightUi)
                        // Both use the selected satellite's prediction at mapTimeMs, including scrubs.
                        (view.overlays[OVERLAY_ELEVATION_CONTOURS] as ElevationContourOverlay).apply {
                            isEnabled = showElevationContours
                            update(
                                if (showElevationContours) uiState.footprint else null,
                                elevationAngles,
                                view.resources.displayMetrics.density
                            )
                        }
                        (view.overlays[OVERLAY_STATION_ELEVATION] as StationElevationOverlay).apply {
                            isEnabled = true
                            station = uiState.stationPosition
                            elevationDeg = uiState.mapData?.elevation
                        }
                        // Time scrub: keep the scrubbed satellite centred, in the same pass that
                        // moves its marker so the two can never disagree. The pan is applied
                        // directly instead of as an osmdroid animation: the eased clock advances
                        // every frame now, and re-issuing an 80 ms animation that often made the
                        // camera trail the satellite (a moving target restarts it before it gets
                        // anywhere) instead of following it.
                        // Also fires for a new scrub SERIAL, not just while isScrubbing: a tap on
                        // the slider starts and finishes within one frame, so the flag is never
                        // seen true and the satellite used to end up off-screen.
                        if (uiState.isScrubbing || uiState.scrubSerial != lastFollowedScrubSerial) {
                            uiState.mapData?.osmPos?.let { point ->
                                view.controller.setCenter(GeoPoint(point.latitude, point.longitude))
                            }
                            lastFollowedScrubSerial = uiState.scrubSerial
                        }
                    }
                    if (uiState.isGridMode) {
                        view.overlays[OVERLAY_ELEVATION_CONTOURS].isEnabled = false
                        view.overlays[OVERLAY_STATION_ELEVATION].isEnabled = false
                    }
                    scrubInProgress = uiState.isScrubbing
                    view.invalidate()
                }
                if (passWindow != null && uiState.mapData == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Satellite mode: the time slider replaces the old info cards. Grid mode keeps
                // its own overlay set untouched.
                if (!uiState.isGridMode) {
                    if (passWindow != null) {
                        PassTimeScrubBar(
                            pass = passWindow,
                            mapTimeMs = uiState.mapTimeMs,
                            isUtc = uiState.isUtc,
                            copyrightResId = copyrightResId,
                            onSeek = { onAction(MapAction.SetPassTime(it)) },
                            onScrubStart = { onAction(MapAction.SetScrubbing(true)) },
                            onScrubEnd = { onAction(MapAction.SetScrubbing(false)) },
                            onReset = { onAction(MapAction.ResetTimeOffset) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    } else TimeScrubBar(
                        offsetMs = uiState.timeOffsetMs,
                        spanMs = scrubSpanMs,
                        mapTimeMs = uiState.mapTimeMs,
                        isUtc = uiState.isUtc,
                        copyrightResId = copyrightResId,
                        onScrub = { onAction(MapAction.SetTimeOffset(it)) },
                        onScrubStart = { onAction(MapAction.SetScrubbing(true)) },
                        onScrubEnd = { onAction(MapAction.SetScrubbing(false)) },
                        onReset = { onAction(MapAction.ResetTimeOffset) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                // Satellite mode: a semi-transparent pill mirroring the mode switch on the right.
                if (!uiState.isGridMode && !gridPickerMode) {
                    ElevationContourToggle(
                        checked = showElevationContours,
                        onToggle = { checked ->
                            if (checked) {
                                elevationEnabledBeforeDialog = showElevationContours
                                elevationDraft = elevationInputs
                                showElevationContours = true
                                elevationDialogVisible = true
                            } else showElevationContours = false
                        },
                        onConfigure = {
                            elevationEnabledBeforeDialog = true
                            elevationDraft = elevationInputs
                            elevationDialogVisible = true
                        },
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    )
                }
                // Top-left: 台址 selector for VUCC counting (grid mode only).
                // Shown whenever per-grid QSO data exists; empty selection means All.
                if (uiState.isGridMode && activeAward == AwardType.VUCC && stationGroups.isNotEmpty()) {
                    VuccGridSelector(
                        stations = stationGroups,
                        allCount = uiState.workedGrids.size,
                        selected = selectedStationIds,
                        onSelect = onSelectedStationIdsChange,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }
                // Top-right: grid-mode pill + (grid mode only) the first-call
                // label pill, stacked vertically.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (passWindow == null && !gridPickerMode) GridModeToggle(
                        isGridMode = uiState.isGridMode,
                        onToggle = { onAction(MapAction.ToggleGridMode(it)) }
                    )
                    // 首通呼号开关仅 VUCC 视图显示(与 VuccGridSelector 同条件).
                    if (!gridPickerMode && uiState.isGridMode && activeAward == AwardType.VUCC) {
                        FirstCallToggle(
                            checked = uiState.showFirstCallLabels,
                            onToggle = { onAction(MapAction.ToggleFirstCallLabels(it)) }
                        )
                    }
                }
            }
        }
    }
    if (elevationDialogVisible) {
        ElevationContourSettingsDialog(
            draft = elevationDraft,
            onDraftChange = { elevationDraft = it },
            onDismiss = closeElevationDialog,
            onConfirm = {
                elevationInputs = elevationDraft
                showElevationContours = true
                elevationDialogVisible = false
            }
        )
    }
    // Centered dialog listing the tapped worked grid's confirmed satellite QSOs.
    // Dismissed by tapping outside (no explicit close button). Unworked grids
    // open the same dialog with an empty list plus a mark-a-station reminder.
    selectedGrid?.let { grid ->
        WorkedGridQsoDialog(
            grid = grid,
            // A multi-selection shows the union of QSO details without duplicates,
            // preserving each station's covering scope; empty selection means All.
            qsos = uiState.workedGridQsos[grid].orEmpty()
                .scopedToStations(selectedStationIds)
                .sortedBy { it.epochMs },
            marked = uiState.markedGrids[grid].orEmpty(),
            isUtc = uiState.isUtc,
            matchCalculating = matchCalculating,
            onDismiss = { selectedGrid = null },
            onMatch = { onMatchGrid(grid) },
            onMark = { call -> onAction(MapAction.SetMarkedStation(grid, call)) },
            onRemoveMark = { call -> onAction(MapAction.RemoveMarkedStation(grid, call)) },
            onPinMark = { call -> onAction(MapAction.PinMarkedStation(grid, call)) }
        )
    }
    // Region detail dialog for a tapped award region. Read-only: the Match
    // button and station marks are grid-scoped and stay hidden here. The list
    // is the GLOBAL one (all 台址) — the non-VUCC awards count and fill the
    // map globally, so their detail lists use the same scope.
    selectedRegion?.let { (type, region) ->
        val regionQsos = remember(uiState.workedGridQsos, type, region, waipaGridResolver) {
            AwardCalculator.regionQsos(
                uiState.workedGridQsos, type, region.code, waipaGridResolver
            )
        }
        RegionQsoDialog(
            type = type,
            region = region,
            qsos = regionQsos,
            isUtc = uiState.isUtc,
            onDismiss = { selectedRegion = null }
        )
    }
}

// region Worked-grid QSO dialog

@Composable
private fun WorkedGridQsoDialog(
    grid: String,
    qsos: List<com.rtbishop.look4sat.core.domain.model.GridQso>,
    marked: List<com.rtbishop.look4sat.core.domain.model.MarkedStation>,
    isUtc: Boolean,
    matchCalculating: Boolean,
    onDismiss: () -> Unit,
    onMatch: () -> Unit,
    onMark: (String) -> Unit,
    onRemoveMark: (String) -> Unit,
    onPinMark: (String) -> Unit
) {
    var showMarkInput by remember { mutableStateOf(false) }
    var callInput by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // Centered card, ~85% width, internal scroll for long lists.
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column {
                // Header: grid + counts on the left, "Match" action on the right
                // (jumps to the match page pre-filled with this grid).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = grid,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        val distinctCalls = qsos.map { it.call }.distinct().size
                        Text(
                            text = stringResource(R.string.grid_qso_calls_count, distinctCalls, qsos.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        enabled = !matchCalculating,
                        onClick = {
                            onDismiss()
                            onMatch()
                        }
                    ) {
                        if (matchCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_radio_tower),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.grid_qso_match))
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Marked stations show first as red reminder rows: pale-yellow
                    // callsign, "标记" in the count column, the marking date in the
                    // date column, a small pin icon (first = pinned, grey; others
                    // pale yellow) and a delete icon to remove the mark.
                    val showPin = marked.size > 1
                    marked.forEachIndexed { index, station ->
                        MarkedStationRow(
                            marked = station,
                            isPinned = index == 0,
                            showPin = showPin,
                            isUtc = isUtc,
                            onRemoveMark = { onRemoveMark(station.call) },
                            onPinMark = { onPinMark(station.call) }
                        )
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    if (qsos.isNotEmpty()) {
                        // Group by callsign preserving first-contact order (list is
                        // already sorted oldest-first); expandable rows.
                        val grouped = remember(qsos) {
                            qsos.groupBy { it.call }.entries.sortedBy { it.value.first().epochMs }
                        }
                        grouped.forEach { (call, callQsos) ->
                            WorkedGridCallRow(call, callQsos = callQsos, isUtc = isUtc)
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                    // Unworked grids: offer to mark a station the user wants to
                    // contact in this grid (a reminder, cleared when worked).
                    if (qsos.isEmpty()) {
                        TextButton(
                            onClick = {
                                callInput = ""
                                showMarkInput = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.grid_mark_station))
                        }
                    }
                }
            }
        }
    }
    // Mark-a-station input dialog: enter a callsign as a reminder.
    if (showMarkInput) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMarkInput = false },
            shape = sheetDialogShape(),
            containerColor = sheetDialogContainerColor(),
            title = { SheetDialogTitle(stringResource(R.string.grid_mark_station)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = callInput,
                    onValueChange = { callInput = it.uppercase() },
                    label = { Text(stringResource(R.string.grid_mark_callsign)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = callInput.isNotBlank(),
                    onClick = {
                        onMark(callInput)
                        showMarkInput = false
                    }
                ) { Text(stringResource(R.string.grid_mark_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showMarkInput = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

/**
 * Centered dialog listing the confirmed satellite QSOs counted for a tapped
 * award region (DXCC/WAPC/WAJA/WAIPA/WAZ/WAS). Read-only variant of the worked-grid
 * dialog: the header shows the region name + code, and the same expandable
 * callsign rows. Match / station marks are grid-scoped and stay hidden.
 * Dismissed by tapping outside. An unworked region opens with an empty list.
 */
@Composable
private fun RegionQsoDialog(
    type: AwardType,
    region: AwardRegion,
    qsos: List<com.rtbishop.look4sat.core.domain.model.GridQso>,
    isUtc: Boolean,
    onDismiss: () -> Unit
) {
    // WAZ regions are named by their bare zone number in the asset, so the
    // title gets the full "CQ ZONE 24" wording (user request 2026-10-05) and
    // the code is not repeated in the subtitle. DXCC shows the entity's call
    // prefix instead of the numeric code ("BY", user request 2026-10-06);
    // other awards keep the bare code.
    val isZone = type == AwardType.WAZ
    val codeLabel = when {
        isZone -> ""
        type == AwardType.DXCC -> "${region.pfx.ifBlank { region.code }} · "
        else -> "${region.code} · "
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                        // Match the grid dialog header height exactly: its Match button
                        // enforces a 48dp minimum touch target, inflating the row and
                        // lowering the title to 6dp from the dialog edge.
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    // Header mirrors the worked-grid dialog exactly (same font,
                    // same paddings, same weight layout) — user request
                    // 2026-10-05: 标题字体/位置与 VUCC 的统一.
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isZone) "CQ ZONE ${region.code}" else regionDisplayName(region),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Text(
                            text = codeLabel + stringResource(
                                R.string.grid_qso_calls_count,
                                qsos.map { it.call }.distinct().size,
                                qsos.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (qsos.isEmpty()) {
                        Text(
                            text = stringResource(R.string.region_qso_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    } else {
                        // Same grouping as the worked-grid dialog: by callsign
                        // preserving first-contact order (list is oldest-first).
                        val grouped = remember(qsos) {
                            qsos.groupBy { it.call }.entries.sortedBy { it.value.first().epochMs }
                        }
                        grouped.forEach { (call, callQsos) ->
                            WorkedGridCallRow(call, callQsos = callQsos, isUtc = isUtc)
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Region display label, same language rule as the overlay's map labels. */
@Composable
private fun regionDisplayName(region: AwardRegion): String {
    val useEnglish =
        androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language != "zh"
    return if (useEnglish) region.nameEn ?: region.name else region.name
}

@Composable
private fun MarkedStationRow(
    marked: com.rtbishop.look4sat.core.domain.model.MarkedStation,
    isPinned: Boolean,
    showPin: Boolean,
    isUtc: Boolean,
    onRemoveMark: () -> Unit,
    onPinMark: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = marked.call,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // "标记" column: pin icon (only when the grid has multiple marks) sits
        // left of the label; pinned (first) row's icon is grey, others pale
        // yellow — tapping moves that mark to the top. Wrap-content (no weight),
        // same as the count column of the QSO rows, so the callsign column next
        // to it takes the leftover width while these glyphs stay row-centred.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (showPin) {
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = stringResource(R.string.grid_mark_pin),
                    tint = if (isPinned) ComposeColor(0xFF9E9E9E) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onPinMark)
                )
                Spacer(Modifier.width(2.dp))
            }
            Text(
                text = stringResource(R.string.grid_mark_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = formatDate(marked.epochMs, isUtc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.grid_mark_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRemoveMark)
            )
        }
    }
}

@Composable
private fun WorkedGridCallRow(
    call: String,
    callQsos: List<com.rtbishop.look4sat.core.domain.model.GridQso>,
    isUtc: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val first = callQsos.first()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Text(
                text = call,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                // Plurals resources don't work for zh (only 'other' matches), so
                // pick the singular/plural string explicitly.
                text = if (callQsos.size == 1) stringResource(R.string.grid_qso_count_one, 1)
                else stringResource(R.string.grid_qso_count_many, callQsos.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                // No weight: the count column wraps its content, so the callsign
                // column (weight 1f, opposite the date column) absorbs the slack
                // that used to sit left of the centred count text. The count text
                // keeps its exact on-screen position: with equal weights on the
                // outer columns the wrap-content middle column starts where the
                // centred text used to start, i.e. its centre stays at row centre.
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = formatDate(first.epochMs, isUtc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (expanded) " ▴" else " ▾",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // First-QSO summary line, always visible.
        Text(
            text = stringResource(R.string.grid_qso_first) + " · " + qsoSummary(first, isUtc),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 3.dp)
        )
        // Subsequent QSOs in this grid, revealed on expand. No ordinal prefix;
        // each line carries its own date (the first QSO's date already sits in
        // the header row).
        if (expanded && callQsos.size > 1) {
            callQsos.drop(1).forEach { qso ->
                Text(
                    text = formatDate(qso.epochMs, isUtc) + " · " + qsoSummary(qso, isUtc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

private fun qsoSummary(qso: com.rtbishop.look4sat.core.domain.model.GridQso, isUtc: Boolean): String {
    val sat = qso.satName.ifBlank { "?" }
    val mode = qso.mode.ifBlank { "?" }
    val band = qso.bandLabel
    val time = formatTime(qso.epochMs, isUtc)
    return listOf(sat, mode, band, time).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun formatDate(epochMs: Long, isUtc: Boolean): String {
    if (epochMs <= 0L) return "--"
    val zone = if (isUtc) java.time.ZoneOffset.UTC else java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}

private fun formatTime(epochMs: Long, isUtc: Boolean): String {
    if (epochMs <= 0L) return "--:--"
    val zone = if (isUtc) java.time.ZoneOffset.UTC else java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern(if (isUtc) "HH:mm'Z'" else "HH:mm"))
}
// endregion

// region Time scrub bar

/** Fixed AOS–LOS slider; unlike the normal map, neither endpoint moves with "now". */
@Composable
private fun PassTimeScrubBar(
    pass: RadarPassMapDestination,
    mapTimeMs: Long,
    isUtc: Boolean,
    copyrightResId: Int,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = (pass.losTime - pass.aosTime).coerceAtLeast(1L)
    val fraction = ((mapTimeMs - pass.aosTime).toDouble() / duration).toFloat().coerceIn(0f, 1f)
    // Keep the thumb at the finger while the asynchronous orbital prediction catches up.
    var draggedFraction by remember(pass.aosTime, pass.losTime) { mutableStateOf<Float?>(null) }
    val labelShadow = Shadow(color = ComposeColor.Black.copy(alpha = 0.85f), blurRadius = 4f)
    Column(
        modifier = modifier.fillMaxWidth()
            .background(ComposeColor.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatTime(mapTimeMs.coerceIn(pass.aosTime, pass.losTime), isUtc),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                style = LocalTextStyle.current.copy(shadow = labelShadow),
                modifier = Modifier.align(Alignment.Center)
            )
            TextButton(
                onClick = onReset,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Text(
                    text = stringResource(R.string.map_time_reset),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    style = LocalTextStyle.current.copy(shadow = labelShadow)
                )
            }
        }
        Slider(
            value = draggedFraction ?: fraction,
            onValueChange = { value ->
                draggedFraction = value
                onSeek(timeOnRadarPass(pass.aosTime, pass.losTime, value))
            },
            onValueChangeFinished = {
                draggedFraction = null
                onScrubEnd()
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = ComposeColor.White.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
                .pointerInput(pass.aosTime, pass.losTime) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onScrubStart()
                        waitForUpOrCancellation()
                    }
                }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(pass.aosTime, isUtc), color = ComposeColor.White, fontSize = 11.sp)
            Text(formatTime(pass.losTime, isUtc), color = ComposeColor.White, fontSize = 11.sp)
        }
        Text(stringResource(copyrightResId), color = ComposeColor.White, fontSize = 9.sp)
    }
}


/**
 * Time constant of the eased scrub clock, in milliseconds. The readout and the map chase the
 * finger over roughly this long (settling in about three times it): the steps between two finger
 * positions, each worth tens of seconds of orbit, become continuous motion instead of jumps.
 */
private const val SCRUB_EASE_MS = 100.0

/** Fraction of the span at which the eased clock counts as caught up (~0.05 % of the track). */
private const val SCRUB_EASE_EPSILON = 0.0005f

/** Width reserved for the drag bubble, used to keep it inside the slider's track. */
private val scrubBubbleWidth = 64.dp

/**
 * Bottom bar of satellite mode: one slider that scrubs the map's clock forward from "now" over
 * the window the ground track is drawn for, so the scrubbed marker always sits on the drawn
 * track. While the finger is down the satellite marker, its footprint and the terminator follow
 * the thumb; a bubble above it shows that instant in the same UTC/local format as the rest of
 * the app. Releasing keeps the offset — the map keeps running at that offset, so the pass can be
 * watched — until "Now" is tapped. This replaces the old info cards; their readings live on the
 * radar page.
 */
@Composable
private fun TimeScrubBar(
    offsetMs: Long,
    spanMs: Long,
    mapTimeMs: Long,
    isUtc: Boolean,
    copyrightResId: Int,
    onScrub: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Finger position: the thumb follows it 1:1 and it is the value the clock eases towards.
    var fingerFraction by remember { mutableStateOf(0f) }
    // Reported position: the readout and the map ease onto the finger's value over a few frames.
    // One slider pixel is ~48 s of orbit, so pushing the raw finger value stepped the satellite
    // and its footprint by hundreds of kilometres on every update — during a quick drag that reads
    // as teleporting. Easing turns those steps into continuous motion and settles exactly.
    var shownFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // True from the first touch until the eased clock has caught up with the finger's last value,
    // i.e. it outlives the release by a few frames. That tail is what keeps the satellite centred
    // after a fast flick: reporting the finger's raw value the moment it is lifted would jump the
    // clock by the whole easing lag (minutes of orbit on a quick swipe) while the camera had
    // already stopped following, so the marker would land far off-screen.
    var easing by remember { mutableStateOf(false) }
    // "Now" captured when the gesture starts: the readout must not jitter with the wall clock
    // while the finger is already deciding the instant.
    var anchorMs by remember { mutableStateOf(0L) }
    // The instant the finger is pointing at. It is frozen for as long as the gesture lasts, which is
    // what the readout shows and what the map must therefore render.
    fun pickedInstantMs(fraction: Float): Long = anchorMs + (fraction * spanMs).toLong()
    // What goes to the map: the picked instant expressed as an offset from the moment the map reads
    // it. Reporting `fraction * span` as-is (i.e. "offset from now") made the map time creep forward
    // with the wall clock during a hold — the satellite drifted while the readout stood still — and
    // jump forward by the whole hold on release, so a two-minute hold landed two minutes past the
    // instant the finger had chosen.
    fun offsetFor(fraction: Float): Long =
        (pickedInstantMs(fraction) - System.currentTimeMillis()).coerceAtLeast(0L)
    // Re-sync when the offset changes from outside (the reset button).
    LaunchedEffect(offsetMs, spanMs) {
        if (!easing) {
            fingerFraction = (offsetMs.toFloat() / spanMs.toFloat()).coerceIn(0f, 1f)
            shownFraction = fingerFraction
        }
    }
    // Easing loop: chase the finger frame by frame and report the eased instant. Frame-rate
    // independent exponential approach; the map only ever moves by small increments, so even a
    // flick glides. It keeps running for a moment after the finger is lifted (see `easing`) and
    // stops reporting once it has caught up.
    LaunchedEffect(easing, spanMs) {
        if (!easing) return@LaunchedEffect
        try {
            var lastFrameMs = 0.0
            var lastPickedMs = Long.MIN_VALUE
            while (true) {
                var changed = false
                var settled = false
                withFrameNanos { frameNs ->
                    val frameMs = frameNs / 1_000_000.0
                    val deltaMs = if (lastFrameMs == 0.0) 16.0 else (frameMs - lastFrameMs)
                    lastFrameMs = frameMs
                    val factor = (1.0 - exp(-deltaMs / SCRUB_EASE_MS)).coerceIn(0.0, 1.0)
                    shownFraction += ((fingerFraction - shownFraction) * factor).toFloat()
                    if (abs(fingerFraction - shownFraction) < SCRUB_EASE_EPSILON) {
                        shownFraction = fingerFraction
                        settled = true
                    }
                    val pickedMs = pickedInstantMs(shownFraction)
                    if (pickedMs != lastPickedMs) {
                        lastPickedMs = pickedMs
                        changed = true
                    }
                }
                if (changed) onScrub(offsetFor(shownFraction))
                // Finger up and clock caught up: settle on the exact instant, then hand the map back
                // to the tick loop (which is what makes the camera stop following).
                if (settled && !dragging) {
                    onScrub(offsetFor(shownFraction))
                    onScrubEnd()
                    easing = false
                    break
                }
            }
        } finally {
            // Also runs when this effect is cancelled instead of finishing — which happens whenever
            // `spanMs` changes (i.e. the selection moved to a satellite with another period) or the
            // bar leaves the composition. Without this the scrub flag stayed set: the tick loop
            // stands down while it is set, so the map and its ground track froze on the last
            // scrubbed instant until the user dragged again.
            if (easing) {
                easing = false
                dragging = false
                onScrubEnd()
            }
        }
    }
    // The bar has no backing panel: it sits straight on the map. Contrast for its labels comes
    // from a soft halo (plus the clock's own capsule) instead of a black box behind them.
    val labelShadow = Shadow(color = ComposeColor.Black.copy(alpha = 0.85f), blurRadius = 4f)
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fixed-height slot so the readout never moves the slider under the finger. The clock
            // stays visible: while the finger is down it shows the eased instant (the same one the
            // map is rendering), otherwise the instant the tick loop last rendered.
            Box(modifier = Modifier.fillMaxWidth().height(22.dp)) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatTime(
                            if (dragging) pickedInstantMs(shownFraction) else mapTimeMs,
                            isUtc
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .offset(x = ((maxWidth - scrubBubbleWidth) * fingerFraction).coerceAtLeast(0.dp))
                            .background(ComposeColor.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // The Material slider only reports a value once the finger *moves* (a press on its
                // own is delivered on release, as a tap), so pressing a new spot used to leave the
                // map on the old instant until the drag started. This observes the same press
                // without consuming it, maps the finger's x onto the track's travel and seeks right
                // away; the slider's own drag/tap reports take over from there and correct the few
                // pixels this mapping assumes for the thumb inset.
                val pressSeek = Modifier.pointerInput(spanMs) {
                    // M3's thumb is a 4 dp bar, so the travel is inset by half of it at each end.
                    val thumbInset = 2.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val travel = size.width - thumbInset * 2f
                        if (travel > 0f) {
                            val fraction =
                                ((down.position.x - thumbInset) / travel).coerceIn(0f, 1f)
                            if (!dragging) {
                                dragging = true
                                easing = true
                                anchorMs = System.currentTimeMillis()
                                shownFraction = fraction
                                onScrubStart()
                            }
                            fingerFraction = fraction
                            onScrub(offsetFor(fraction))
                            // The slider reports the end of its own gesture; this is the safety net
                            // for a press it never reports at all, which would otherwise leave the
                            // bar parked in scrub mode with the prediction loop standing down.
                            waitForUpOrCancellation()
                            dragging = false
                        }
                    }
                }
                Slider(
                    value = fingerFraction,
                    onValueChange = { value ->
                        if (!dragging) {
                            dragging = true
                            easing = true
                            anchorMs = System.currentTimeMillis()
                            // Start the eased clock where the finger grabbed the track, so the
                            // readout does not glide in from wherever it was before.
                            shownFraction = value
                            onScrubStart()
                        }
                        fingerFraction = value
                    },
                    onValueChangeFinished = {
                        // The clock keeps easing (and the camera keeps following) until it has
                        // caught up with fingerFraction; the loop ends the gesture itself.
                        dragging = false
                    },
                    valueRange = 0f..1f,
                    // Stated explicitly: with no panel behind it, the theme's inactive track (a
                    // surface tint) can vanish against the map tiles.
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = ComposeColor.Black.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.weight(1f).then(pressSeek)
                )
                // Always rendered: the button is part of the bar from the first frame, so the row
                // never re-layouts when a scrub starts. It greys out while the map is live (there
                // is nothing to reset). The grey is stated explicitly: the theme's disabled colour
                // comes from onSurface and would be dark-on-dark over this bar's translucent black.
                TextButton(
                    onClick = onReset,
                    enabled = offsetMs > 0L,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = ComposeColor.White.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.map_time_reset),
                        fontSize = 12.sp,
                        style = LocalTextStyle.current.copy(shadow = labelShadow)
                    )
                }
            }
            Text(
                text = stringResource(copyrightResId),
                color = ComposeColor.White.copy(alpha = 0.9f),
                fontSize = 9.sp,
                style = LocalTextStyle.current.copy(shadow = labelShadow)
            )
        }
    }
}
// endregion

// region Grid mode toggle pill

/**
 * Award filter chips row shown in grid mode (replaces the satellite-selection
 * top bar). "All" = plain worked-grid view; each award chip carries its live
 * progress (count/target) and selecting it switches the map to the award's
 * boundary view.
 */
@Composable
private fun AwardChipsRow(
    progress: List<AwardProgress>,
    selected: AwardType?,
    onSelect: (AwardType?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // No "All" chip: it was redundant with VUCC — both show the plain
        // worked-grid map (VUCC never enters the boundary-overlay mode).
        items(progress) { p ->
            FilterChip(
                selected = selected == p.type,
                onClick = { onSelect(p.type) },
                label = {
                    Text(
                        text = "${p.type.name} ${p.count}/${p.target}",
                        fontSize = 12.sp
                    )
                },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}

/**
 * One 台址 shown by the operated-grid selector. A 台址 is a station location
 * and can span SEVERAL grids (MY_GRIDSQUARE + MY_VUCC_GRIDS); groups are keyed
 * by the 台址's full grid set, so a multi-grid 台址 is one entry listing all
 * its grids. Data synced before multi-grid support falls back to one group
 * per grid.
 */
private data class StationGroup(
    /** Grid-set key (sorted 4-char grids joined by ","). */
    val id: String,
    /** Full grid set of this 台址 (1..n 4-char grids). */
    val grids: Set<String>,
    /** Distinct worked grids worked under this 台址 (any of its grids). */
    val worked: Set<String>
) {
    val workedCount: Int get() = worked.size
    /** Selector label: the grids themselves — "OL62" for a single-grid 台址,
     *  "OM60,PM01" for a multi-grid one (matches how 台址 are described). */
    val label: String get() = grids.sorted().joinToString(",")
}

/** Compact pill toggle for switching between satellite view and grid mode,
 * floated over the map's top-right corner. The label reflects the active
 * mode ("Grid mode" when ON, "Satellite mode" when OFF). Semi-transparent
 * background keeps the map readable underneath. */
@Composable
private fun VuccGridSelector(
    stations: List<StationGroup>,
    allCount: Int,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // Preserve the former ordering: most-worked first, All last.
    val sorted = remember(stations) { stations.sortedByDescending { it.workedCount } }
    val selectedCount = remember(stations, selected, allCount) {
        if (selected.isEmpty()) allCount
        else stations.asSequence().filter { it.id in selected }
            .flatMap { it.worked.asSequence() }.toSet().size
    }
    val label = when {
        selected.isEmpty() -> "All ($allCount)"
        selected.size == 1 -> {
            val station = stations.firstOrNull { it.id in selected }
            "${station?.label ?: "1 station"} ($selectedCount)"
        }
        else -> "${selected.size} stations ($selectedCount)"
    }
    Surface(
        color = ComposeColor.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = label,
                    color = ComposeColor.White,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    text = if (expanded) " ▴" else " ▾",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            // Popup dismisses on outside tap; checkbox taps apply immediately
            // and deliberately keep the menu open (no Apply/Done button).
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = ComposeColor.Black.copy(alpha = 0.90f),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                sorted.forEach { station ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${station.label} (${station.workedCount})",
                                color = ComposeColor.White,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        },
                        leadingIcon = {
                            Checkbox(
                                checked = station.id in selected,
                                onCheckedChange = null,
                                modifier = Modifier.scale(0.8f),
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    uncheckedColor = ComposeColor.White
                                )
                            )
                        },
                        onClick = {
                            onSelect(
                                if (station.id in selected) selected - station.id
                                else selected + station.id
                            )
                        }
                    )
                }
                // All is mutually exclusive with specific station selections.
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "All ($allCount)",
                            color = ComposeColor.White,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    },
                    leadingIcon = {
                        Checkbox(
                            checked = selected.isEmpty(),
                            onCheckedChange = null,
                            modifier = Modifier.scale(0.8f),
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = ComposeColor.White
                            )
                        )
                    },
                    onClick = { onSelect(emptySet()) }
                )
            }
        }
    }
}
/**
 * Compact pill toggle for switching between satellite view and grid mode,
 * floated over the map's top-right corner. The label reflects the active
 * mode ("Grid mode" when ON, "Satellite mode" when OFF). Semi-transparent
 * background keeps the map readable underneath.
 */
@Composable
private fun GridModeToggle(
    isGridMode: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ComposeColor.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = stringResource(
                    if (isGridMode) R.string.map_grid_mode else R.string.map_satellite_mode
                ),
                color = ComposeColor.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 2.dp)
            )
            Switch(
                checked = isGridMode,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.75f)
            )
        }
    }
}

/** Map top-left switch; tapping its label while enabled reopens the contour editor. */
@Composable
private fun ElevationContourToggle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ComposeColor.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .clickable { if (checked) onConfigure() else onToggle(true) }
                .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.map_elevation_contours),
                color = ComposeColor.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 2.dp)
            )
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.75f)
            )
        }
    }
}

@Composable
private fun ElevationContourSettingsDialog(
    draft: List<String>,
    onDraftChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val parsed = draft.map { it.trim().toDoubleOrNull() }
    val valid = parsed.isNotEmpty() && parsed.all { it != null && it.isFinite() && it >= 0.0 && it < 90.0 } &&
        parsed.distinct().size == parsed.size
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(R.string.map_elevation_contours_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                draft.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(
                                    ComposeColor(ELEVATION_CONTOUR_COLORS[index]),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { next ->
                                onDraftChange(draft.toMutableList().apply { this[index] = next })
                            },
                            label = { Text(stringResource(R.string.map_elevation_angle, index + 1)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (draft.size > 1) {
                            TextButton(onClick = {
                                onDraftChange(draft.toMutableList().apply { removeAt(index) })
                            }) { Text("−") }
                        }
                    }
                }
                TextButton(
                    enabled = draft.size < ELEVATION_CONTOUR_COLORS.size,
                    onClick = { onDraftChange(draft + "") }
                ) { Text("+ " + stringResource(R.string.map_elevation_add_circle)) }
                if (!valid) {
                    Text(
                        stringResource(R.string.map_elevation_angle_hint),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = valid) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * 网格模式下的"首通呼号"开关: 打开后绿格(worked)的标签显示该格第一个通联的
 * 呼号, 非绿格不显示任何文字. 仅在网格模式下显示(与网格模式开关叠放).
 */
@Composable
private fun FirstCallToggle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ComposeColor.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = stringResource(R.string.map_first_call_mode),
                color = ComposeColor.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 2.dp)
            )
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.75f)
            )
        }
    }
}

// endregion

// region Tile sources
private var configuredTileMapView: MapView? = null
private var configuredTileSource: String? = null
private var configuredTileIsLightUi: Boolean? = null

private fun configureTileSources(mapView: MapView, mapSource: String, tiandituKey: String, isLightUi: Boolean) {
    val key = tiandituKey.trim()
    val layers = tiandituLayers(mapSource)
    val offlineSource = layers == null || key.isBlank()
    offlineTilesActive = offlineSource
    val sourceKey = if (offlineSource) MapSource.OSM else "${layers.base}:$key"
    if (configuredTileMapView === mapView && configuredTileSource == sourceKey &&
        configuredTileIsLightUi == isLightUi
    ) return

    configuredTileMapView = mapView
    configuredTileSource = sourceKey
    configuredTileIsLightUi = isLightUi
    (mapView.overlays.getOrNull(OVERLAY_TDT_LABELS) as? TilesOverlay)?.onDetach(mapView)

    if (offlineSource) {
        mapView.setUseDataConnection(false)
        mapView.setTileSource(offlineTileSource)
        mapView.maxZoomLevel = OFFLINE_MAX_ZOOM
        // The dark-map filter belongs to the dark theme only; the light theme shows plain tiles.
        mapView.overlayManager.tilesOverlay.applyTileOverlayDefaults(if (isLightUi) null else darkTileFilter)
        mapView.overlays[OVERLAY_TDT_LABELS] = FolderOverlay()
        requestTileRefresh(mapView)
        return
    }

    mapView.setUseDataConnection(true)
    mapView.setTileSource(TiandituTileSource(layer = layers.base, key = key))
    mapView.maxZoomLevel = TIANDITU_MAX_ZOOM
    mapView.overlayManager.tilesOverlay.applyTileOverlayDefaults(null)
    mapView.overlays[OVERLAY_TDT_LABELS] = TilesOverlay(
        MapTileProviderBasic(mapView.context, TiandituTileSource(layer = layers.label, key = key)),
        mapView.context
    ).apply {
        applyTileOverlayDefaults(null)
        setUseDataConnection(true)
    }
    requestTileRefresh(mapView)
}

private data class TiandituLayers(val base: String, val label: String)

private fun tiandituLayers(mapSource: String): TiandituLayers? = when (MapSource.normalize(mapSource)) {
    MapSource.TIANDITU_VECTOR -> TiandituLayers(base = "vec", label = "cva")
    MapSource.TIANDITU_IMAGE -> TiandituLayers(base = "img", label = "cia")
    else -> null
}

private fun TilesOverlay.applyTileOverlayDefaults(colorFilter: ColorMatrixColorFilter?) {
    setColorFilter(colorFilter)
    setLoadingBackgroundColor(Color.TRANSPARENT)
    setLoadingLineColor(Color.TRANSPARENT)
}

private fun requestTileRefresh(mapView: MapView) {
    mapView.post {
        mapView.requestLayout()
        mapView.invalidate()
    }
}

private fun buildTiandituWmtsUrl(
    baseUrl: String,
    layer: String,
    zoom: Int,
    row: Int,
    col: Int,
    encodedKey: String
): String = "$baseUrl?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layer" +
    "&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=$zoom&TILEROW=$row&TILECOL=$col&tk=$encodedKey"

private class TiandituTileSource(
    private val layer: String,
    key: String
) : OnlineTileSourceBase(
    "tianditu-wmts-https-$layer",
    1,
    TIANDITU_MAX_ZOOM.toInt(),
    256,
    ".png",
    Array(8) { index -> "https://t$index.tianditu.gov.cn/${layer}_w/wmts" },
    "Tianditu"
) {
    private val encodedKey = URLEncoder.encode(key, Charsets.UTF_8.name())

    override fun getTileURLString(pMapTileIndex: Long): String = buildTiandituWmtsUrl(
        baseUrl = getBaseUrl(),
        layer = layer,
        zoom = MapTileIndex.getZoom(pMapTileIndex),
        row = MapTileIndex.getY(pMapTileIndex),
        col = MapTileIndex.getX(pMapTileIndex),
        encodedKey = encodedKey
    )
}
// endregion

// region Map overlay helpers

/**
 * Switches the grid-slot overlay to the award boundary view for the given
 * award: loads the boundary asset once (cached), paints worked regions green.
 * Satellite-related layers are hidden exactly like grid mode. VUCC keeps the
 * plain Maidenhead grid overlay (the grid view IS the VUCC view).
 */
private fun setAwardMode(award: AwardType, workedCodes: Set<String>, mapView: MapView) {
    val asset = when (award) {
        AwardType.WAPC -> AwardBoundaryData.AwardAsset.WAPC
        AwardType.WAJA -> AwardBoundaryData.AwardAsset.WAJA
        AwardType.WAIPA -> AwardBoundaryData.AwardAsset.WAIPA
        AwardType.WAZ -> AwardBoundaryData.AwardAsset.WAZ
        AwardType.WAS -> AwardBoundaryData.AwardAsset.WAS
        AwardType.DXCC -> AwardBoundaryData.AwardAsset.DXCC
        AwardType.VUCC -> return
    }
    val overlay = mapView.overlays.getOrNull(OVERLAY_GRID)
    if (overlay is AwardBoundaryOverlay) {
        overlay.isEnabled = true
        overlay.regions = AwardBoundaryData.load(mapView.context, asset)
        overlay.workedCodes = workedCodes
    } else {
        mapView.overlays[OVERLAY_GRID] = AwardBoundaryOverlay().apply {
            isEnabled = true
            regions = AwardBoundaryData.load(mapView.context, asset)
            this.workedCodes = workedCodes
            applyTheme(mapIsLightUi == true)
        }
    }
    for (index in OVERLAY_STATION..OVERLAY_MOON) {
        mapView.overlays.getOrNull(index)?.isEnabled = false
    }
}

/** Toggle the grid-mode layer visibility on/off without recreating any overlay. */
private fun setGridMode(
    gridMode: Boolean,
    workedGrids: Set<String>,
    roamedGrids: Set<String>,
    mapView: MapView,
    stationPosition: GeoPos?,
    centerOnStation: Boolean = false,
    showFirstCallLabels: Boolean = false,
    firstCallsByGrid: Map<String, String> = emptyMap(),
    markedGrids: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>> = emptyMap()
) {
    try {
        val gridOverlay = mapView.overlays[OVERLAY_GRID]
        if (gridOverlay is MaidenheadGridOverlay) {
            gridOverlay.isEnabled = gridMode
            gridOverlay.workedGrids = workedGrids
            gridOverlay.roamedGrids = roamedGrids
            // 首通呼号模式: 绿格标注第一个通联的呼号, 非绿格不显示标签.
            gridOverlay.showFirstCallLabels = showFirstCallLabels
            gridOverlay.firstCallsByGrid = firstCallsByGrid
            gridOverlay.markedGrids = markedGrids
            // ownGrid must be set on EVERY update — the position is available
            // regardless of whether this frame centers (centering happens only
            // on entry, but passing null here would wipe the bold outline).
            gridOverlay.ownGrid = ownGridOf(stationPosition)
        } else {
            mapView.overlays[OVERLAY_GRID] = MaidenheadGridOverlay().apply {
                isEnabled = gridMode
                this.workedGrids = workedGrids
                this.roamedGrids = roamedGrids
                this.showFirstCallLabels = showFirstCallLabels
                this.firstCallsByGrid = firstCallsByGrid
                this.markedGrids = markedGrids
                this.ownGrid = ownGridOf(stationPosition)
                applyTheme(mapIsLightUi == true)
            }
        }
        // Satellite-related layers are hidden in grid mode; the grid overlay
        // itself is controlled by its own enabled flag.
        for (index in OVERLAY_STATION..OVERLAY_MOON) {
            mapView.overlays.getOrNull(index)?.isEnabled = !gridMode
        }
        // Entering grid mode: center on the station's current grid square,
        // keeping the current zoom level unchanged. post{} is essential: on the
        // first composition this runs before MapView's first layout, and a
        // setCenter issued pre-layout is discarded when the view lays out —
        // the map then keeps its default center forever.
        if (gridMode && centerOnStation) {
            val pos = stationPosition ?: return
            val lat = pos.latitude
            val lon = pos.longitude
            // Maidenhead field indices: field 0 spans -90..-80° lat and
            // -180..-160° lon, so the field-center must be offset by those
            // bases — omitting them maps e.g. OL62's field center to
            // 115°N/290°E (an ocean a hemisphere away) instead of 25°N/110°E.
            val fieldLat = ((lat + 90.0) / 10.0).toInt().coerceIn(0, 17)
            val fieldLon = ((lon + 180.0) / 20.0).toInt().coerceIn(0, 17)
            val centerLat = fieldLat * 10.0 + 5.0 - 90.0
            val centerLon = fieldLon * 20.0 + 10.0 - 180.0
            mapView.post { mapView.controller.setCenter(GeoPoint(centerLat, centerLon)) }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/** The station's 4-char Maidenhead gridsquare, e.g. "OL62", or null without a position. */
private fun ownGridOf(stationPosition: GeoPos?): String? {
    val pos = stationPosition ?: return null
    val lat = pos.latitude
    val lon = pos.longitude
    val fieldLat = ((lat + 90.0) / 10.0).toInt().coerceIn(0, 17)
    val fieldLon = ((lon + 180.0) / 20.0).toInt().coerceIn(0, 17)
    val subLat = ((lat + 90.0) % 10.0).toInt()
    val subLon = ((lon + 180.0) % 20.0 / 2.0).toInt()
    return "${'A' + fieldLon}${'A' + fieldLat}$subLon$subLat"
}

/** The 4-char Maidenhead gridsquare containing the given point, or null outside the grid. */
private fun gridOfPoint(latitude: Double, longitude: Double): String? {
    if (latitude < -90.0 || latitude >= 90.0) return null
    val lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    val fieldLat = ((latitude + 90.0) / 10.0).toInt().coerceIn(0, 17)
    val fieldLon = ((lon + 180.0) / 20.0).toInt().coerceIn(0, 17)
    val subLat = ((latitude + 90.0) % 10.0).toInt()
    val subLon = ((lon + 180.0) % 20.0 / 2.0).toInt()
    return "${'A' + fieldLon}${'A' + fieldLat}$subLon$subLat"
}

private fun setStationPosition(stationPos: GeoPos, mapView: MapView, isLightUi: Boolean) {
    try {
        val overlay = mapView.overlays[OVERLAY_STATION]
        val stationPoint = GeoPoint(stationPos.latitude, stationPos.longitude)
        if (overlay is Marker) {
            overlay.position = stationPoint
            // Icon tint is baked into the drawable, so re-tint after a theme change.
            if ((overlay.relatedObject as? Boolean) != isLightUi) {
                overlay.icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_position)?.apply {
                    setTint(mapAccentColor)
                }
                overlay.relatedObject = isLightUi
            }
        } else {
            mapView.overlays[OVERLAY_STATION] = Marker(mapView).apply {
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_position)?.apply {
                    setTint(mapAccentColor)
                }
                position = stationPoint
                relatedObject = isLightUi
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/**
 * Opposite-station marker used only by the matched radar's dedicated map.
 * Its coordinates come from the centre of the entered Maidenhead square.
 */
private const val OTHER_STATION_MARKER_STYLE = "look4sat-other-station-blue"

private fun setOtherStationPosition(position: GeoPos?, grid: String?, mapView: MapView) {
    try {
        val existing = mapView.overlays.getOrNull(OVERLAY_OTHER_STATION)
        if (position == null) {
            if (existing is Marker) mapView.overlays[OVERLAY_OTHER_STATION] = FolderOverlay()
            return
        }
        val marker = (existing as? Marker) ?: Marker(mapView).also {
            it.setInfoWindow(null)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_position)?.apply {
                setTint("#1976D2".toColorInt())
            }
            it.relatedObject = OTHER_STATION_MARKER_STYLE
            mapView.overlays[OVERLAY_OTHER_STATION] = it
        }
        marker.position = GeoPoint(position.latitude, position.longitude)
        marker.title = grid.orEmpty()
        if (marker.relatedObject != OTHER_STATION_MARKER_STYLE) {
            marker.icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_position)?.apply {
                setTint("#1976D2".toColorInt())
            }
            marker.relatedObject = OTHER_STATION_MARKER_STYLE
        }
    } catch (e: Exception) {
        println(e)
    }
}

/** Above this many satellites inside the viewport labels are dropped in favor of a single
 * shared dot icon. Per-satellite label bitmaps cost ~90KB each, so drawing thousands of them
 * exhausts memory and stalls the UI thread — and overlapping labels are unreadable anyway. */
private const val LABEL_LIMIT = 128

/** Degrees of space around the viewport so markers don't pop in at the edges */
private const val VIEWPORT_MARGIN = 8.0

/** Debounce for viewport-driven marker refreshes, in milliseconds */
private const val MAP_LISTENER_DELAY = 128L

/** Shared dot icon used when too many satellites are visible to label them */
private var dotIcon: Drawable? = null

/** Scratch list reused every frame to avoid per-tick allocation */
private val visibleSats = ArrayList<Pair<OrbitalObject, GeoPos>>()

/** Last emitted positions, replayed on scroll/zoom so culled markers appear without waiting for a tick */
private var lastPositions: Map<OrbitalObject, GeoPos>? = null
private var lastAction: ((OrbitalObject) -> Unit)? = null

/**
 * True while the time slider is under the finger. The marker refresh that the map listener runs
 * after a scroll ([MAP_LISTENER_DELAY] ms after the camera moved) re-applies the last known
 * positions; during a drag that is up to one slider step stale, which used to pull the satellite
 * and its footprint back a step between two scrub updates and read as shaking.
 */
@Volatile
private var scrubInProgress = false

/** Last [MapState.scrubSerial] the camera was centred for (see the map update pass). */
private var lastFollowedScrubSerial = 0L

/** Pool of reusable Marker objects keyed by satellite name, to avoid re-creation every frame */
private val markerPool = HashMap<String, Marker>()
private var lastMapView: MapView? = null

private fun setPositions(
    posMap: Map<OrbitalObject, GeoPos>,
    mapView: MapView,
    action: (OrbitalObject) -> Unit
) {
    try {
        // While the slider is under the finger only the scrubbed satellite moves and the tick
        // loop is paused, so the rest of the layer is already correct: patch the markers that
        // actually changed instead of clearing and re-adding the whole folder. `items` is a
        // CopyOnWriteArrayList, so every re-add copies the backing array — that per-step churn
        // (thousands of element copies at 25 steps a second) was the stutter.
        if (scrubInProgress) {
            val previous = lastPositions
            val moved = posMap.filter { (item, pos) -> previous?.get(item) != pos }
            // Only safe while every moved satellite already has a live marker; otherwise fall
            // through and let the full path build the layer.
            if (moved.keys.all { markerPool[it.data.name] != null }) {
                moved.forEach { (item, pos) ->
                    val current = markerPool.getValue(item.data.name).position
                    if (current != null) {
                        current.latitude = pos.latitude
                        current.longitude = pos.longitude
                    } else {
                        markerPool.getValue(item.data.name).position = GeoPoint(pos.latitude, pos.longitude)
                    }
                }
                lastPositions = posMap
                lastAction = action
                return
            }
        }
        lastPositions = posMap
        lastAction = action
        // Clear caches when the MapView instance changes (e.g. config change)
        if (lastMapView !== mapView) {
            lastMapView = mapView
            markerPool.clear()
            iconCache.evictAll()
            dotIcon = null
            footprintOverlay = null
            footprintArcs.clear()
            footprintLines.clear()
            mapView.addMapListener(DelayedMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?) = refreshPositions(mapView)
                override fun onZoom(event: ZoomEvent?) = refreshPositions(mapView)
            }, MAP_LISTENER_DELAY))
        }
        // Reuse the existing FolderOverlay — creating a new one and replacing it
        // causes osmdroid to detach shared Marker objects, making them invisible.
        val folder = mapView.overlays[OVERLAY_POSITIONS] as? FolderOverlay ?: FolderOverlay().also {
            mapView.overlays[OVERLAY_POSITIONS] = it
        }
        folder.items.clear()

        // Cull satellites outside the viewport: only meaningful once zoomed in, but that is
        // exactly when marker labels are shown and drawing is most expensive.
        visibleSats.clear()
        if (mapView.width > 0 && mapView.height > 0) {
            val box = mapView.boundingBox
            val latNorth = box.latNorth + VIEWPORT_MARGIN
            val latSouth = box.latSouth - VIEWPORT_MARGIN
            val lonWest = box.lonWest - VIEWPORT_MARGIN
            val lonEast = box.lonEast + VIEWPORT_MARGIN
            val wrapsDateLine = box.lonWest > box.lonEast
            for ((satellite, geoPos) in posMap) {
                val lat = geoPos.latitude
                if (lat !in latSouth..latNorth) continue
                val lon = geoPos.longitude
                val isLonVisible = if (wrapsDateLine) lon >= lonWest || lon <= lonEast
                else lon in lonWest..lonEast
                if (isLonVisible) visibleSats.add(satellite to geoPos)
            }
        } else {
            for (entry in posMap) visibleSats.add(entry.key to entry.value)
        }

        val showLabels = visibleSats.size <= LABEL_LIMIT
        val activeNames = HashSet<String>(visibleSats.size)
        for ((satellite, geoPos) in visibleSats) {
            val name = satellite.data.name
            activeNames.add(name)
            val marker = markerPool.getOrPut(name) {
                Marker(mapView).apply {
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // Resolve the satellite via relatedObject so the listener is allocated
                    // once per marker instead of once per satellite per tick
                    setOnMarkerClickListener { clicked, _ ->
                        (clicked.relatedObject as? OrbitalObject)?.let(action)
                        true
                    }
                }
            }
            marker.relatedObject = satellite
            val icon = if (showLabels) getCachedTextIcon(name, mapView) else getDotIcon(mapView)
            if (marker.icon !== icon) marker.icon = icon
            // Update position in-place — reuse existing GeoPoint if available
            val pos = marker.position
            if (pos != null) {
                pos.latitude = geoPos.latitude
                pos.longitude = geoPos.longitude
            } else {
                marker.position = GeoPoint(geoPos.latitude, geoPos.longitude)
            }
            folder.add(marker)
        }
        // Evict markers that are no longer tracked or no longer visible
        markerPool.keys.retainAll(activeNames)
        visibleSats.clear()
    } catch (e: Exception) {
        println(e)
    }
}

/** Re-applies the last known positions against the new viewport after a pan or zoom */
private fun refreshPositions(mapView: MapView): Boolean {
    // Grid mode disables the satellite layer — don't re-populate markers off-screen
    if (mapView.overlays.getOrNull(OVERLAY_POSITIONS)?.isEnabled == false) return true
    // A drag re-applies the selected satellite every slider step; a listener refresh in between
    // would put the last (stale) map back and make the marker jump.
    if (scrubInProgress) return true
    val posMap = lastPositions ?: return false
    val action = lastAction ?: return false
    setPositions(posMap, mapView, action)
    mapView.invalidate()
    return true
}

private fun getDotIcon(mapView: MapView): Drawable = dotIcon ?: run {
    val size = 20
    val bitmap = createBitmap(size, size)
    Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f - 2f, textPaint)
    bitmap.toDrawable(mapView.context.resources).also { dotIcon = it }
}

private fun getCachedTextIcon(name: String, mapView: MapView): Drawable {
    iconCache[name]?.let { return it }
    val labelRect = Rect()
    textPaint.getTextBounds(name, 0, name.length, labelRect)
    val iconSize = 10f
    val width = labelRect.width() + iconSize * 2f
    val height = textPaint.textSize * 3f + iconSize * 2f
    val bitmap = createBitmap(width.toInt(), height.toInt())
    Canvas(bitmap).run {
        drawCircle(width / 2f, height / 2f, iconSize, textPaint)
        drawText(name, iconSize / 2f, height - iconSize, textPaint)
    }
    val drawable = bitmap.toDrawable(mapView.context.resources)
    iconCache.put(name, drawable)
    return drawable
}

private var lastTrack: List<List<GeoPos>>? = null
private var lastTrackView: MapView? = null

/** Ground-track overlay, reused across updates: replacing the folder every time would detach the
 *  polylines osmdroid is drawing (same reason the marker layer reuses its folder). */
private var trackOverlay: FolderOverlay? = null
private val trackLines = ArrayList<Polyline>(4)

private fun setSatelliteTrack(satTrack: List<List<GeoPos>>, mapView: MapView) {
    if (satTrack === lastTrack && lastTrackView === mapView) return
    lastTrack = satTrack
    lastTrackView = mapView
    try {
        val overlay = trackOverlay?.takeIf { mapView.overlays.getOrNull(OVERLAY_TRACK) === it }
            ?: FolderOverlay().also {
                trackOverlay = it
                mapView.overlays[OVERLAY_TRACK] = it
            }
        overlay.items.clear()
        var lineIndex = 0
        satTrack.forEach { track ->
            // osmdroid keeps longitudes as given but draws overlays in ONE world copy, so a lap
            // that runs up to ±180 loses everything on the far side of the meridian (the line stops
            // at it and nothing appears on the other side). Award boundaries solve the same thing
            // with a whole-turn offset towards the view centre; here the offsets are baked in
            // instead, so the track stays correct while panning without needing the view centre.
            for (turn in trackTurnsFor(track)) {
                val offset = turn * 360.0
                val line = trackLines.getOrNull(lineIndex) ?: Polyline().apply {
                    outlinePaint.set(trackPaint)
                    trackLines.add(this)
                }
                lineIndex++
                line.setPoints(
                    if (offset == 0.0) track.map { GeoPoint(it.latitude, it.longitude) }
                    else track.map { GeoPoint(it.latitude, it.longitude + offset) }
                )
                overlay.add(line)
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/**
 * Whole 360° turns to draw [track] at: just the real position, or one copy either side when it runs
 * close to the antimeridian. Only near ±180 does the far half need a copy in the neighbouring world
 * — everywhere else one copy is exactly where it belongs, and the extra points would be dead weight.
 */
private fun trackTurnsFor(track: List<GeoPos>): IntArray {
    val nearDateLine = track.any { abs(it.longitude) > TRACK_DATELINE_MARGIN }
    return if (nearDateLine) intArrayOf(-1, 0, 1) else intArrayOf(0)
}

/** [trackTurnsFor] for osmdroid points (the footprint arcs). */
private fun turnsForPoints(points: List<GeoPoint>): IntArray {
    val nearDateLine = points.any { abs(it.longitude) > TRACK_DATELINE_MARGIN }
    return if (nearDateLine) intArrayOf(-1, 0, 1) else intArrayOf(0)
}

/** Within this many degrees of ±180 a line needs its neighbouring world copies drawn too. */
private const val TRACK_DATELINE_MARGIN = 176.0

/** Footprint circle, split into arcs that each stay on one side of the date line */
private var footprintOverlay: FolderOverlay? = null
private val footprintArcs = ArrayList<MutableList<GeoPoint>>(4)
private val footprintLines = ArrayList<Polyline>(4)

private fun setFootprint(orbitalPos: OrbitalPos, mapView: MapView) {
    try {
        val rangeCircle = orbitalPos.getRangeCircle()
        // One polyline per arc, but the overlay itself is reused: replacing it detaches objects
        // osmdroid is still drawing (same reason as the marker folder).
        val overlay = footprintOverlay?.takeIf { mapView.overlays.getOrNull(OVERLAY_FOOTPRINT) === it }
            ?: FolderOverlay().also {
                footprintOverlay = it
                mapView.overlays[OVERLAY_FOOTPRINT] = it
            }
        overlay.items.clear()
        // The circle's longitudes are unwrapped around the subpoint, so they run past ±180 and a
        // single polyline would step across the date line — osmdroid draws that step as a line
        // straight over to the other side of the map (the circle looks broken and wrapped). Clip
        // each point and start a new arc wherever the clipped value jumps the meridian.
        var arcCount = 0
        var arc: MutableList<GeoPoint>? = null
        var previousLon = 0.0
        var hasPrevious = false
        for (point in rangeCircle) {
            val lon = clipLon(point.longitude)
            if (arc == null || (hasPrevious && abs(lon - previousLon) > 180.0)) {
                arc = footprintArcs.getOrNull(arcCount)
                    ?: ArrayList<GeoPoint>(rangeCircle.size).also { footprintArcs.add(it) }
                arc.clear()
                arcCount++
            }
            arc.add(GeoPoint(point.latitude, lon))
            previousLon = lon
            hasPrevious = true
        }
        var lineIndex = 0
        for (index in 0 until arcCount) {
            val arc = footprintArcs[index]
            // Same world-copy reasoning as the track: near the meridian the arc that continues on
            // the other side is drawn outside the visible copy unless it also exists one world over.
            for (turn in turnsForPoints(arc)) {
                while (footprintLines.size <= lineIndex) {
                    footprintLines.add(Polyline().apply { outlinePaint.set(footprintPaint) })
                }
                val line = footprintLines[lineIndex]
                lineIndex++
                line.setPoints(
                    if (turn == 0) arc
                    else arc.map { GeoPoint(it.latitude, it.longitude + turn * 360.0) }
                )
                overlay.add(line)
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/**
 * Update the NightOverlay with the current sub-solar position.
 * The overlay is created once and kept in OVERLAY_TERMINATOR; only its
 * sunLatDeg/sunLonDeg fields are updated each tick so osmdroid redraws it.
 */
private fun setTerminator(sunLatDeg: Double, sunLonDeg: Double, mapView: MapView) {
    try {
        val overlay = mapView.overlays[OVERLAY_TERMINATOR]
        if (overlay is MapNightOverlay) {
            overlay.sunLatDeg = sunLatDeg
            overlay.sunLonDeg = sunLonDeg
        } else {
            mapView.overlays[OVERLAY_TERMINATOR] = MapNightOverlay().apply {
                this.sunLatDeg = sunLatDeg
                this.sunLonDeg = sunLonDeg
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/** Build the 48dp sun/moon marker icon, tinted with the theme-dependent paint. */
private fun buildMarkerIcon(mapView: MapView, resId: Int, paint: Paint): Drawable {
    val iconSize = 48
    val bmp = createBitmap(iconSize, iconSize)
    ContextCompat.getDrawable(mapView.context, resId)?.apply {
        setBounds(0, 0, iconSize, iconSize)
        colorFilter = paint.colorFilter
        draw(Canvas(bmp))
    }
    return bmp.toDrawable(mapView.context.resources)
}

/** Place an ic_sun icon marker at the sub-solar point. */
private fun setSubSolarPoint(sunLatDeg: Double, sunLonDeg: Double, mapView: MapView, isLightUi: Boolean) {
    try {
        val overlay = mapView.overlays[OVERLAY_SUN]
        val sunPos = GeoPoint(sunLatDeg, sunLonDeg)
        if (overlay is Marker) {
            overlay.position = sunPos
            // The icon bitmap bakes in the theme tint, so rebuild it after a theme change.
            if ((overlay.relatedObject as? Boolean) != isLightUi) {
                overlay.icon = buildMarkerIcon(mapView, R.drawable.ic_sun, sunIconPaint)
                overlay.relatedObject = isLightUi
            }
        } else {
            mapView.overlays[OVERLAY_SUN] = Marker(mapView).apply {
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = buildMarkerIcon(mapView, R.drawable.ic_sun, sunIconPaint)
                position = sunPos
                relatedObject = isLightUi
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/** Place an ic_moon icon marker at the sub-lunar point. */
private fun setMoonPosition(moonLatDeg: Double, moonLonDeg: Double, mapView: MapView, isLightUi: Boolean) {
    try {
        val overlay = mapView.overlays[OVERLAY_MOON]
        val moonPos = GeoPoint(moonLatDeg, moonLonDeg)
        if (overlay is Marker) {
            overlay.position = moonPos
            if ((overlay.relatedObject as? Boolean) != isLightUi) {
                overlay.icon = buildMarkerIcon(mapView, R.drawable.ic_moon, moonIconPaint)
                overlay.relatedObject = isLightUi
            }
        } else {
            mapView.overlays[OVERLAY_MOON] = Marker(mapView).apply {
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = buildMarkerIcon(mapView, R.drawable.ic_moon, moonIconPaint)
                position = moonPos
                relatedObject = isLightUi
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}
// endregion

// region MapView lifecycle
@Composable
private fun rememberMapViewWithLifecycle(
    mapFilterViewModel: MapFilterViewModel,
    restoreViewport: Boolean
): MapView {
    val context = LocalContext.current
    val isVertical = isVerticalLayout()
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            setUseDataConnection(false)
            // Vertical map repetition wraps points below the viewport up to the
            // top edge (osmdroid Y-wrap). Antarctica's full-circle ring then
            // draws a full-height artifact line whenever its X range crosses
            // the screen at low zoom. The map must never repeat vertically.
            setVerticalMapRepetitionEnabled(false)
            setTileSource(offlineTileSource)
            minZoomLevel = getMinZoom(resources.displayMetrics.heightPixels, isVertical)
            maxZoomLevel = OFFLINE_MAX_ZOOM
            // Restore the viewport the user left on the previous visit, but only
            // in grid mode: satellite mode intentionally keeps its original
            // behavior (default center, then follows the selected satellite).
            val savedLat = mapFilterViewModel.mapCenterLat
            val savedLon = mapFilterViewModel.mapCenterLon
            val savedZoom = mapFilterViewModel.mapZoom
            if (restoreViewport && savedLat != null && savedLon != null && savedZoom != null) {
                controller.setCenter(GeoPoint(savedLat, savedLon))
                controller.setZoom(savedZoom)
            } else {
                controller.setCenter(GeoPoint(48.8575, 6.3514))
                controller.setZoom(minZoomLevel + 2)
            }
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.loadingBackgroundColor = Color.TRANSPARENT
            overlayManager.tilesOverlay.loadingLineColor = Color.TRANSPARENT
            // The dark-map tile filter (or no filter in the light theme) is applied per theme from
            // the AndroidView update block, so it also follows a theme switch at runtime.
            setScrollableAreaLimitLatitude(maxLat, minLat, 0)
            overlays.addAll(Array(OVERLAY_COUNT) { FolderOverlay() })
            overlays[OVERLAY_GRID] = MaidenheadGridOverlay().apply { applyTheme(mapIsLightUi == true) }
            overlays[OVERLAY_ELEVATION_CONTOURS] = ElevationContourOverlay()
            overlays[OVERLAY_STATION_ELEVATION] = StationElevationOverlay()
            currentMapView = this
        }
    }
    val lifecycleObserver = rememberMapViewLifecycleObserver(mapView)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycle.removeObserver(lifecycleObserver) }
    }
    // The overlay caches below are file-level (shared across MapView instances), so they must be
    // released with the MapView or they keep the Activity and its bitmaps alive after disposal.
    // (The viewport save lives in MapDestination, where the grid/satellite mode is known.)
    DisposableEffect(mapView) {
        onDispose {
            clearMapCaches()
            if (currentMapView === mapView) currentMapView = null
        }
    }
    return mapView
}

private fun clearMapCaches() {
    markerPool.clear()
    iconCache.evictAll()
    dotIcon = null
    lastPositions = null
    lastAction = null
    lastMapView = null
    lastFollowedScrubSerial = 0L
    lastTrack = null
    lastTrackView = null
    trackOverlay = null
    trackLines.clear()
    footprintOverlay = null
    footprintArcs.clear()
    footprintLines.clear()
    configuredTileMapView = null
    configuredTileSource = null
}

@Composable
private fun rememberMapViewLifecycleObserver(mapView: MapView) = remember(mapView) {
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            else -> {}
        }
    }
}

private fun createColorFilter(): ColorMatrixColorFilter {
    val grayScale = ColorMatrix().apply { setSaturation(0f) }
    val negative = ColorMatrix(
        floatArrayOf(-1f, 0f, 0f, 0f, 260f, 0f, -1f, 0f, 0f, 260f, 0f, 0f, -1f, 0f, 260f, 0f, 0f, 0f, 1f, 0f)
    )
    negative.preConcat(grayScale)
    return ColorMatrixColorFilter(negative)
}

private fun getMinZoom(screenHeight: Int, isVertical: Boolean): Double {
    if (!isVertical) return 3.5
    return MapView.getTileSystem().getLatitudeZoom(maxLat, minLat, screenHeight)
}
// endregion
