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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.repository.TrackSampleData
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MutualScreen(
    viewModel: MutualViewModel,
    navigateToRadar: (Int, Long, MutualPassData?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    ScreenColumn(
        topBar = { isVertical ->
            TopBar(
                isVerticalLayout = isVertical,
                startAction = {},
                topInfo = {
                    Text(
                        text = stringResource(R.string.mutual_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                bottomInfo = {
                    Text(
                        text = mutualStatusText(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                },
                endAction = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.isCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(24.dp)
                                    .width(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        MutualStatusChip(state)
                    }
                }
            )
        }
    ) { isVertical ->
        MutualContent(
            state = state,
            isVertical = isVertical,
            onQuery = viewModel::queryMutualPasses,
            onSelectPass = viewModel::onSelectPass,
            onNavigateToRadar = navigateToRadar,
            onStationALat = viewModel::onStationALat,
            onStationALon = viewModel::onStationALon,
            onStationAGrid = viewModel::onStationAGrid,
            onStationAMinElev = viewModel::onStationAMinElev,
            onStationBLat = viewModel::onStationBLat,
            onStationBLon = viewModel::onStationBLon,
            onStationBGrid = viewModel::onStationBGrid,
            onStationBMinElev = viewModel::onStationBMinElev,
            onUseCurrentPosition = viewModel::onUseCurrentPosition,
            onHoursAhead = viewModel::onHoursAhead,
            onClearError = viewModel::clearError
        )
    }
}

@Composable
private fun MutualContent(
    state: MutualUiState,
    isVertical: Boolean,
    onQuery: () -> Unit,
    onSelectPass: (Int) -> Unit,
    onNavigateToRadar: (Int, Long, MutualPassData?) -> Unit,
    onStationALat: (String) -> Unit,
    onStationALon: (String) -> Unit,
    onStationAGrid: (String) -> Unit,
    onStationAMinElev: (Double) -> Unit,
    onStationBLat: (String) -> Unit,
    onStationBLon: (String) -> Unit,
    onStationBGrid: (String) -> Unit,
    onStationBMinElev: (Double) -> Unit,
    onUseCurrentPosition: () -> Unit,
    onHoursAhead: (Int) -> Unit,
    onClearError: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Error message
        val errorMsg = state.errorMessage
        if (errorMsg != null) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Input form
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isVertical) {
                    StationInputCard(
                        title = stringResource(R.string.mutual_station_you),
                        titleColor = MaterialTheme.colorScheme.primary,
                        lat = state.stationALat,
                        lon = state.stationALon,
                        grid = state.stationAGrid,
                        minElev = state.stationAMinElev,
                        latPlaceholder = "39.9042",
                        lonPlaceholder = "116.4074",
                        gridPlaceholder = "ON79uj",
                        onLatChange = onStationALat,
                        onLonChange = onStationALon,
                        onGridChange = onStationAGrid,
                        onMinElevChange = onStationAMinElev,
                        currentPositionAction = onUseCurrentPosition
                    )
                    StationInputCard(
                        title = stringResource(R.string.mutual_station_opposite),
                        titleColor = MaterialTheme.colorScheme.tertiary,
                        lat = state.stationBLat,
                        lon = state.stationBLon,
                        grid = state.stationBGrid,
                        minElev = state.stationBMinElev,
                        latPlaceholder = "34.0522",
                        lonPlaceholder = "-118.2437",
                        gridPlaceholder = "PM01tv",
                        onLatChange = onStationBLat,
                        onLonChange = onStationBLon,
                        onGridChange = onStationBGrid,
                        onMinElevChange = onStationBMinElev
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StationInputCard(
                            title = stringResource(R.string.mutual_station_you),
                            titleColor = MaterialTheme.colorScheme.primary,
                            lat = state.stationALat,
                            lon = state.stationALon,
                            grid = state.stationAGrid,
                            minElev = state.stationAMinElev,
                            latPlaceholder = "39.9042",
                            lonPlaceholder = "116.4074",
                            gridPlaceholder = "ON79uj",
                            onLatChange = onStationALat,
                            onLonChange = onStationALon,
                            onGridChange = onStationAGrid,
                            onMinElevChange = onStationAMinElev,
                            currentPositionAction = onUseCurrentPosition,
                            modifier = Modifier.weight(1f)
                        )
                        StationInputCard(
                            title = stringResource(R.string.mutual_station_opposite),
                            titleColor = MaterialTheme.colorScheme.tertiary,
                            lat = state.stationBLat,
                            lon = state.stationBLon,
                            grid = state.stationBGrid,
                            minElev = state.stationBMinElev,
                            latPlaceholder = "34.0522",
                            lonPlaceholder = "-118.2437",
                            gridPlaceholder = "PM01tv",
                            onLatChange = onStationBLat,
                            onLonChange = onStationBLon,
                            onGridChange = onStationBGrid,
                            onMinElevChange = onStationBMinElev,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                MatchSearchCard(
                    hoursAhead = state.hoursAhead,
                    isCalculating = state.isCalculating,
                    onHoursAhead = onHoursAhead,
                    onQuery = onQuery
                )
            }
        }

        // Results
        itemsIndexed(state.mutualPasses) { index, pass ->
            val mutualData = MutualPassData(
                samples = pass.elevationSamples,
                trackSamples = pass.trackSamples.map {
                    TrackSampleData(
                        time = it.time,
                        azimuthA = it.azimuthA,
                        elevationA = it.elevationA,
                        azimuthB = it.azimuthB,
                        elevationB = it.elevationB
                    )
                },
                startTime = pass.startTime,
                endTime = pass.endTime,
                maxElev = maxOf(pass.maxElevationA, pass.maxElevationB, 10.0),
                labelA = stringResource(R.string.mutual_label_you),
                labelB = stringResource(R.string.mutual_label_opposite)
            )
            MutualPassCard(
                pass = pass,
                isExpanded = state.selectedPassIndex == index,
                timeFormat = timeFormat,
                minElevA = state.stationAMinElev,
                minElevB = state.stationBMinElev,
                onClick = { onSelectPass(if (state.selectedPassIndex == index) -1 else index) },
                onNavigateToRadar = { onNavigateToRadar(pass.catNum, pass.startTime, mutualData) }
            )
        }
    }
}

@Composable
private fun MutualStatusChip(state: MutualUiState) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = when {
        state.isCalculating -> colorScheme.primaryContainer
        state.errorMessage != null -> colorScheme.errorContainer
        state.mutualPasses.isNotEmpty() -> colorScheme.primary
        state.hasSearched -> colorScheme.surfaceVariant
        else -> colorScheme.surfaceVariant
    }
    val contentColor = when {
        state.isCalculating -> colorScheme.onPrimaryContainer
        state.errorMessage != null -> colorScheme.onErrorContainer
        state.mutualPasses.isNotEmpty() -> colorScheme.onPrimary
        else -> colorScheme.onSurfaceVariant
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = mutualStatusChipText(state),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun mutualStatusText(state: MutualUiState): String = when {
    state.isCalculating -> stringResource(R.string.mutual_status_calculating)
    state.errorMessage != null -> stringResource(R.string.mutual_status_error)
    state.mutualPasses.isNotEmpty() -> stringResource(R.string.mutual_status_matches, state.mutualPasses.size)
    state.hasSearched -> stringResource(R.string.mutual_status_none)
    else -> stringResource(R.string.mutual_status_idle)
}

@Composable
private fun mutualStatusChipText(state: MutualUiState): String = when {
    state.isCalculating -> stringResource(R.string.mutual_chip_calculating)
    state.errorMessage != null -> stringResource(R.string.mutual_chip_error)
    state.mutualPasses.isNotEmpty() -> stringResource(R.string.mutual_chip_matches, state.mutualPasses.size)
    state.hasSearched -> stringResource(R.string.mutual_chip_none)
    else -> stringResource(R.string.mutual_chip_idle)
}

@Composable
private fun StationInputCard(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    lat: String,
    lon: String,
    grid: String,
    minElev: Double,
    latPlaceholder: String,
    lonPlaceholder: String,
    gridPlaceholder: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
    onGridChange: (String) -> Unit,
    onMinElevChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    currentPositionAction: (() -> Unit)? = null
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                if (currentPositionAction != null) {
                    OutlinedButton(onClick = currentPositionAction) {
                        Text(stringResource(R.string.mutual_use_current), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = lat,
                    onValueChange = onLatChange,
                    label = { Text(stringResource(R.string.mutual_lat)) },
                    placeholder = { Text(latPlaceholder) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = onLonChange,
                    label = { Text(stringResource(R.string.mutual_lon)) },
                    placeholder = { Text(lonPlaceholder) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = grid,
                onValueChange = onGridChange,
                label = { Text(stringResource(R.string.mutual_grid)) },
                placeholder = { Text(gridPlaceholder) },
                supportingText = { Text(stringResource(R.string.mutual_grid_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.mutual_min_elevation, minElev.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = minElev.toFloat(),
                onValueChange = { onMinElevChange(it.toDouble()) },
                valueRange = 0f..90f,
                steps = 17
            )
        }
    }
}

@Composable
private fun MatchSearchCard(
    hoursAhead: Int,
    isCalculating: Boolean,
    onHoursAhead: (Int) -> Unit,
    onQuery: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.mutual_time_range),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(6, 12, 24, 48, 72).forEach { hours ->
                    FilterChip(
                        selected = hoursAhead == hours,
                        onClick = { onHoursAhead(hours) },
                        label = { Text("${hours}h") }
                    )
                }
            }
            Button(
                onClick = onQuery,
                enabled = !isCalculating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCalculating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(18.dp)
                            .width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isCalculating) stringResource(R.string.mutual_calculating) else stringResource(R.string.mutual_query))
            }
        }
    }
}

@Composable
private fun MutualPassCard(
    pass: MutualPass,
    isExpanded: Boolean,
    timeFormat: SimpleDateFormat,
    minElevA: Double,
    minElevB: Double,
    onClick: () -> Unit,
    onNavigateToRadar: () -> Unit
) {
    // Shared time cursor: both the elevation curve and the radar track view
    // are controlled by this single progress value for bidirectional drag sync.
    var dragProgress by remember(pass) { mutableFloatStateOf(0.5f) }

    // Filter to only show the portion where both stations are above their minElev
    val visibleSamples = remember(pass, minElevA, minElevB) {
        pass.elevationSamples.filter { (_, elev) ->
            elev.first >= minElevA && elev.second >= minElevB
        }
    }
    val visibleTracks = remember(pass, minElevA, minElevB) {
        pass.trackSamples.filter { it.elevationA >= minElevA && it.elevationB >= minElevB }
    }
    val visibleStart = visibleSamples.firstOrNull()?.first ?: pass.startTime
    val visibleEnd = visibleSamples.lastOrNull()?.first ?: pass.endTime
    val adjustedMaxElev = maxOf(
        visibleSamples.maxOfOrNull { (_, elev) -> elev.first } ?: 10.0,
        visibleSamples.maxOfOrNull { (_, elev) -> elev.second } ?: 10.0,
        10.0
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pass.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${timeFormat.format(Date(pass.startTime))} - ${timeFormat.format(Date(pass.endTime))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.mutual_elevation_you, pass.maxElevationA.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.mutual_elevation_opposite, pass.maxElevationB.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    // Dual-station radar track (polar plot)
                    if (visibleTracks.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.mutual_both_tracks),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MutualRadarView(
                            trackSamples = visibleTracks,
                            progress = dragProgress,
                            labelA = stringResource(R.string.mutual_label_you),
                            labelB = stringResource(R.string.mutual_label_opposite)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (visibleSamples.isNotEmpty()) {
                        ElevationCurveChart(
                            samples = visibleSamples,
                            startTime = visibleStart,
                            endTime = visibleEnd,
                            maxElev = adjustedMaxElev,
                            progress = dragProgress,
                            onProgressChange = { dragProgress = it }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToRadar,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_radar),
                            contentDescription = null,
                            modifier = Modifier.height(16.dp).width(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mutual_open_radar))
                    }
                }
            }
        }
    }
}