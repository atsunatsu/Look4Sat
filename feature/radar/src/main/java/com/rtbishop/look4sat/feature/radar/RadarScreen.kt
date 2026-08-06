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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.wavelog.UploadOutcome
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQueue
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
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import com.rtbishop.look4sat.core.presentation.layoutPadding
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI

private enum class RadarPage(val title: String) {
    Transceivers("Transceivers"),
    Log("Log"),
    Calculator("Calculator"),
    Sstv("SSTV")
}

@Composable
fun RadarDestination(navigateUp: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: RadarViewModel = viewModel(factory = RadarViewModel.factory(container))
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
        while (true) {
            val remainingMs = mutualData.endTime - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                navigateUpAndClearMutual()
                return@LaunchedEffect
            }
            delay(remainingMs.coerceAtMost(1000L))
        }
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
    // WaveLog(4.5.2): 自动上传 — 每 10 分钟重试本地队列(开关开启时)
    val wavelogUploader = remember { container.provideWavelogUploader() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10 * 60 * 1000L)
            val s = container.settingsRepo.otherSettings.value
            if (s.wavelogAutoUpload && s.wavelogUrl.isNotBlank()) {
                // 网格不一致时静默跳过(留给手动上传确认), 其余失败自动下次重试
                val outcome = wavelogUploader.uploadQueue()
                if (outcome is UploadOutcome.NeedConfirm) {
                    // 跳过本次; 用户可在设置页手动上传并确认
                }
            }
        }
    }
    RadarScreen(
        uiState,
        viewModel::onAction,
        navigateUpAndClearMutual,
        mutualData,
        { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        wavelogQueue = container.wavelogQueue,
        wavelogConfigured = container.settingsRepo.otherSettings.value.let {
            it.wavelogUrl.isNotBlank() && it.wavelogApiKey.isNotBlank() && it.wavelogStationId.isNotBlank()
        },
        showToast = { msg -> container.provideShowToast()(msg) }
    )
}

@Composable
private fun RadarScreen(
    uiState: RadarState,
    onAction: (RadarAction) -> Unit,
    navigateUp: () -> Unit,
    mutualData: MutualPassData,
    requestMicPermission: () -> Unit,
    wavelogQueue: WavelogQueue,
    wavelogConfigured: Boolean,
    showToast: (String) -> Unit
) {
    val upcomingPass = uiState.currentPass ?: getDefaultPass()
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
    Column(
        modifier = Modifier
            .layoutPadding()
            .keepScreenOn(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val isVertical = isVerticalLayout()
        if (isVertical) {
            TopBar {
                IconCard(action = navigateUp, resId = R.drawable.ic_back)
                TimerRow(timeString = uiState.currentTime, isTimeAos = uiState.isTimeAos)
                IconCard(action = addToCalendar, resId = R.drawable.ic_calendar)
            }
            TopBar { NextPassRow(pass = upcomingPass, isUtc = uiState.isUtc) }
        } else {
            TopBar {
                IconCard(action = navigateUp, resId = R.drawable.ic_back)
                TimerRow(timeString = uiState.currentTime, isTimeAos = uiState.isTimeAos)
                NextPassRow(pass = upcomingPass, modifier = Modifier.weight(1f), isUtc = uiState.isUtc)
                IconCard(action = addToCalendar, resId = R.drawable.ic_calendar)
            }
        }
        if (isVertical) {
            RadarCard(uiState, trackB, trackBPosition, Modifier.weight(1f))
            PagerCard(uiState, onAction, requestMicPermission, wavelogQueue, wavelogConfigured, showToast, Modifier.weight(1f))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RadarCard(uiState, trackB, trackBPosition, Modifier.weight(1f))
                PagerCard(uiState, onAction, requestMicPermission, wavelogQueue, wavelogConfigured, showToast, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PagerCard(
    uiState: RadarState,
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit,
    wavelogQueue: WavelogQueue,
    wavelogConfigured: Boolean,
    showToast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCalculatorPage = remember(uiState.transceivers.transmitters) {
        uiState.transceivers.transmitters.any(DopplerFrequencyCalculator::isNamedLinearTransponder)
    }
    val pages = remember(hasCalculatorPage) {
        buildList {
            add(RadarPage.Transceivers)
            add(RadarPage.Log)
            if (hasCalculatorPage) add(RadarPage.Calculator)
            add(RadarPage.Sstv)
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pages.size) {
        val lastPage = pages.lastIndex
        if (pagerState.currentPage > lastPage) pagerState.scrollToPage(lastPage)
    }

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            val selectedTabIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                pages.forEachIndexed { index, page ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(text = page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                        onAction = onAction,
                        requestMicPermission = requestMicPermission
                    )
                    RadarPage.Sstv -> SstvPage(
                        sstv = uiState.sstv,
                        dopplerFrequency = uiState.transceivers.selectedFrequency?.let { formatFrequency(it) },
                        onAction = onAction,
                        requestMicPermission = requestMicPermission
                    )
                    RadarPage.Log -> LogTab(
                        transceivers = uiState.transceivers.transmitters,
                        orbitalPos = uiState.orbitalPos,
                        satelliteName = uiState.currentPass?.name ?: "",
                        queue = wavelogQueue,
                        wavelogConfigured = wavelogConfigured,
                        showToast = showToast,
                        txBaseFrequencyHz = uiState.radioControl.txBaseFrequencyHz,
                        aosTimeMs = uiState.currentPass?.aosTime ?: 0L
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
    modifier: Modifier = Modifier
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
                PositionOverlay(position)
            }
        }
    }
}

@Composable
private fun PositionOverlay(position: OrbitalPos) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
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
