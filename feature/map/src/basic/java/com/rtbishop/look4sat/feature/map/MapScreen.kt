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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color as ComposeColor
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
import com.rtbishop.look4sat.core.domain.model.stationGridSetKey
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.IconCard
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
private const val OVERLAY_COUNT = 9

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
    matchCalculating: Boolean = false
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mapView = rememberMapViewWithLifecycle(mapFilterViewModel, restoreViewport = uiState.isGridMode)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Save the viewport when leaving the page, but only in grid mode: the
    // satellite mode intentionally keeps its original behavior (default center,
    // follow the selected satellite), so a satellite-mode exit must not
    // overwrite the last grid-mode viewport.
    DisposableEffect(mapView, uiState.isGridMode) {
        onDispose {
            if (uiState.isGridMode) {
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
        uiState = uiState,
        onAction = viewModel::onAction,
        mapView = mapView,
        mapFilterViewModel = mapFilterViewModel,
        onMatchGrid = onMatchGrid,
        matchCalculating = matchCalculating
    )
}

@Composable
private fun MapScreen(
    uiState: MapState,
    onAction: (MapAction) -> Unit,
    mapView: MapView,
    mapFilterViewModel: MapFilterViewModel,
    onMatchGrid: (String) -> Unit,
    matchCalculating: Boolean
) {
    val rotateMod = Modifier.rotate(180f)
    val timeString = uiState.mapData?.aosTime ?: "00:00:00"
    val isTimeAos = uiState.mapData?.isTimeAos ?: true
    val usesTianditu = tiandituLayers(uiState.mapSource) != null && uiState.tiandituKey.isNotBlank()
    val copyrightResId = if (usesTianditu) R.string.map_copyright_tianditu else R.string.map_copyright_osm

    // Tapped worked grid -> centered QSO dialog. Local UI state: the map is the
    // only consumer and it resets when leaving the page.
    var selectedGrid by remember { mutableStateOf<String?>(null) }
    // Tapped award region (DXCC/WAPC/WAJA/WAZ/WAS) -> its QSO detail dialog,
    // paired with the award type it was tapped under. Local UI state like the
    // grid selection above: the map is the only consumer.
    var selectedRegion by remember { mutableStateOf<Pair<AwardType, AwardRegion>?>(null) }
    // Selected award filter. Lives in an Activity-scoped ViewModel so it
    // survives page switches; defaults to VUCC only once per process (cold start).
    var selectedAward by mapFilterViewModel.selectedAward
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
    // Selected 台址 for VUCC counting; defaults to the group with the most
    // worked grids. Null = "All" (every 台址 combined). Empty when the store
    // carries no per-QSO myGrid data (requires a LoTW resync) — the selector
    // is hidden then and VUCC falls back to the global count.
    var selectedStationId by remember(stationGroups) {
        mutableStateOf(stationGroups.maxByOrNull { it.workedCount }?.id)
    }
    val awardProgress: List<AwardProgress> = remember(uiState.workedGridQsos, stationGroups, selectedStationId) {
        val base = AwardCalculator.calculate(uiState.workedGridQsos)
        val worked = stationGroups.firstOrNull { it.id == selectedStationId }?.worked
        if (worked == null) base
        else base.map { p ->
            if (p.type == AwardType.VUCC) p.copy(workedKeys = worked, count = worked.size) else p
        }
    }
    // Worked grids drawn on the map: filtered by the selected 台址 (null = all).
    // Drives both the green fills and the tap listener below, so switching the
    // selector changes which cells are green.
    val workedGrids = remember(uiState.workedGrids, stationGroups, selectedStationId) {
        if (selectedStationId == null) uiState.workedGrids
        else stationGroups.firstOrNull { it.id == selectedStationId }?.worked ?: emptySet()
    }
    // First callsign worked in each grid (earliest QSO by time), used by the
    // "首通呼号" label mode. Scoped to the selected 台址 like the fills: with a
    // specific operated grid chosen, the label must show ITS first contact, not
    // another 台址's (user decision 2026-10-05); "All" = global first call.
    val firstCallsByGrid: Map<String, String> = remember(uiState.workedGridQsos, selectedStationId) {
        uiState.workedGridQsos.firstCallsByGrid(selectedStationId)
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
    DisposableEffect(isGridMode, workedGrids) {
        val receiver = object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (!isGridMode || p == null) return false
                // Award views (DXCC/WAPC/WAJA/WAZ/WAS): the tap resolves to the
                // boundary region under the finger and opens its QSO detail
                // dialog. No zoom gate here — unlike the grid path below — so
                // tiny islands stay reachable while zoomed out (Tianditu tiles
                // have no zoom cap, so "just zoom in" is not an answer).
                val awardMode = selectedAward?.takeIf { it != AwardType.VUCC }
                if (awardMode != null) {
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
                selectedGrid = grid
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
    LaunchedEffect(uiState.track) {
        // In grid mode the map is centered on the local grid square; following
        // the satellite subpoint here would override that centering on every
        // track update, so skip the tracking animation entirely.
        if (uiState.isGridMode) return@LaunchedEffect
        val firstPos = uiState.track?.firstOrNull()?.firstOrNull() ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(firstPos.latitude, firstPos.longitude))
    }
    LaunchedEffect(uiState.mapSource, uiState.tiandituKey, uiState.isLightUi) {
        configureTileSources(mapView, uiState.mapSource, uiState.tiandituKey, uiState.isLightUi)
    }
    Column(modifier = Modifier.layoutPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val isVertical = isVerticalLayout()
        if (isGridMode) {
            // Grid mode: replace the satellite-selection top bar with the
            // award filter chips (All / DXCC / VUCC / WAPC / WAJA / WAZ / WAS).
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
                    val awardMode = selectedAward?.takeIf { it != AwardType.VUCC }
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
                            showFirstCallLabels = uiState.showFirstCallLabels && selectedAward == AwardType.VUCC,
                            firstCallsByGrid = firstCallsByGrid,
                            markedGrids = uiState.markedGrids
                        )
                        if (!shouldCenter || uiState.stationPosition != null) {
                            prevGridMode = uiState.isGridMode
                        }
                    }
                    if (!uiState.isGridMode) {
                        uiState.stationPosition?.let { setStationPosition(it, view, uiState.isLightUi) }
                        uiState.track?.let { setSatelliteTrack(it, view) }
                        uiState.footprint?.let { setFootprint(it, view) }
                        uiState.positions?.let { setPositions(it, view) { item -> onAction(MapAction.SelectItem(item)) } }
                        setTerminator(uiState.sunLatDeg, uiState.sunLonDeg, view)
                        setSubSolarPoint(uiState.sunLatDeg, uiState.sunLonDeg, view, uiState.isLightUi)
                        setMoonPosition(uiState.moonLatDeg, uiState.moonLonDeg, view, uiState.isLightUi)
                    }
                    view.invalidate()
                }
                uiState.mapData?.let { mapData ->
                    if (!uiState.isGridMode) {
                        if (isVertical) MapDataCard(mapData, copyrightResId)
                        else MapDataCards(mapData, copyrightResId)
                    }
                }
                // Top-left: 台址 selector for VUCC counting (grid mode only).
                // Shown whenever per-grid QSO data exists; "All" (null) is available.
                if (uiState.isGridMode && selectedAward == AwardType.VUCC && stationGroups.isNotEmpty()) {
                    VuccGridSelector(
                        stations = stationGroups,
                        allCount = uiState.workedGrids.size,
                        selected = selectedStationId,
                        onSelect = { selectedStationId = it },
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
                    GridModeToggle(
                        isGridMode = uiState.isGridMode,
                        onToggle = { onAction(MapAction.ToggleGridMode(it)) }
                    )
                    // 首通呼号开关仅 VUCC 视图显示(与 VuccGridSelector 同条件).
                    if (uiState.isGridMode && selectedAward == AwardType.VUCC) {
                        FirstCallToggle(
                            checked = uiState.showFirstCallLabels,
                            onToggle = { onAction(MapAction.ToggleFirstCallLabels(it)) }
                        )
                    }
                }
            }
        }
    }
    // Centered dialog listing the tapped worked grid's confirmed satellite QSOs.
    // Dismissed by tapping outside (no explicit close button). Unworked grids
    // open the same dialog with an empty list plus a mark-a-station reminder.
    selectedGrid?.let { grid ->
        WorkedGridQsoDialog(
            grid = grid,
            // Scope to the selected 台址 (null = All): a specific station location
            // must not show other locations' QSOs (bug report 2026-10-05).
            qsos = uiState.workedGridQsos[grid].orEmpty()
                .scopedToStation(selectedStationId)
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
        val regionQsos = remember(uiState.workedGridQsos, type, region) {
            AwardCalculator.regionQsos(uiState.workedGridQsos, type, region.code)
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
 * award region (DXCC/WAPC/WAJA/WAZ/WAS). Read-only variant of the worked-grid
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

// region Map data composables
@Composable
private fun MapDataCard(data: MapData, copyrightResId: Int) {
    val textColor = MaterialTheme.colorScheme.primary
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = stringResource(copyrightResId), fontSize = 14.sp)
        Card(colors = cardColors) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                MapDataRow(
                    stringResource(R.string.map_azimuth, data.azimuth) to textColor,
                    stringResource(R.string.map_elevation, data.elevation) to textColor
                )
                MapDataRow(
                    stringResource(R.string.map_altitude, data.altitude) to null,
                    stringResource(R.string.map_distance, data.range) to null
                )
                MapDataRow(
                    stringResource(R.string.map_latitude, data.osmPos.latitude) to textColor,
                    stringResource(R.string.map_longitude, data.osmPos.longitude) to textColor
                )
                MapDataRow(
                    stringResource(R.string.map_qth, data.qthLoc) to null,
                    stringResource(R.string.map_phase, data.phase) to null
                )
            }
        }
    }
}

@Composable
private fun MapDataRow(
    left: Pair<String, androidx.compose.ui.graphics.Color?>,
    right: Pair<String, androidx.compose.ui.graphics.Color?>
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        if (left.second != null) Text(text = left.first, color = left.second!!)
        else Text(text = left.first)
        if (right.second != null) Text(text = right.first, color = right.second!!)
        else Text(text = right.first)
    }
}

@Composable
private fun MapDataCards(data: MapData, copyrightResId: Int) {
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val paddingMod = Modifier
        .padding(horizontal = 8.dp, vertical = 4.dp)
        .width(160.dp)
    val textColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxSize()) {
        Card(colors = cardColors, modifier = Modifier.align(Alignment.TopStart)) {
            Column(horizontalAlignment = Alignment.Start, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_azimuth, data.azimuth), color = textColor)
                Text(text = stringResource(R.string.map_elevation, data.elevation))
            }
        }
        // Top-end card is offset down to make room for the grid-mode toggle pill.
        Card(colors = cardColors, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp)) {
            Column(horizontalAlignment = Alignment.End, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_altitude, data.altitude), color = textColor)
                Text(text = stringResource(R.string.map_distance, data.range))
            }
        }
        Card(colors = cardColors, modifier = Modifier.align(Alignment.BottomStart)) {
            Column(horizontalAlignment = Alignment.Start, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_phase, data.phase), color = textColor)
                Text(text = stringResource(R.string.map_qth, data.qthLoc))
            }
        }
        Text(
            text = stringResource(copyrightResId),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        Card(colors = cardColors, modifier = Modifier.align(Alignment.BottomEnd)) {
            Column(horizontalAlignment = Alignment.End, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_latitude, data.osmPos.latitude), color = textColor)
                Text(text = stringResource(R.string.map_longitude, data.osmPos.longitude))
            }
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
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // Most-worked 台址 first; selection is made on tap. "All" sits last.
    val sorted = remember(stations) { stations.sortedByDescending { it.workedCount } }
    val selectedLabel = stations.firstOrNull { it.id == selected }?.label
    Surface(
        color = ComposeColor.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        // Width = widest child (header or list item), never the full screen:
        // the expanded rows use fillMaxWidth, which would otherwise stretch
        // this box to the whole map width.
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = if (selectedLabel == null) "All ($allCount)"
                    else "$selectedLabel (${stations.firstOrNull { it.id == selected }?.workedCount ?: 0})",
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
            if (expanded) {
                androidx.compose.material3.HorizontalDivider(color = ComposeColor.White.copy(alpha = 0.2f))
                sorted.forEach { station ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(station.id)
                                expanded = false
                            }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${station.label} (${station.workedCount})",
                            color = if (station.id == selected) MaterialTheme.colorScheme.primary else ComposeColor.White,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                // "All" always listed last: shows every worked grid combined.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(null)
                            expanded = false
                        }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "All ($allCount)",
                        color = if (selected == null) MaterialTheme.colorScheme.primary else ComposeColor.White,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
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

/** Pool of reusable Marker objects keyed by satellite name, to avoid re-creation every frame */
private val markerPool = HashMap<String, Marker>()
private var lastMapView: MapView? = null

private fun setPositions(
    posMap: Map<OrbitalObject, GeoPos>,
    mapView: MapView,
    action: (OrbitalObject) -> Unit
) {
    try {
        lastPositions = posMap
        lastAction = action
        // Clear caches when the MapView instance changes (e.g. config change)
        if (lastMapView !== mapView) {
            lastMapView = mapView
            markerPool.clear()
            iconCache.evictAll()
            dotIcon = null
            footprintPolyline = null
            footprintPoints = null
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

private fun setSatelliteTrack(satTrack: List<List<GeoPos>>, mapView: MapView) {
    val trackOverlay = FolderOverlay()
    try {
        satTrack.forEach { track ->
            Polyline().apply {
                setPoints(track.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.set(trackPaint)
                trackOverlay.add(this)
            }
        }
        mapView.overlays[OVERLAY_TRACK] = trackOverlay
    } catch (e: Exception) {
        println(e)
    }
}

/** Reusable footprint Polyline — created once, points updated in-place each frame */
private var footprintPolyline: Polyline? = null
private var footprintPoints: ArrayList<GeoPoint>? = null

private fun setFootprint(orbitalPos: OrbitalPos, mapView: MapView) {
    try {
        val rangeCircle = orbitalPos.getRangeCircle()
        var pts = footprintPoints
        if (pts == null || pts.size != rangeCircle.size) {
            pts = ArrayList(rangeCircle.size)
            for (gp in rangeCircle) pts.add(GeoPoint(gp.latitude, gp.longitude))
            footprintPoints = pts
        } else {
            for (i in rangeCircle.indices) {
                pts[i].latitude = rangeCircle[i].latitude
                pts[i].longitude = rangeCircle[i].longitude
            }
        }
        val polyline = footprintPolyline ?: Polyline().apply {
            outlinePaint.set(footprintPaint)
            footprintPolyline = this
        }
        polyline.setPoints(pts)
        mapView.overlays[OVERLAY_FOOTPRINT] = polyline
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
