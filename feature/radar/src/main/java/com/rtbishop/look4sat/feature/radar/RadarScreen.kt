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
package com.rtbishop.look4sat.feature.radar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.utility.toDegrees
import com.rtbishop.look4sat.core.presentation.EmptyListCard
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.NextPassRow
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.TimerRow
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.formatFrequency
import com.rtbishop.look4sat.core.presentation.getDefaultPass
import com.rtbishop.look4sat.core.presentation.layoutPadding
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI

private enum class RadarPage(val title: String) {
    Transceivers("Transceivers"),
    Calculator("Calculator"),
    Log("Log"),
    Sstv("SSTV")
}

/**
 * Always-visible translucent pager tab strip drawn over the radar in small windows.
 */
private val COMPACT_TAB_STRIP_HEIGHT = 48.dp

/**
 * Smallest pager block worth reserving below the radar before falling back to the
 * overlaid tab strip + panel. Below this the pager would be unusable anyway.
 */
private val COMPACT_MIN_PAGER_HEIGHT = 96.dp

/**
 * Vertical chrome the classic layout spends outside the radar: two 48dp top bar rows
 * plus three 6dp row gaps (bar/bar/radar/pager).
 */
private val CLASSIC_VERTICAL_CHROME = 114.dp

/**
 * The radar circle is 0.95 x min(cardWidth, cardHeight), so a card that is wider than it
 * is tall always shows a small circle with empty bands on the sides. Full screen the radar
 * card ends up ~0.88 x cardWidth tall (circle ~84% of the page width). Once the classic
 * 1:1 split on a vertical window would give the radar less than this share of its own
 * width, the fill layout takes over so the circle keeps the full-screen width proportion
 * instead of shrinking with the window height.
 */
private const val FILL_RADAR_SPLIT_RATIO = 0.75f

/**
 * Smallest radar side (as a share of the page width) worth keeping a reserved pager block for.
 * 0.88 x 0.95 = ~0.84 = the circle's share of the page width full screen, so the pager is only
 * preserved while reserving it costs the circle nothing against the full-screen proportion.
 */
private const val MIN_RADAR_SHARE_WITH_PAGER = 0.88f

/** Vertical gap kept between the stacked cards. */
private val ROW_GAP = 6.dp

/**
 * True when a vertical window is too short for the classic 1:1 split to keep the radar circle
 * at its full-screen share of the page width. The split gives the radar
 * `(height - chrome) / 2`, so a short window caps the circle by that height instead of by the
 * page width.
 */
internal fun useFillRadarLayout(isVertical: Boolean, maxWidth: Dp, maxHeight: Dp): Boolean =
    isVertical && (maxHeight - CLASSIC_VERTICAL_CHROME) / 2 < maxWidth * FILL_RADAR_SPLIT_RATIO

/**
 * Geometry of the fill layout: the square the radar keeps for itself (largest square that fits,
 * so the circle spans the page width like it does full screen) and the space left for the pager.
 * When that space is too small to be usable the pager is overlaid on the plot instead.
 */
internal data class RadarFillSizes(
    val radarSide: Dp,
    val pagerSpace: Dp,
    val pagerOverlaid: Boolean
)

internal fun radarFillSizes(maxWidth: Dp, maxHeight: Dp): RadarFillSizes {
    // Preferred: keep a usable pager block under the radar square. That is only worth it while
    // the circle still reaches its full-screen share of the page width.
    val radarWithPager = minOf(maxWidth, maxHeight - COMPACT_MIN_PAGER_HEIGHT - ROW_GAP)
    if (radarWithPager >= maxWidth * MIN_RADAR_SHARE_WITH_PAGER) {
        return RadarFillSizes(
            radarSide = radarWithPager,
            pagerSpace = maxHeight - radarWithPager - ROW_GAP,
            pagerOverlaid = false
        )
    }
    // Window too short for both: the radar takes the largest square it can and the pager is
    // folded into the overlaid tab strip.
    val radarSide = minOf(maxWidth, maxHeight)
    val pagerSpace = maxHeight - radarSide - ROW_GAP
    return RadarFillSizes(
        radarSide = radarSide,
        pagerSpace = pagerSpace,
        pagerOverlaid = pagerSpace < COMPACT_MIN_PAGER_HEIGHT
    )
}

@Composable
fun RadarDestination(navigateUp: () -> Unit, onOpenLoTWStation: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: RadarViewModel = viewModel(factory = RadarViewModel.factory(container))
    val logViewModel: LogViewModel = viewModel(factory = LogViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mutualData by container.mutualPassData.collectAsStateWithLifecycle()
    val navigateUpAndClearMutual = {
        if (container.mutualPassData.value.endTime > 0L) {
            container.setMutualPassData(MutualPassData())
        }
        navigateUp()
    }
    LaunchedEffect(mutualData.endTime) {
        if (mutualData.endTime <= 0L) return@LaunchedEffect
        // Auto-return only while the mutual pass is actually in progress. An
        // already-finished pass must NOT bounce the radar page back instantly
        // (that made the pass-card radar shortcut look broken: tapping it while
        // the computed pass had ended returned to Mutual immediately).
        var remainingMs = mutualData.endTime - System.currentTimeMillis()
        if (remainingMs <= 0L) return@LaunchedEffect
        while (remainingMs > 0L) {
            delay(remainingMs.coerceAtMost(1000L))
            remainingMs = mutualData.endTime - System.currentTimeMillis()
        }
        navigateUpAndClearMutual()
    }
    // Sync actual permission state on every recomposition so it survives screen re-entry
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(hasPermission) {
        viewModel.onAction(RadarAction.SstvPermissionResult(hasPermission))
        viewModel.onAction(RadarAction.CwPermissionResult(hasPermission))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onAction(RadarAction.SstvPermissionResult(granted))
        viewModel.onAction(RadarAction.CwPermissionResult(granted))
    }
    // Grid-check "fix it" jump from the log page: hand the affected records' grids to the
    // station-location page as its prefill (mirrors the Grid Finder's hand-off).
    val openLoTWStationForGridFix: (List<String>) -> Unit = { grids ->
        if (grids.isNotEmpty()) container.setPendingLoTWStationGrid(grids.joinToString(","))
        onOpenLoTWStation()
    }
    RadarScreen(uiState, viewModel::onAction, navigateUpAndClearMutual, mutualData, logViewModel, requestMicPermission = {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }, onFixGrid = openLoTWStationForGridFix)
}

@Composable
private fun RadarScreen(
    uiState: RadarState,
    onAction: (RadarAction) -> Unit,
    navigateUp: () -> Unit,
    mutualData: MutualPassData,
    logViewModel: LogViewModel,
    requestMicPermission: () -> Unit,
    onFixGrid: (List<String>) -> Unit
) {
    val upcomingPass = uiState.currentPass ?: getDefaultPass()
    // 日程功能: 把当前过境写入系统日历(原仓库的 addToCalendar, ic_calendar 按钮)
    val addToCalendar: () -> Unit = {
        uiState.currentPass?.let { onAction(RadarAction.AddToCalendar(it.name, it.aosTime, it.losTime)) }
    }
    // Station-B overlay: full track line (only where B's elevation > 0) + live position dot
    // at the current moment, same display mode as the local station.
    val trackB = remember(mutualData.trackSamples) {
        mutualData.trackSamples
            .filter { it.elevationB > 0.0 }
            .map {
                OrbitalPos(
                    azimuth = it.azimuthB * PI / 180.0,
                    elevation = it.elevationB * PI / 180.0,
                    time = it.time
                )
            }
    }
    val timeNow = System.currentTimeMillis()
    val trackBPosition = mutualData.trackSamples
        .filter { it.time <= timeNow }
        .lastOrNull()
        ?.let {
            OrbitalPos(
                azimuth = it.azimuthB * PI / 180.0,
                elevation = it.elevationB * PI / 180.0,
                time = it.time
            )
        }
    BoxWithConstraints(
        modifier = Modifier
            .layoutPadding()
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            .keepScreenOn()
    ) {
        // Measure the ACTUAL window constraints instead of the window size
        // class: currentWindowAdaptiveInfo() often reports the full-screen
        // size in split-screen / multi-window, so the compact branch never
        // triggered there. maxHeight/maxWidth are the real window bounds.
        val isVertical = maxWidth < 600.dp
        // The classic vertical layout gives the radar a 1:1 share of the height while also
        // paying for two top bar rows. In split-screen / small windows that leaves a card that
        // is wider than it is tall, so the circle is capped by the height and stops filling the
        // page width. Below the full-screen proportion the fill layout takes over: one top bar
        // row, the radar keeps the largest square it can, the pager yields (reserved block when
        // the window is tall enough, overlaid tab strip + panel when it is not).
        val fillRadar = useFillRadarLayout(isVertical, maxWidth, maxHeight)
        var useLargeRadar by remember(fillRadar) { mutableStateOf(fillRadar) }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isVertical) {
                // Normal vertical top area: the timer card keeps its own row and the pass card
                // gets a full-width row of its own. Never merge them into one row — both cards
                // carry weight(1f), so side by side they split the width into two halves.
                TopBar {
                    IconCard(action = navigateUp, resId = R.drawable.ic_back)
                    TimerRow(timeString = uiState.currentTime, isTimeAos = uiState.isTimeAos)
                    IconCard(action = addToCalendar, resId = R.drawable.ic_calendar)
                }
                TopBar {
                    NextPassRow(pass = upcomingPass, isUtc = uiState.isUtc)
                    if (fillRadar) {
                        RadarSizeToggle(
                            showingLargeRadar = useLargeRadar,
                            onToggle = { useLargeRadar = !useLargeRadar }
                        )
                    }
                }
            } else {
                TopBar {
                    IconCard(action = navigateUp, resId = R.drawable.ic_back)
                    TimerRow(timeString = uiState.currentTime, isTimeAos = uiState.isTimeAos)
                    NextPassRow(pass = upcomingPass, modifier = Modifier.weight(1f), isUtc = uiState.isUtc)
                    IconCard(action = addToCalendar, resId = R.drawable.ic_calendar)
                }
            }
            if (isVertical) {
                if (fillRadar && useLargeRadar) {
                    RadarFillArea(
                        uiState = uiState,
                        trackB = trackB,
                        trackBPosition = trackBPosition,
                        onAction = onAction,
                        logViewModel = logViewModel,
                        requestMicPermission = requestMicPermission,
                        onFixGrid = onFixGrid,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    RadarCard(uiState, trackB, trackBPosition, Modifier.weight(1f))
                    PagerCard(uiState, onAction, logViewModel, requestMicPermission, Modifier.weight(1f), onFixGrid = onFixGrid)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadarCard(uiState, trackB, trackBPosition, Modifier.weight(1f))
                    PagerCard(uiState, onAction, logViewModel, requestMicPermission, Modifier.weight(1f), onFixGrid = onFixGrid)
                }
            }
        }
    }
}

@Composable
private fun RadarSizeToggle(showingLargeRadar: Boolean, onToggle: () -> Unit) {
    val label = stringResource(
        if (showingLargeRadar) R.string.radar_use_small else R.string.radar_use_large
    )
    ElevatedCard(
        modifier = Modifier.size(48.dp),
        onClick = onToggle
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    if (showingLargeRadar) R.string.radar_size_small_short
                    else R.string.radar_size_large_short
                ),
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Radar area for split-screen / small windows. The radar takes the largest square the area
 * can hold so the circle spans the same share of the width it does full screen, and the
 * pager yields: a real block below the square when there is room, otherwise a translucent
 * tab strip overlaid on the plot that opens the pager panel on top of it.
 */
@Composable
private fun RadarFillArea(
    uiState: RadarState,
    trackB: List<OrbitalPos>,
    trackBPosition: OrbitalPos?,
    onAction: (RadarAction) -> Unit,
    logViewModel: LogViewModel,
    requestMicPermission: () -> Unit,
    onFixGrid: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sizes = radarFillSizes(maxWidth, maxHeight)
        // Hoisted out of the nested Box scope: the BoxWithConstraints receiver is not
        // implicitly reachable inside it.
        val pagerPanelHeight = maxHeight * 0.72f
        if (!sizes.pagerOverlaid) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP)
            ) {
                RadarCard(uiState, trackB, trackBPosition, Modifier.height(sizes.radarSide).fillMaxWidth())
                PagerCard(uiState, onAction, logViewModel, requestMicPermission, Modifier.height(sizes.pagerSpace), onFixGrid = onFixGrid)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                RadarCard(uiState, trackB, trackBPosition, Modifier.fillMaxSize(), overlayStrip = true)
                CompactPagerOverlay(
                    uiState = uiState,
                    onAction = onAction,
                    logViewModel = logViewModel,
                    requestMicPermission = requestMicPermission,
                    onFixGrid = onFixGrid,
                    panelHeight = pagerPanelHeight,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * Collapsed pager for short windows: a translucent row of tab names over the bottom of the
 * radar plot; picking one opens the full pager panel on top of the plot, collapsible again
 * with the chevron in its tab row.
 */
@Composable
private fun CompactPagerOverlay(
    uiState: RadarState,
    onAction: (RadarAction) -> Unit,
    logViewModel: LogViewModel,
    requestMicPermission: () -> Unit,
    onFixGrid: (List<String>) -> Unit,
    panelHeight: Dp,
    modifier: Modifier = Modifier
) {
    val pages = rememberRadarPages(uiState)
    var openPage by remember { mutableStateOf<RadarPage?>(null) }
    val currentPage = openPage
    if (currentPage == null) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = MaterialTheme.shapes.small,
            modifier = modifier
                .fillMaxWidth()
                .height(COMPACT_TAB_STRIP_HEIGHT)
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                pages.forEach { page ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { openPage = page }
                    ) {
                        Text(
                            text = page.title,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    } else {
        PagerCard(
            uiState = uiState,
            onAction = onAction,
            logViewModel = logViewModel,
            requestMicPermission = requestMicPermission,
            onFixGrid = onFixGrid,
            modifier = modifier
                .fillMaxWidth()
                .height(panelHeight),
            startPage = currentPage,
            onCollapse = { openPage = null }
        )
    }
}

/** Page list of the pager card, shared by the inline pager and the compact overlay panel. */
@Composable
private fun rememberRadarPages(uiState: RadarState): List<RadarPage> {
    val hasCalculatorPage = remember(uiState.transceivers.transmitters) {
        uiState.transceivers.transmitters.any(DopplerFrequencyCalculator::isNamedLinearTransponder)
    }
    return remember(hasCalculatorPage) {
        buildList {
            add(RadarPage.Transceivers)
            if (hasCalculatorPage) add(RadarPage.Calculator)
            add(RadarPage.Log)
            add(RadarPage.Sstv)
        }
    }
}

@Composable
private fun PagerCard(
    uiState: RadarState,
    onAction: (RadarAction) -> Unit,
    logViewModel: LogViewModel,
    requestMicPermission: () -> Unit,
    modifier: Modifier = Modifier,
    startPage: RadarPage? = null,
    onCollapse: (() -> Unit)? = null,
    onFixGrid: (List<String>) -> Unit = {}
) {
    val pages = rememberRadarPages(uiState)
    val pagerState = rememberPagerState(
        initialPage = startPage?.let { pages.indexOf(it) }?.coerceAtLeast(0) ?: 0,
        pageCount = { pages.size }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pages.size) {
        val lastPage = pages.lastIndex
        if (pagerState.currentPage > lastPage) pagerState.scrollToPage(lastPage)
    }

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            val selectedTabIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = if (onCollapse != null) Modifier.weight(1f) else Modifier
                ) {
                    pages.forEachIndexed { index, page ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(text = page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                onCollapse?.let { collapse ->
                    Text(
                        text = "▼",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clickable { collapse() }
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                when (pages[pageIndex]) {
                    RadarPage.Transceivers -> TransceiversPage(
                        transceivers = uiState.transceivers.transmitters,
                        selectedUuid = uiState.transceivers.selectedUuid,
                        radioControl = uiState.radioControl,
                        onAction = onAction
                    )
                    RadarPage.Calculator -> CalculatorPage(
                        transceivers = uiState.transceivers.transmitters,
                        selectedUuid = uiState.transceivers.selectedUuid,
                        orbitalPos = uiState.orbitalPos,
                        cw = uiState.cw,
                        calculatorOffsetKHz = uiState.calculatorOffsetKHz,
                        onAction = onAction,
                        requestMicPermission = requestMicPermission
                    )
                    RadarPage.Log -> LogPage(
                        uiState = uiState,
                        logViewModel = logViewModel,
                        onFixGrid = onFixGrid
                    )
                    RadarPage.Sstv -> SstvPage(
                        sstv = uiState.sstv,
                        dopplerFrequency = uiState.transceivers.selectedFrequency?.let { formatFrequency(it) },
                        onAction = onAction,
                        requestMicPermission = requestMicPermission
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarCard(
    uiState: RadarState,
    trackB: List<OrbitalPos> = emptyList(),
    trackBPosition: OrbitalPos? = null,
    modifier: Modifier = Modifier,
    overlayStrip: Boolean = false
) {
    val satellitePos = uiState.orbitalPos
    val shouldAnimateBorder = satellitePos?.aboveHorizon == true && satellitePos.eclipsed
    // Always call these composables unconditionally — conditional composable calls violate
    // Compose's slot-table stability rules and can crash or produce incorrect state
    val infiniteTransition = rememberInfiniteTransition(label = "eclipsedBorder")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, delayMillis = 25, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eclipsedBorderAlpha"
    )
    val borderModifier = if (shouldAnimateBorder) {
        Modifier.border(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha),
            shape = MaterialTheme.shapes.medium
        )
    } else Modifier
    ElevatedCard(modifier = modifier.then(borderModifier)) {
        Box(contentAlignment = Alignment.Center) {
            val position = uiState.orbitalPos
            if (position == null) {
                ElevatedCard(modifier = Modifier.fillMaxSize()) {
                    EmptyListCard(message = "")
                }
            } else {
                RadarViewCompose(
                    item = position,
                    items = uiState.satTrack,
                    trackB = trackB.takeIf { it.isNotEmpty() },
                    trackBPosition = trackBPosition,
                    azimElev = uiState.orientationValues,
                    shouldShowSweep = uiState.shouldShowSweep,
                    shouldUseCompass = uiState.shouldUseCompass,
                    modifier = Modifier.align(Alignment.Center),
                    sunPosition = uiState.sunPosition,
                    moonPosition = uiState.moonPosition,
                )
                PositionOverlay(position, overlayStrip)
            }
        }
    }
}

@Composable
private fun PositionOverlay(position: OrbitalPos, overlayStrip: Boolean = false) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            // Keep the bottom labels clear of the overlaid pager tab strip in small windows.
            .padding(
                start = 6.dp,
                end = 6.dp,
                top = 4.dp,
                bottom = if (overlayStrip) COMPACT_TAB_STRIP_HEIGHT + 4.dp else 4.dp
            )
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadarLabel(
                value = stringResource(R.string.radar_az_value, position.azimuth.toDegrees()),
                label = stringResource(R.string.radar_az_text),
                alignment = Alignment.Start,
                labelFirst = false
            )
            RadarLabel(
                value = stringResource(R.string.radar_az_value, position.elevation.toDegrees()),
                label = stringResource(R.string.radar_el_text),
                alignment = Alignment.End,
                labelFirst = false
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadarLabel(
                value = stringResource(R.string.radar_alt_value, position.altitude),
                label = stringResource(R.string.radar_alt_text),
                alignment = Alignment.Start,
                labelFirst = true
            )
            RadarLabel(
                value = stringResource(R.string.radar_alt_value, position.distance),
                label = stringResource(R.string.radar_dist_text),
                alignment = Alignment.End,
                labelFirst = true
            )
        }
    }
}

@Composable
private fun RadarLabel(
    value: String,
    label: String,
    alignment: Alignment.Horizontal,
    labelFirst: Boolean
) {
    Column(horizontalAlignment = alignment) {
        if (labelFirst) {
            Text(text = label, fontSize = 15.sp)
            Text(text = value, fontSize = 18.sp)
        } else {
            Text(text = value, fontSize = 18.sp)
            Text(text = label, fontSize = 15.sp)
        }
    }
}
