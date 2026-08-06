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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.rtbishop.look4sat.core.presentation.LocalElevationThresholds
import com.rtbishop.look4sat.core.presentation.RadarDestination
import com.rtbishop.look4sat.core.presentation.Screen
import com.rtbishop.look4sat.core.presentation.hasEnoughHeight
import com.rtbishop.look4sat.core.presentation.hasEnoughWidth
import com.rtbishop.look4sat.feature.cw.CwDecodeScreen
import com.rtbishop.look4sat.feature.map.MapDestination
import com.rtbishop.look4sat.feature.mutual.MutualScreen
import com.rtbishop.look4sat.feature.mutual.MutualViewModel
import com.rtbishop.look4sat.feature.passes.PassesDestination
import com.rtbishop.look4sat.feature.radar.RadarDestination
import com.rtbishop.look4sat.feature.radar.WavelogLogScreen
import com.rtbishop.look4sat.feature.status.SatStatusScreen
import com.rtbishop.look4sat.feature.roaming.RoamingScreen
import com.rtbishop.look4sat.feature.satellites.SatellitesDestination
import com.rtbishop.look4sat.feature.settings.SettingsDestination

@Composable
fun NavRoot(deeplink: String? = null) {
    val rootBackStack = rememberNavBackStack(Screen.Passes)
    val deeplinkResolver = DeeplinkResolver()
    LaunchedEffect(deeplink) {
        deeplink?.let {
            val destination = deeplinkResolver.resolve(it) // rootBackStack.clear()
            rootBackStack.add(destination)
        }
    }
    val navigateBack: () -> Unit = { rootBackStack.removeLastOrNull() }
    val slideInTransition = slideInHorizontally(initialOffsetX = { it }) togetherWith scaleOut(targetScale = 0.9f)
    val slideOutTransition = scaleIn(initialScale = 0.9f) togetherWith slideOutHorizontally(targetOffsetX = { it })
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = rootBackStack,
        onBack = navigateBack,
        transitionSpec = { slideInTransition },
        popTransitionSpec = { slideOutTransition },
        predictivePopTransitionSpec = { slideOutTransition },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(), // Required for saving Compose state per entry
            rememberViewModelStoreNavEntryDecorator() // Required for ViewModel scoping per entry
        ),
        entryProvider = entryProvider {
            entry<Screen.Passes> { MainScreen(navigateToRadar = { rootBackStack.add(RadarDestination) }) }
            entry<RadarDestination> {
                Scaffold { innerPadding ->
                    RadarDestination(navigateUp = navigateBack)
                    innerPadding.calculateTopPadding()
                }
            }
        }
    )
}

@Composable
fun MainScreen(navigateToRadar: () -> Unit = {}) {
    val backStack = rememberNavBackStack(Screen.Passes)
    val currentKey = backStack.lastOrNull()
    val navigateBack: () -> Unit = { backStack.removeLastOrNull() }
    val fadeTransition = fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(350))
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val trackingState by container.radioTrackingService.state.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    // UI 设置: 按 screenOrder 排序(空 = 默认顺序), 再按 hiddenScreens 过滤(设置页固定保留)
    val allNavItems = listOf(Screen.Satellites, Screen.Passes, Screen.Radar, Screen.Mutual, Screen.Roaming, Screen.CwDecode, Screen.WavelogLog, Screen.AmSat, Screen.Map, Screen.Settings)
        .sortedBy { screen ->
            // 未知(新页面如 CwDecode 不在旧持久化顺序里): 用默认顺序位置(漫游↔地图), 再兜底最后
            val idx = otherSettings.screenOrder.indexOf(screen.screenId)
            if (idx != -1) idx
            else com.rtbishop.look4sat.core.presentation.defaultScreenOrder.indexOf(screen.screenId).let {
                if (it != -1) it else Int.MAX_VALUE
            }
        }
        .filter { it.screenId !in otherSettings.hiddenScreens || it is Screen.Settings }
    // 4.5.1 折叠菜单: 主菜单(底部栏 5 槽) + 更多菜单(溢出页面)
    // 老用户迁移: 已持久化的 subMenuOrder 不含新页面 WavelogLog → 追加到子菜单尾部
    val subOrder = (otherSettings.subMenuOrder.ifEmpty { com.rtbishop.look4sat.core.presentation.defaultSubMenuOrder })
        .let { list -> if ("WavelogLog" in list) list else list + "WavelogLog" }
        .let { list -> if ("AMSAT" in list) list else list + "AMSAT" }
    val mainNavItems = remember(allNavItems, subOrder) {
        allNavItems.filter { it.screenId !in subOrder }.take(5)
    }
    val moreNavItems = remember(allNavItems, subOrder) {
        subOrder.mapNotNull { id -> allNavItems.find { it.screenId == id } }
    }
    var moreExpanded by remember { mutableStateOf(false) }
    // 更多菜单打开时拦截返回: 先关菜单
    BackHandler(enabled = moreExpanded) { moreExpanded = false }
    // Activity-scoped so the mutual query results survive navigation to Radar and back
    val mutualViewModel: MutualViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MutualViewModel.factory(container)
    )

    CompositionLocalProvider(
        LocalElevationThresholds provides ElevationThresholds(
            low = otherSettings.lowElevation,
            high = otherSettings.highElevation
        )
    ) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                mainNavItems.forEach { screen ->
                    val isSelected = when (currentKey) {
                        is Screen.Satellites -> screen is Screen.Satellites
                        is Screen.Passes -> screen is Screen.Passes
                        is Screen.Radar -> screen is Screen.Radar
                        is Screen.Mutual -> screen is Screen.Mutual
                        is Screen.CwDecode -> screen is Screen.CwDecode
                        is Screen.WavelogLog -> screen is Screen.WavelogLog
                        is Screen.AmSat -> screen is Screen.AmSat
                        is Screen.Map -> screen is Screen.Map
                        is Screen.Settings -> screen is Screen.Settings
                        else -> false
                    }
                    item(
                        icon = { Icon(painterResource(screen.iconResId), stringResource(screen.titleResId)) },
                        label = { Text(stringResource(screen.titleResId)) },
                        selected = isSelected,
                        onClick = {
                            if (isSelected) return@item
                            moreExpanded = false
                            while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                            if (screen !is Screen.Passes) backStack.add(screen)
                        }
                    )
                }
                // 更多菜单按钮(固定第 6 槽, 子菜单非空才显示)
                if (moreNavItems.isNotEmpty()) {
                    item(
                        icon = {
                            Icon(
                                painterResource(com.rtbishop.look4sat.R.drawable.ic_more),
                                stringResource(com.rtbishop.look4sat.core.presentation.R.string.nav_more)
                            )
                        },
                        label = { Text(stringResource(com.rtbishop.look4sat.core.presentation.R.string.nav_more)) },
                        selected = moreExpanded,
                        onClick = { moreExpanded = !moreExpanded }
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
            Box {
                Column(modifier = Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                PassesDestination { catNum, aosTime ->
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    backStack.add(Screen.Radar)
    //                            navigateToRadar()
                                }
                            }
                            entry<Screen.Radar> {
                                RadarDestination(navigateUp = navigateBack)
                            }
                            entry<Screen.Map> {
                                MapDestination()
                            }
                            entry<Screen.Mutual> {
                                MutualScreen(
                                    viewModel = mutualViewModel,
                                    navigateUp = navigateBack,
                                    navigateToRadar = { catNum, aosTime, pass ->
                                        container.setMutualPassData(pass ?: MutualPassData())
                                        container.satelliteRepo.selectPass(catNum, aosTime)
                                        backStack.add(Screen.Radar)
                                    }
                                )
                            }
                            entry<Screen.Roaming> {
                                RoamingScreen()
                            }
                            entry<Screen.CwDecode> {
                                CwDecodeScreen()
                            }
                            entry<Screen.AmSat> {
                                SatStatusScreen(container = container)
                            }
                            entry<Screen.WavelogLog> {
                                WavelogLogScreen(queue = container.wavelogQueue)
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
                                        backStack.add(Screen.Radar)
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
                                text = stringResource(com.rtbishop.look4sat.core.presentation.R.string.tracking_status, trackingState.currentPass?.name ?: ""),
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
                // 更多菜单弹出面板(覆盖在内容上, 底部栏上方; spring 弹跳)
                AnimatedVisibility(
                    visible = moreExpanded,
                    modifier = Modifier.fillMaxSize(),
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    ) + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    MoreMenuPopup(
                        items = moreNavItems,
                        currentKey = currentKey,
                        onDismiss = { moreExpanded = false },
                        onSelect = { screen ->
                            moreExpanded = false
                            while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                            if (screen !is Screen.Passes) backStack.add(screen)
                        }
                    )
                }
            }
        }
    }
}