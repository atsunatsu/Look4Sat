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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.rtbishop.look4sat.core.presentation.GridFinderDestination as GridFinderRoute
import com.rtbishop.look4sat.core.presentation.LocalElevationThresholds
import com.rtbishop.look4sat.core.presentation.LogbookDestination
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.LoTWUploadDestination as LoTWUploadRoute
import com.rtbishop.look4sat.core.presentation.RadarDestination as RadarRoute
import com.rtbishop.look4sat.core.presentation.Screen
import com.rtbishop.look4sat.core.presentation.hasEnoughHeight
import com.rtbishop.look4sat.core.presentation.hasEnoughWidth
import com.rtbishop.look4sat.feature.ft4.Ft4ShellDestination
import com.rtbishop.look4sat.feature.logbook.LogbookScreenDestination
import com.rtbishop.look4sat.feature.gridfinder.GridFinderDestination
import com.rtbishop.look4sat.feature.map.MapDestination
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
    val deeplinkResolver = DeeplinkResolver()
    var openMapRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(deeplink) {
        deeplink?.let { rootBackStack.add(deeplinkResolver.resolve(it)) }
    }
    val navigateBack: () -> Unit = { rootBackStack.removeLastOrNull() }
    val navigateToRadar: () -> Unit = { rootBackStack.add(RadarRoute) }
    val navigateToFt4: () -> Unit = { rootBackStack.add(Screen.Ft4) }
    val navigateToLogbook: () -> Unit = { rootBackStack.add(LogbookDestination) }
    val navigateToMap: () -> Unit = {
        while (rootBackStack.size > 1) rootBackStack.removeAt(rootBackStack.size - 1)
        openMapRequest += 1
    }
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    val navigateToLoTWStation: () -> Unit = { rootBackStack.add(LoTWUploadRoute) }
    // Incoming screen slides in from the right, outgoing drifts left at 1/3 speed (API35+ style)
    val pushTransition = slideInHorizontally(tween(300)) { it } togetherWith
        slideOutHorizontally(tween(300)) { -it / 3 }
    // Reverse: outgoing slides out to the right, incoming drifts in from the left
    val popTransition = slideInHorizontally(tween(300)) { -it / 3 } togetherWith
        slideOutHorizontally(tween(300)) { it }
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
                        navigateToFt4 = navigateToFt4,
                        navigateToLoTWStation = navigateToLoTWStation,
                        openMapRequest = openMapRequest,
                        onOpenMapRequestHandled = { openMapRequest = 0 }
                    )
                }
                entry<RadarRoute> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RadarDestination(
                            navigateUp = navigateBack,
                            navigateToMap = navigateToMap,
                            onOpenLoTWStation = navigateToLoTWStation
                        )
                    }
                }
                entry<GridFinderRoute> {
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
                entry<LoTWUploadRoute> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LoTWUploadDestination(navigateUp = navigateBack)
                    }
                }
            entry<Screen.Ft4> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Ft4ShellDestination(navigateUp = navigateBack, navigateToLogbook = navigateToLogbook)
                }
            }
            entry<LogbookDestination> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LogbookScreenDestination(navigateUp = navigateBack, navigateToMap = navigateToMap)
                }
            }
        }
        )
    }
}

@Composable
fun MainScreen(
    navigateToRadar: () -> Unit = {},
    navigateToFt4: () -> Unit = {},
    navigateToLoTWStation: () -> Unit = {},
    openMapRequest: Int = 0,
    onOpenMapRequestHandled: () -> Unit = {}
) {
    val backStack = rememberNavBackStack(Screen.Passes)
    val currentKey = backStack.lastOrNull()
    var showMoreSheet by rememberSaveable { mutableStateOf(false) }
    val navigateBack: () -> Unit = { backStack.removeLastOrNull() }
    val navigateToScreen: (Screen) -> Unit = { screen ->
        while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
        if (screen !is Screen.Passes) backStack.add(screen)
    }
    val navigateToMap: () -> Unit = { navigateToScreen(Screen.Map) }
    LaunchedEffect(openMapRequest) {
        if (openMapRequest > 0) {
            navigateToMap()
            onOpenMapRequestHandled()
        }
    }
    val fadeTransition = fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(350))
    val navItems = listOf(
        Screen.Satellites,
        Screen.Passes,
        Screen.Logbook,
        Screen.Ft4,
        Screen.More,
        Screen.Settings
    )
    val moreItems = listOf(
        Screen.GridFinder,
        Screen.Map,
        Screen.Mutual,
        Screen.AMSAT
    )

    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val trackingState by container.radioTrackingService.state.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    // Activity-scoped so the mutual query results survive navigation to Radar and back
    val mutualViewModel: MutualViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MutualViewModel.factory(container)
    )
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
        if (showMoreSheet) {
            MoreDestinationsSheet(
                destinations = moreItems,
                currentScreen = currentKey as? Screen,
                onDismiss = { showMoreSheet = false },
                onDestinationSelected = { screen ->
                    showMoreSheet = false
                    if (currentKey != screen) navigateToScreen(screen)
                }
            )
        }
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                navItems.forEach { screen ->
                    val isSelected = when (currentKey) {
                        is Screen.Satellites -> screen is Screen.Satellites
                        is Screen.Passes -> screen is Screen.Passes
                        is Screen.Ft4 -> screen is Screen.Ft4
                        is Screen.Logbook -> screen is Screen.Logbook
                        is Screen.Settings -> screen is Screen.Settings
                        is Screen.Map,
                        is Screen.GridFinder,
                        is Screen.AMSAT,
                        is Screen.Mutual -> screen is Screen.More
                        else -> false
                    } || (screen is Screen.More && showMoreSheet)
                    item(
                        icon = { Icon(painterResource(screen.iconResId), stringResource(screen.titleResId)) },
                        label = { Text(stringResource(screen.titleResId)) },
                        selected = isSelected,
                        onClick = {
                            if (screen is Screen.More) {
                                showMoreSheet = true
                                return@item
                            }
                            if (isSelected) return@item
                            if (screen is Screen.Ft4) {
                                navigateToFt4()
                                return@item
                            }
                            navigateToScreen(screen)
                        }
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
                            SatellitesDestination(
                                navigateUp = navigateBack,
                                navigateToAmSat = { backStack.add(Screen.AMSAT) }
                            )
                        }
                        entry<Screen.Passes> {
                            PassesDestination(
                                navigateToRadar = { catNum, aosTime ->
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                },
                                navigateToMap = navigateToMap
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
                        entry<Screen.GridFinder> {
                            GridFinderDestination(
                                navigateUp = navigateBack,
                                onOpenLoTWStation = navigateToLoTWStation
                            )
                        }
                        entry<Screen.Mutual> {
                            MutualScreen(
                                viewModel = mutualViewModel,
                                navigateToRadar = { catNum, aosTime, pass ->
                                    container.setMutualPassData(pass ?: MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                }
                            )
                        }
                        entry<Screen.AMSAT> { SatStatusDestination() }
                        entry<Screen.Logbook> {
                            LogbookScreenDestination(navigateUp = navigateBack, navigateToMap = navigateToMap)
                        }
                        entry<Screen.Settings> {
                            SettingsDestination()
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
                            text = stringResource(
                                R.string.main_tracking,
                                trackingState.currentPass?.name.orEmpty()
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        val txOk = if (trackingState.txConnected) stringResource(R.string.ft4_tx_label) else ""
                        val rxOk = if (trackingState.rxConnected) stringResource(R.string.ft4_rx_label) else ""
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MoreDestinationsSheet(
    destinations: List<Screen>,
    currentScreen: Screen?,
    onDismiss: () -> Unit,
    onDestinationSelected: (Screen) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.nav_more),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        destinations.forEach { screen ->
            val selected = currentScreen == screen
            ListItem(
                headlineContent = { Text(stringResource(screen.titleResId)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(screen.iconResId),
                        contentDescription = null
                    )
                },
                trailingContent = {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_done),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDestinationSelected(screen) }
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
    }
}
