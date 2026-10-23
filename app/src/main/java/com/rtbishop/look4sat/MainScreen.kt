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
package com.rtbishop.look4sat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.presentation.DeeplinkResolver
import com.rtbishop.look4sat.core.presentation.ElevationThresholds
import com.rtbishop.look4sat.core.presentation.GridFinderDestination
import com.rtbishop.look4sat.core.presentation.LocalElevationThresholds
import com.rtbishop.look4sat.core.presentation.LoTWUploadDestination
import com.rtbishop.look4sat.core.presentation.MatchGridPickerDestination
import com.rtbishop.look4sat.core.presentation.RadarDestination
import com.rtbishop.look4sat.core.presentation.RadarPassMapDestination
import com.rtbishop.look4sat.core.presentation.RadarMatchDestination
import com.rtbishop.look4sat.core.presentation.Screen
import com.rtbishop.look4sat.core.presentation.hasEnoughHeight
import com.rtbishop.look4sat.core.presentation.hasEnoughWidth
import com.rtbishop.look4sat.feature.gridfinder.GridFinderDestination
import com.rtbishop.look4sat.feature.map.MapDestination
import com.rtbishop.look4sat.feature.map.RadarPassMapScreenDestination
import com.rtbishop.look4sat.feature.map.MapFilterViewModel
import com.rtbishop.look4sat.feature.mutual.MutualScreen
import com.rtbishop.look4sat.feature.mutual.MutualViewModel
import com.rtbishop.look4sat.feature.passes.PassesDestination
import com.rtbishop.look4sat.feature.radar.RadarDestination
import com.rtbishop.look4sat.feature.satellites.SatellitesDestination
import com.rtbishop.look4sat.feature.settings.SettingsDestination
import com.rtbishop.look4sat.feature.settings.LoTWUploadDestination
import com.rtbishop.look4sat.feature.status.SatStatusDestination

@Composable
fun NavRoot(deeplink: String? = null) {
    val rootBackStack = rememberNavBackStack(Screen.Passes)
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val mutualViewModel: MutualViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MutualViewModel.factory(container)
    )
    val deeplinkResolver = DeeplinkResolver()
    LaunchedEffect(deeplink) {
        deeplink?.let { rootBackStack.add(deeplinkResolver.resolve(it)) }
    }
    val navigateBack: () -> Unit = { rootBackStack.removeLastOrNull() }
    // From the dedicated pass map, switch to an existing main tab and clear the
    // radar/map overlays. The inner MainScreen keeps its own independent back stack.
    var requestedMainTab by remember { androidx.compose.runtime.mutableStateOf<Screen?>(null) }
    val navigateToRadar: () -> Unit = { rootBackStack.add(RadarDestination) }
    // Keep a single dedicated-map NavEntry and MapView while switching passes.
    // Replacing the entry used to overlap two osmdroid views during transitions;
    // their shared rendering caches could detach each other's satellite overlays.
    var currentRadarMapPass by remember { androidx.compose.runtime.mutableStateOf<RadarPassMapDestination?>(null) }
    val navigateToRadarPassMap: (Int, Long, Long) -> Unit = { catNum, aos, los ->
        if (los > aos) {
            val next = RadarPassMapDestination(catNum, aos, los)
            currentRadarMapPass = next
            rootBackStack.add(next)
        }
    }
    // Only the pass data changes. System Back still returns to the original radar.
    val switchRadarPassMap: (RadarPassMapDestination) -> Unit = { next ->
        if (rootBackStack.lastOrNull() is RadarPassMapDestination) {
            currentRadarMapPass = next
        }
    }
    val navigateToGridFinder: () -> Unit = { rootBackStack.add(GridFinderDestination) }
    val navigateToLoTWStation: () -> Unit = { rootBackStack.add(LoTWUploadDestination) }
    val startRadarMatch: (String, (Boolean) -> Unit) -> Unit = { grid, onComplete ->
        mutualViewModel.prepareRadarMatchFromGrid(grid) { selection ->
            if (selection == null) {
                onComplete(false)
            } else {
                container.setMutualPassData(selection.mutualPassData)
                container.satelliteRepo.selectPass(selection.catNum, selection.aosTime)
                // Keep the originating radar underneath, but replace an already matched
                // radar so repeated matches never create a long stack of radar pages.
                if (rootBackStack.lastOrNull() is RadarMatchDestination) {
                    rootBackStack.removeLastOrNull()
                }
                rootBackStack.add(
                    RadarMatchDestination(
                        stationBGrid = selection.stationBGrid,
                        catNum = selection.catNum,
                        aosTime = selection.aosTime,
                        sessionId = System.currentTimeMillis()
                    )
                )
                onComplete(true)
            }
        }
    }
    // Incoming screen slides in from the right, outgoing drifts left at 1/3 speed (API35+ style)
    val pushTransition = slideInHorizontally(tween(300)) { it } togetherWith
        slideOutHorizontally(tween(300)) { -it / 3 }
    // Reverse: outgoing slides out to the right, incoming drifts in from the left
    val popTransition = slideInHorizontally(tween(300)) { -it / 3 } togetherWith
        slideOutHorizontally(tween(300)) { it }
    // Elevation color thresholds must be provided at the root level so that BOTH
    // the tab content (MainScreen) and the root-level RadarDestination see the
    // user's custom thresholds. RadarDestination is a sibling entry of MainScreen
    // in this NavDisplay, so a provider inside MainScreen never reaches it.
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalElevationThresholds provides ElevationThresholds(
            low = otherSettings.lowElevation,
            high = otherSettings.highElevation
        )
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = rootBackStack,
            onBack = navigateBack,
            transitionSpec = { pushTransition },
            popTransitionSpec = { popTransition },
            predictivePopTransitionSpec = { popTransition },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<Screen.Passes> {
                    MainScreen(
                        navigateToRadar = navigateToRadar,
                        navigateToGridFinder = navigateToGridFinder,
                        mutualViewModel = mutualViewModel,
                        requestedTab = requestedMainTab,
                        onRequestedTabHandled = { requestedMainTab = null }
                    )
                }
                entry<RadarDestination> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RadarDestination(
                            navigateUp = navigateBack,
                            onOpenLoTWStation = navigateToLoTWStation,
                            onOpenPassMap = navigateToRadarPassMap,
                            onStartMatch = startRadarMatch
                        )
                    }
                }
                entry<RadarMatchDestination> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RadarDestination(
                            navigateUp = navigateBack,
                            onOpenLoTWStation = navigateToLoTWStation,
                            onOpenPassMap = navigateToRadarPassMap,
                            onStartMatch = startRadarMatch
                        )
                    }
                }
                entry<RadarPassMapDestination> { pass ->
                    // Dedicated map keeps the same bottom navigation as regular
                    // map/screens. It selects Map but every tab (including Map)
                    // returns to that main tab instead of stacking more pages.
                    Look4SatNavigationScaffold(
                        selectedScreen = Screen.Map,
                        onSelectScreen = { screen ->
                            requestedMainTab = screen
                            while (rootBackStack.size > 1) rootBackStack.removeLastOrNull()
                        }
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            RadarPassMapScreenDestination(
                                pass = currentRadarMapPass ?: pass,
                                onSwitchPass = switchRadarPassMap
                            )
                        }
                    }
                }
                entry<GridFinderDestination> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        GridFinderDestination(
                            navigateUp = navigateBack,
                            onOpenLoTWStation = navigateToLoTWStation
                        )
                    }
                }
                entry<LoTWUploadDestination> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LoTWUploadDestination(navigateUp = navigateBack)
                    }
                }
            }
        )
    }
}

/**
 * The same navigation bar/rail for the normal tabs and dedicated pass map.
 * Do not use a separate lookalike: both destinations must stay consistent.
 */
@Composable
private fun Look4SatNavigationScaffold(
    selectedScreen: Screen?,
    onSelectScreen: (Screen) -> Unit,
    content: @Composable () -> Unit
) {
    val navItems = listOf(
        Screen.Satellites, Screen.Passes, Screen.AMSAT,
        Screen.Map, Screen.Mutual, Screen.Settings
    )
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            navItems.forEach { screen ->
                item(
                    icon = { Icon(painterResource(screen.iconResId), stringResource(screen.titleResId)) },
                    label = { Text(stringResource(screen.titleResId)) },
                    selected = selectedScreen == screen,
                    onClick = { onSelectScreen(screen) }
                )
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        layoutType = when {
            !hasEnoughHeight() && hasEnoughWidth() -> NavigationSuiteType.NavigationRail
            !hasEnoughWidth() -> NavigationSuiteType.ShortNavigationBarCompact
            else -> NavigationSuiteType.ShortNavigationBarMedium
        }
    ) { content() }
}

@Composable
fun MainScreen(
    navigateToRadar: () -> Unit = {},
    navigateToGridFinder: () -> Unit = {},
    mutualViewModel: MutualViewModel,
    requestedTab: Screen? = null,
    onRequestedTabHandled: () -> Unit = {}
) {
    val backStack = rememberNavBackStack(Screen.Passes)
    val currentKey = backStack.lastOrNull()
    val navigateBack: () -> Unit = {
        val leavingGridPicker = backStack.lastOrNull() is MatchGridPickerDestination
        backStack.removeLastOrNull()
        if (leavingGridPicker) mutualViewModel.requestListScrollRestore()
    }
    val fadeTransition = fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(350))
    // Root-level dedicated map toolbar tabs target this nested main navigation.
    LaunchedEffect(requestedTab) {
        requestedTab?.let { tab ->
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            if (tab !is Screen.Passes) backStack.add(tab)
            onRequestedTabHandled()
        }
    }

    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val trackingState by container.radioTrackingService.state.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    // Activity-scoped MutualViewModel is shared with root-level Radar destinations.
    // Activity-scoped so the map's award filter survives page switches;
    // resets to VUCC only on cold start (fresh process).
    val mapFilterViewModel: MapFilterViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MapFilterViewModel.factory()
    )

    // Map grid-QSO dialog "Match" button: pre-fill the match page for that
    // grid and run the query BEFORE navigating, so the page's first frame
    // already contains the results (time-range card at the top, pass curves
    // right below — no scroll flicker). Navigation happens once the query
    // finishes (see the collector below).
    val mutualState by mutualViewModel.uiState.collectAsStateWithLifecycle()
    val matchCalculating = mutualState.pendingNavigation && mutualState.isCalculating

    LaunchedEffect(mutualViewModel) {
        mutualViewModel.uiState.collect { state ->
            if (state.pendingNavigation && !state.isCalculating) {
                mutualViewModel.consumePendingNavigation()
                // Push Mutual on top of the Map entry instead of replacing the
                // stack (bottom-nav style): the system back gesture then pops
                // back to the map page, which is the page the user came from.
                if (backStack.lastOrNull() !is Screen.Mutual) {
                    backStack.add(Screen.Mutual)
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalElevationThresholds provides ElevationThresholds(
            low = otherSettings.lowElevation,
            high = otherSettings.highElevation
        )
    ) {
        Look4SatNavigationScaffold(
            selectedScreen = currentKey as? Screen,
            onSelectScreen = { screen ->
                if (currentKey != screen) {
                    val leavingGridPicker = backStack.lastOrNull() is MatchGridPickerDestination
                    while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                    if (screen !is Screen.Passes) backStack.add(screen)
                    if (leavingGridPicker) mutualViewModel.requestListScrollRestore()
                }
            }
        ) {
            Column {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.weight(1f),
                    onBack = navigateBack,
                    transitionSpec = { fadeTransition },
                    popTransitionSpec = { fadeTransition },
                    predictivePopTransitionSpec = { fadeTransition },
                    entryDecorators = listOf(
                        // Required for saving Compose state per entry
                        rememberSaveableStateHolderNavEntryDecorator(),
                        // Required for ViewModel scoping per entry
                        rememberViewModelStoreNavEntryDecorator()
                    ),
                    entryProvider = entryProvider {
                        entry<Screen.Satellites> {
                            SatellitesDestination(navigateUp = navigateBack)
                        }
                        entry<Screen.Passes> {
                            PassesDestination(
                                navigateToRadar = { catNum, aosTime ->
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                }
                            )
                        }
                        entry<Screen.Map> {
                            MapDestination(
                                mapFilterViewModel = mapFilterViewModel,
                                matchCalculating = matchCalculating,
                                onMatchGrid = { grid ->
                                    // Grid-QSO dialog "Match" button: pre-fill
                                    // the match page for that grid and start the
                                    // query. Navigation to the match page is
                                    // handled by the uiState collector above,
                                    // once the query finishes.
                                    mutualViewModel.prefillMatchFromGrid(grid)
                                }
                            )
                        }
                        entry<MatchGridPickerDestination> { picker ->
                            MapDestination(
                                mapFilterViewModel = mapFilterViewModel,
                                gridPickerMode = true,
                                onPickGrid = { grid ->
                                    if (picker.stationA) mutualViewModel.onStationAGrid(grid)
                                    else mutualViewModel.onStationBGrid(grid)
                                    navigateBack()
                                },
                                onNavigateBack = navigateBack
                            )
                        }
                        entry<Screen.Mutual> {
                            MutualScreen(
                                viewModel = mutualViewModel,
                                navigateToRadar = { catNum, aosTime, pass ->
                                    container.setMutualPassData(pass ?: MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                },
                                onPickGrid = { stationA ->
                                    backStack.add(MatchGridPickerDestination(stationA))
                                }
                            )
                        }
                        entry<Screen.AMSAT> { SatStatusDestination() }
                        entry<Screen.Settings> {
                            SettingsDestination(onOpenGridFinder = navigateToGridFinder)
                        }
                    }
                )
                // Radio tracking status banner
                if (trackingState.isActive) {
                    val infiniteTransition = rememberInfiniteTransition(label = "trackingPulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 0.4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "pulseAlpha"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                val pass = trackingState.currentPass
                                if (pass != null) {
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(pass.catNum, pass.aosTime)
                                    navigateToRadar()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50).copy(alpha = alpha))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tracking: ${trackingState.currentPass?.name ?: ""}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        val txOk = if (trackingState.txConnected) "TX" else ""
                        val rxOk = if (trackingState.rxConnected) "RX" else ""
                        Text(
                            text = listOf(txOk, rxOk).filter { it.isNotBlank() }.joinToString("/"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}
