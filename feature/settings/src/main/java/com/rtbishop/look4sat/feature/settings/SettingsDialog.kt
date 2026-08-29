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
package com.rtbishop.look4sat.feature.settings

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rtbishop.look4sat.core.domain.model.Constants
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Preview(showBackground = true)
@Composable
private fun PositionDialogPreview() {
    MainTheme { PositionDialog(0.0, 0.0, {}) { _, _ -> } }
}

@Composable
fun PositionDialog(lat: Double, lon: Double, dismiss: () -> Unit, save: (Double, Double) -> Unit) {
    val latValue = rememberSaveable { mutableStateOf(lat.toString()) }
    val lonValue = rememberSaveable { mutableStateOf(lon.toString()) }
    val titleText = stringResource(id = R.string.prefs_station_title)
    val onAccept = { saveValues(latValue.value, lonValue.value, save).also { dismiss() } }
    SharedDialog(title = titleText, onCancel = dismiss, onAccept = onAccept) {
        OutlinedTextField(
            value = latValue.value,
            onValueChange = { latValue.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_station_lat_text)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        OutlinedTextField(
            value = lonValue.value,
            onValueChange = { lonValue.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_station_lon_text)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        Spacer(modifier = Modifier.height(0.dp))
    }
}

private fun saveValues(latValue: String, lonValue: String, save: (Double, Double) -> Unit) {
    val latitude = latValue.toDoubleOrNull() ?: 0.0
    val longitude = lonValue.toDoubleOrNull() ?: 0.0
    val newLatitude = if (latitude > 90) 90.0 else if (latitude < -90) -90.0 else latitude
    val newLongitude = if (longitude > 180) 180.0 else if (longitude < -180) -180.0 else longitude
    save(newLatitude, newLongitude)
}

@Preview(showBackground = true)
@Composable
private fun LocatorDialogPreview() {
    MainTheme { LocatorDialog("IO91vl", {}) { } }
}

@Composable
fun LocatorDialog(qthLocator: String, dismiss: () -> Unit, save: (String) -> Unit) {
    val locator = rememberSaveable { mutableStateOf(qthLocator) }
    val onAccept = { save(locator.value).also { dismiss() } }
    SharedDialog(title = stringResource(R.string.prefs_locator_title), onCancel = dismiss, onAccept = onAccept) {
        OutlinedTextField(
            value = locator.value,
            onValueChange = { locator.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_locator_text)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        Spacer(modifier = Modifier.height(0.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun TransceiversDialogPreview() {
    MainTheme {
        DataSourcesDialog(
            satelliteUrls = listOf(
                "celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv",
                "amsat.org/tle/current/nasabare.txt"
            ),
            transceiversUrls = listOf(
                "db.satnogs.org/api/transmitters/?format=json&status=active"
            ),
            satelliteEnabled = listOf(true, false),
            transceiversEnabled = listOf(true),
            statusCodes = mapOf(
                "celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv" to 200,
                "amsat.org/tle/current/nasabare.txt" to 404
            ),
            onImportTle = {},
            onImportTransceivers = {},
            onDismiss = {},
            onSave = { _, _, _, _ -> }
        )
    }
}

@Composable
fun DataSourcesDialog(
    satelliteUrls: List<String>,
    transceiversUrls: List<String>,
    satelliteEnabled: List<Boolean>,
    transceiversEnabled: List<Boolean>,
    statusCodes: Map<String, Int>,
    onImportTle: () -> Unit,
    onImportTransceivers: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<String>, List<String>, List<Boolean>, List<Boolean>) -> Unit
) {
    val padding = LocalSpacing.current.large
    // Stable Long IDs avoid key collisions when several entries are empty or duplicated.
    val nextId = remember { mutableLongStateOf((satelliteUrls.size + transceiversUrls.size).toLong()) }
    val satUrls = remember {
        satelliteUrls.mapIndexed { i, url -> i.toLong() to url }.toMutableStateList()
    }
    val txUrls = remember {
        transceiversUrls.mapIndexed { i, url -> (satelliteUrls.size + i).toLong() to url }.toMutableStateList()
    }
    val satEnabled = remember {
        mutableStateMapOf<Long, Boolean>().apply {
            satUrls.forEachIndexed { i, (id, _) -> this[id] = satelliteEnabled.getOrElse(i) { true } }
        }
    }
    val txEnabled = remember {
        mutableStateMapOf<Long, Boolean>().apply {
            txUrls.forEachIndexed { i, (id, _) -> this[id] = transceiversEnabled.getOrElse(i) { true } }
        }
    }
    val onRestoreDefaults = {
        val satDefaults = Sources.satelliteDataUrls.values.filter { it.isNotBlank() }
        val txDefaults = Sources.transceiversDataUrls.values.filter { it.isNotBlank() }
        nextId.longValue = (satDefaults.size + txDefaults.size).toLong()
        satUrls.clear()
        satUrls.addAll(satDefaults.mapIndexed { i, url -> i.toLong() to url })
        txUrls.clear()
        txUrls.addAll(txDefaults.mapIndexed { i, url -> (satDefaults.size + i).toLong() to url })
        satEnabled.clear()
        txEnabled.clear()
        Unit
    }
    val listState = rememberLazyListState()
    val satDraggedId = remember { mutableStateOf(-1L) }
    val txDraggedId = remember { mutableStateOf(-1L) }
    val onAccept = {
        val satFiltered = satUrls.filter { it.second.trim().isNotBlank() }
        val txFiltered = txUrls.filter { it.second.trim().isNotBlank() }
        onSave(
            satFiltered.map { it.second.trim() },
            txFiltered.map { it.second.trim() },
            satFiltered.map { satEnabled[it.first] ?: true },
            txFiltered.map { txEnabled[it.first] ?: true }
        )
        onDismiss()
    }
    SharedDialog(
        title = stringResource(id = R.string.prefs_data_sources_title),
        onCancel = onDismiss,
        onAccept = onAccept,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight(0.84f)
                .padding(horizontal = padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CardButton(
                        onClick = { onImportTle(); onDismiss() },
                        text = "TLE/3LE (.txt)\nOMM (.csv)",
                        modifier = Modifier.weight(1f)
                    )
                    CardButton(
                        onClick = { onImportTransceivers(); onDismiss() },
                        text = "Transceivers\nSatNOGS (.json)",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                CardButton(
                    onClick = onRestoreDefaults,
                    text = stringResource(R.string.prefs_data_sources_restore),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            sourceSection(
                sectionKey = "sat",
                labelResId = R.string.prefs_data_sources_satellites_label,
                urls = satUrls,
                listState = listState,
                draggedId = satDraggedId,
                statusCodes = statusCodes,
                enabledMap = satEnabled,
                onToggle = { id -> satEnabled[id] = !(satEnabled[id] ?: true) },
                onAdd = { satUrls.add(nextId.longValue++ to "") },
                onMove = { from, to -> satUrls.add(to, satUrls.removeAt(from)) },
                onRemove = { i -> satUrls.removeAt(i) },
                onUrlChange = { i, v -> satUrls[i] = satUrls[i].first to v }
            )
            sourceSection(
                sectionKey = "tx",
                labelResId = R.string.prefs_data_sources_transceivers_label,
                urls = txUrls,
                listState = listState,
                draggedId = txDraggedId,
                statusCodes = statusCodes,
                enabledMap = txEnabled,
                onToggle = { id -> txEnabled[id] = !(txEnabled[id] ?: true) },
                onAdd = { txUrls.add(nextId.longValue++ to "") },
                onMove = { from, to -> txUrls.add(to, txUrls.removeAt(from)) },
                onRemove = { i -> txUrls.removeAt(i) },
                onUrlChange = { i, v -> txUrls[i] = txUrls[i].first to v }
            )
        }
    }
}

private fun LazyListScope.sourceSection(
    sectionKey: String,
    labelResId: Int,
    urls: List<Pair<Long, String>>,
    listState: LazyListState,
    draggedId: MutableState<Long>,
    statusCodes: Map<String, Int>,
    enabledMap: Map<Long, Boolean>,
    onToggle: (Long) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onUrlChange: (Int, String) -> Unit
) {
    item {
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(labelResId),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconCard(action = onAdd, resId = R.drawable.ic_add)
        }
    }
    itemsIndexed(urls, key = { _, entry -> "$sectionKey-${entry.first}" }) { index, (id, url) ->
        val enabledTint = MaterialTheme.colorScheme.onSurfaceVariant
        val enabled = enabledMap[id] ?: true
        val rowState = remember { DragRowState() }
        val scope = rememberCoroutineScope()
        val isDragging = draggedId.value == id
        val isLifted = isDragging || rowState.isSettling.value
        LaunchedEffect(isDragging) {
            if (!isDragging) return@LaunchedEffect
            autoScroll(listState, rowState.startCenterY, rowState.fingerOffset, rowState.scrollComp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .draggedVisual(
                    isLifted = isLifted,
                    translationY = if (rowState.isSettling.value) {
                        rowState.settleAnim.value
                    } else {
                        rowState.offsetY.floatValue + rowState.scrollComp.floatValue
                    }
                )
                .animateItem(
                    fadeInSpec = spring(),
                    // The dragged row repositions instantly, while its neighbours spring
                    // out of the way (the "squeeze" effect).
                    placementSpec = if (isDragging) {
                        tween<IntOffset>(durationMillis = 0)
                    } else {
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    },
                    fadeOutSpec = spring()
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .dragHandle(listState, sectionKey, id, urls, draggedId, rowState, scope, onMove)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = enabledTint
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = { onUrlChange(index, it) },
                label = { Text(stringResource(R.string.prefs_data_sources_url_title)) },
                supportingText = statusCodes[url]?.let { code ->
                    { Text(statusLabel(code), color = statusColor(code), fontSize = 12.sp) }
                },
                trailingIcon = {
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null
                        )
                    }
                },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = enabled,
                onCheckedChange = { onToggle(id) }
            )
        }
    }
}

/**
 * Per-row drag state, kept in one object to keep the drag-handle modifier signature small.
 *
 * [offsetY] is the compensated visual displacement during a drag (finger travel minus the
 * heights of already-swapped neighbours), so the row stays glued to the finger. [fingerOffset]
 * tracks the raw finger travel for edge auto-scroll and swap detection. [settleAnim] smoothly
 * flies the lifted row back into its slot once the finger is released.
 */
private class DragRowState {
    val offsetY = mutableFloatStateOf(0f)
    val fingerOffset = mutableFloatStateOf(0f)
    val scrollComp = mutableFloatStateOf(0f)
    val startCenterY = mutableFloatStateOf(0f)
    val settleAnim = Animatable(0f)
    val isSettling = mutableStateOf(false)
}

/** Spring shared by neighbour "squeeze" and the settle-back animation: soft and slightly bouncy. */
private val reorderSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/**
 * Drag handle gesture that performs live reordering while dragging.
 */
@Composable
private fun Modifier.dragHandle(
    listState: LazyListState,
    sectionKey: String,
    entryId: Long,
    urls: List<Pair<Long, String>>,
    draggedId: MutableState<Long>,
    rowState: DragRowState,
    scope: CoroutineScope,
    onMove: (from: Int, to: Int) -> Unit
): Modifier = pointerInput(entryId, sectionKey) {
    fun reorderLive() {
        val myIndex = urls.indexOfFirst { it.first == entryId }
        if (myIndex !in urls.indices) return
        val myCenter = rowState.startCenterY.floatValue + rowState.fingerOffset.floatValue
        val visible = listState.layoutInfo.visibleItemsInfo
        // Dragging down: swap when the dragged centre passes the next row's midpoint.
        if (myIndex < urls.lastIndex) {
            val next = visible.firstOrNull { it.key == "$sectionKey-${urls[myIndex + 1].first}" }
            if (next != null && myCenter > next.offset + next.size / 2f) {
                onMove(myIndex, myIndex + 1)
                rowState.offsetY.floatValue -= next.size.toFloat()
                return
            }
        }
        // Dragging up: swap when the dragged centre passes the previous row's midpoint.
        if (myIndex > 0) {
            val prev = visible.firstOrNull { it.key == "$sectionKey-${urls[myIndex - 1].first}" }
            if (prev != null && myCenter < prev.offset + prev.size / 2f) {
                onMove(myIndex, myIndex - 1)
                rowState.offsetY.floatValue += prev.size.toFloat()
            }
        }
    }

    // Reset the drag bookkeeping and fly the lifted row back into its slot.
    fun finishDrag() {
        val lastOffset = rowState.offsetY.floatValue + rowState.scrollComp.floatValue
        if (kotlin.math.abs(lastOffset) < 1f) {
            rowState.fingerOffset.floatValue = 0f
            rowState.offsetY.floatValue = 0f
            rowState.scrollComp.floatValue = 0f
            draggedId.value = -1L
            return
        }
        scope.launch {
            rowState.settleAnim.snapTo(lastOffset)
            rowState.isSettling.value = true
            rowState.fingerOffset.floatValue = 0f
            rowState.offsetY.floatValue = 0f
            rowState.scrollComp.floatValue = 0f
            draggedId.value = -1L
            rowState.settleAnim.animateTo(0f, reorderSpring)
            rowState.isSettling.value = false
        }
    }

    detectDragGesturesAfterLongPress(
        onDragStart = {
            rowState.isSettling.value = false
            val layout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "$sectionKey-$entryId" }
            rowState.startCenterY.floatValue = (layout?.offset ?: 0) + (layout?.size ?: 0) / 2f
            rowState.fingerOffset.floatValue = 0f
            rowState.offsetY.floatValue = 0f
            rowState.scrollComp.floatValue = 0f
            draggedId.value = entryId
        },
        onDragEnd = ::finishDrag,
        onDragCancel = ::finishDrag
    ) { change, dragAmount ->
        change.consume()
        if (draggedId.value != entryId) return@detectDragGesturesAfterLongPress
        rowState.fingerOffset.floatValue += dragAmount.y
        rowState.offsetY.floatValue += dragAmount.y
        reorderLive()
    }
}

@Composable
private fun Modifier.draggedVisual(isLifted: Boolean, translationY: Float): Modifier {
    val shape = MaterialTheme.shapes.small
    // Smoothly scale the row up/down as the lifted card appears and disappears.
    val scale by animateFloatAsState(
        targetValue = if (isLifted) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dragScale"
    )
    return this
        .graphicsLayer {
            if (isLifted) {
                this.translationY = translationY
                scaleX = scale
                scaleY = scale
            }
        }
        .then(
            if (isLifted) {
                // Solid card on top so the lifted row fully covers the row beneath it
                // instead of showing a translucent overlap of both rows.
                Modifier
                    .zIndex(1f)
                    .shadow(8.dp, shape, clip = false)
                    .background(MaterialTheme.colorScheme.surface, shape)
            } else {
                Modifier
            }
        )
}

/**
 * Scrolls the list while dragging so the entry follows the finger past the viewport edges.
 * The visual centre is tracked independently of the entry's layout slot (which can scroll out
 * of [LazyListState.layoutInfo.visibleItemsInfo] during a long drag); [startCenterY] is the
 * entry's viewport centre captured at drag start and [fingerOffset] is the raw finger delta.
 */
private suspend fun autoScroll(
    listState: LazyListState,
    startCenterY: MutableFloatState,
    fingerOffset: MutableFloatState,
    scrollComp: MutableFloatState
) {
    val threshold = 48f
    val maxSpeed = 24f
    while (true) {
        val info = listState.layoutInfo
        val center = startCenterY.floatValue + fingerOffset.floatValue
        val top = info.viewportStartOffset + threshold
        val bottom = info.viewportEndOffset - threshold
        val delta = when {
            center < top -> -(top - center).coerceAtMost(maxSpeed)
            center > bottom -> (center - bottom).coerceAtMost(maxSpeed)
            else -> 0f
        }
        if (delta != 0f) scrollComp.floatValue += listState.scrollBy(delta)
        delay(16L)
    }
}

private fun statusLabel(code: Int): String = if (code == NetworkResult.CONNECTION_ERROR) "ERR" else code.toString()

@Composable
private fun statusColor(code: Int): Color = when {
    code == NetworkResult.CONNECTION_ERROR -> MaterialTheme.colorScheme.error
    code in 200..299 -> Color(0xFF66BB6A)
    else -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true)
@Composable
fun PreviewNetworkOutputDialog() {
    MainTheme {
        NetworkOutputDialog(
            initialSettings = RCSettings(
                rotatorState = false,
                rotatorAddress = "127.0.0.1",
                rotatorPort = "4533",
                rotatorFormat = $$"P $AZ $EL",
                frequencyState = false,
                frequencyAddress = "127.0.0.1",
                frequencyPort = "4532",
                frequencyFormat = $$"F $FREQ",
                frequencyOffsetHz = 0L,
                bluetoothRotatorState = false,
                bluetoothRotatorFormat = $$"P $AZ $EL",
                bluetoothRotatorName = "Default",
                bluetoothRotatorAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyState = false,
                bluetoothFrequencyAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyFormat = $$"F $FREQ"
            ),
            onDismiss = {},
            onSave = { _, _, _, _, _, _, _, _, _ -> }
        )
    }
}

@Composable
fun NetworkOutputDialog(
    initialSettings: RCSettings,
    onDismiss: () -> Unit,
    onSave: (
        Boolean, String, String, String,
        Boolean, String, String, String, Long
    ) -> Unit
) {
    val padding = LocalSpacing.current.large
    val rotatorState = rememberSaveable { mutableStateOf(initialSettings.rotatorState) }
    val rotatorAddress = rememberSaveable {
        mutableStateOf("${initialSettings.rotatorAddress}:${initialSettings.rotatorPort}")
    }
    val rotatorFormat = rememberSaveable { mutableStateOf(initialSettings.rotatorFormat) }
    val frequencyState = rememberSaveable { mutableStateOf(initialSettings.frequencyState) }
    val frequencyAddress = rememberSaveable {
        mutableStateOf("${initialSettings.frequencyAddress}:${initialSettings.frequencyPort}")
    }
    val frequencyFormat = rememberSaveable { mutableStateOf(initialSettings.frequencyFormat) }
    val frequencyOffsetHz = rememberSaveable { mutableStateOf(initialSettings.frequencyOffsetHz.toString()) }
    val onAccept = {
        val (rotIp, rotPort) = splitAddress(rotatorAddress.value)
        val (freqIp, freqPort) = splitAddress(frequencyAddress.value)
        val offsetHz = (frequencyOffsetHz.value.trim().toLongOrNull() ?: 0L)
            .coerceIn(Constants.FREQ_OFFSET_MIN_HZ, Constants.FREQ_OFFSET_MAX_HZ)
        onSave(
            rotatorState.value, rotIp, rotPort, rotatorFormat.value,
            frequencyState.value, freqIp, freqPort, frequencyFormat.value, offsetHz
        )
        onDismiss()
    }
    SharedDialog(
        title = stringResource(R.string.prefs_net_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(modifier = Modifier.padding(horizontal = padding)) {
            OutputChannelSection(
                switchLabel = stringResource(R.string.prefs_net_rotator_switch),
                enabled = rotatorState.value,
                onEnabledChange = { rotatorState.value = it },
                address = rotatorAddress.value,
                onAddressChange = { rotatorAddress.value = it },
                addressLabel = stringResource(R.string.prefs_net_rotator_address_hint),
                format = rotatorFormat.value,
                onFormatChange = { rotatorFormat.value = it },
                formatLabel = stringResource(R.string.prefs_net_rotator_format_hint)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutputChannelSection(
                switchLabel = stringResource(R.string.prefs_net_frequency_switch),
                enabled = frequencyState.value,
                onEnabledChange = { frequencyState.value = it },
                address = frequencyAddress.value,
                onAddressChange = { frequencyAddress.value = it },
                addressLabel = stringResource(R.string.prefs_net_frequency_address_hint),
                format = frequencyFormat.value,
                onFormatChange = { frequencyFormat.value = it },
                formatLabel = stringResource(R.string.prefs_net_frequency_format_hint)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = frequencyOffsetHz.value,
                onValueChange = { frequencyOffsetHz.value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.prefs_net_frequency_offset_hint)) },
                supportingText = { Text(stringResource(R.string.prefs_net_frequency_offset_help)) },
                trailingIcon = {
                    IconButton(
                        onClick = { frequencyOffsetHz.value = "0" },
                        enabled = frequencyState.value && frequencyOffsetHz.value != "0"
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = frequencyState.value
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun splitAddress(address: String): Pair<String, String> {
    val lastColon = address.lastIndexOf(':')
    return if (lastColon >= 0) {
        address.substring(0, lastColon) to address.substring(lastColon + 1)
    } else {
        address to ""
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewBluetoothOutputDialog() {
    MainTheme {
        BluetoothOutputDialog(
            initialSettings = RCSettings(
                rotatorState = false,
                rotatorAddress = "127.0.0.1",
                rotatorPort = "4533",
                rotatorFormat = $$"P $AZ $EL",
                frequencyState = false,
                frequencyAddress = "127.0.0.1",
                frequencyPort = "4532",
                frequencyFormat = $$"F $FREQ",
                frequencyOffsetHz = 0L,
                bluetoothRotatorState = false,
                bluetoothRotatorFormat = $$"P $AZ $EL",
                bluetoothRotatorName = "Default",
                bluetoothRotatorAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyState = false,
                bluetoothFrequencyAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyFormat = $$"F $FREQ"
            ),
            onDismiss = {},
            onSave = { _, _, _, _, _, _ -> }
        )
    }
}

@Composable
fun BluetoothOutputDialog(
    initialSettings: RCSettings,
    onDismiss: () -> Unit,
    onSave: (
        Boolean, String, String,
        Boolean, String, String
    ) -> Unit
) {
    val padding = LocalSpacing.current.large
    val rotatorState = rememberSaveable { mutableStateOf(initialSettings.bluetoothRotatorState) }
    val rotatorAddress = rememberSaveable { mutableStateOf(initialSettings.bluetoothRotatorAddress) }
    val rotatorFormat = rememberSaveable { mutableStateOf(initialSettings.bluetoothRotatorFormat) }
    val frequencyState = rememberSaveable { mutableStateOf(initialSettings.bluetoothFrequencyState) }
    val frequencyAddress = rememberSaveable { mutableStateOf(initialSettings.bluetoothFrequencyAddress) }
    val frequencyFormat = rememberSaveable { mutableStateOf(initialSettings.bluetoothFrequencyFormat) }
    val onAccept = {
        onSave(
            rotatorState.value, rotatorAddress.value, rotatorFormat.value,
            frequencyState.value, frequencyAddress.value, frequencyFormat.value
        )
        onDismiss()
    }
    SharedDialog(
        title = stringResource(R.string.prefs_bt_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(modifier = Modifier.padding(horizontal = padding)) {
            OutputChannelSection(
                switchLabel = stringResource(R.string.prefs_bt_rotator_switch),
                enabled = rotatorState.value,
                onEnabledChange = { rotatorState.value = it },
                address = rotatorAddress.value,
                onAddressChange = { rotatorAddress.value = it },
                addressLabel = stringResource(R.string.prefs_bt_rotator_device_hint),
                format = rotatorFormat.value,
                onFormatChange = { rotatorFormat.value = it },
                formatLabel = stringResource(R.string.prefs_bt_rotator_output_hint)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutputChannelSection(
                switchLabel = stringResource(R.string.prefs_bt_frequency_switch),
                enabled = frequencyState.value,
                onEnabledChange = { frequencyState.value = it },
                address = frequencyAddress.value,
                onAddressChange = { frequencyAddress.value = it },
                addressLabel = stringResource(R.string.prefs_bt_frequency_device_hint),
                format = frequencyFormat.value,
                onFormatChange = { frequencyFormat.value = it },
                formatLabel = stringResource(R.string.prefs_bt_frequency_output_hint)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Reusable section for a switch-toggled output channel with address and format fields.
 * Used by both Network and Bluetooth output dialogs.
 */
@Composable
private fun OutputChannelSection(
    switchLabel: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
    addressLabel: String,
    format: String,
    onFormatChange: (String) -> Unit,
    formatLabel: String
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(switchLabel)
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            singleLine = true,
            label = { Text(addressLabel) },
            modifier = Modifier.weight(0.6f),
            enabled = enabled
        )
        OutlinedTextField(
            value = format,
            onValueChange = onFormatChange,
            singleLine = true,
            label = { Text(formatLabel) },
            modifier = Modifier.weight(0.4f),
            enabled = enabled
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioControlDialog(
    initialSettings: RadioControlSettings,
    onDismiss: () -> Unit,
    onSave: (RadioControlSettings) -> Unit
) {
    val context    = androidx.compose.ui.platform.LocalContext.current
    val padding    = LocalSpacing.current.large
    val enabled    = rememberSaveable { mutableStateOf(initialSettings.enabled) }
    val radioModel = rememberSaveable { mutableStateOf(initialSettings.radioModel) }
    val splitMode  = rememberSaveable { mutableStateOf(initialSettings.splitMode) }
    val txAddress  = rememberSaveable { mutableStateOf(initialSettings.txRadioAddress) }
    val rxAddress  = rememberSaveable { mutableStateOf(initialSettings.rxRadioAddress) }
    val txName     = rememberSaveable { mutableStateOf(initialSettings.txRadioName) }
    val rxName     = rememberSaveable { mutableStateOf(initialSettings.rxRadioName) }
    val baudRate   = rememberSaveable { mutableIntStateOf(initialSettings.baudRate) }
    val selectingFor = rememberSaveable { mutableStateOf("") } // "tx", "rx", or ""

    val isIcom = radioModel.value == RadioControlSettings.MODEL_ICOM_IC705
    val isSingleRadio = isIcom && splitMode.value

    // Reset split mode when switching away from IC-705
    if (!isIcom && splitMode.value) splitMode.value = false

    val baudRates = if (isIcom) RadioControlSettings.BAUD_RATES_ICOM
                   else         RadioControlSettings.BAUD_RATES_YAESU

    // If current baud rate is not in the new list, default to the first available
    if (baudRate.intValue !in baudRates) baudRate.intValue = baudRates.first()

    val pairedDevices: List<Pair<String, String>> = remember {
        buildList {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.bondedDevices?.forEach {
                    add(Pair(it.name ?: "Unknown", it.address ?: ""))
                }
            } catch (_: SecurityException) { }
        }
    }

    val onAccept = {
        onSave(
            RadioControlSettings(
                enabled        = enabled.value,
                radioModel     = radioModel.value,
                txRadioAddress = txAddress.value,
                rxRadioAddress = if (isSingleRadio) "" else rxAddress.value,
                txRadioName    = txName.value,
                rxRadioName    = if (isSingleRadio) "" else rxName.value,
                baudRate       = baudRate.intValue,
                splitMode      = splitMode.value
            )
        )
        onDismiss()
    }

    SharedDialog(
        title    = stringResource(R.string.rc_settings_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(modifier = Modifier.padding(horizontal = padding)) {

            // Enable switch
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
                modifier              = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.rc_enable_switch))
                Switch(checked = enabled.value, onCheckedChange = { enabled.value = it })
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Radio model — FlowRow so chips wrap on small screens
            Text(
                text       = stringResource(R.string.rc_radio_model),
                fontWeight = FontWeight.Medium,
                color      = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioControlSettings.SUPPORTED_RADIOS.forEach { model ->
                    FilterChip(
                        selected = radioModel.value == model,
                        onClick  = { radioModel.value = model },
                        label    = { Text(model, fontSize = 12.sp) },
                        enabled  = enabled.value
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // IC-705 split-mode toggle (only shown for IC-705)
            if (isIcom) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Split mode (single radio)", fontWeight = FontWeight.Medium)
                        Text(
                            text     = "Use VFO-A/B split on one IC-705 instead of two radios",
                            fontSize = 12.sp,
                            color    = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked         = splitMode.value,
                        onCheckedChange = { splitMode.value = it },
                        enabled         = enabled.value
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // TX Radio (always shown; in split mode this is the single IC-705)
            val txLabel = if (isSingleRadio) "Radio (IC-705)" else "TX Radio (Uplink)"
            Text(txLabel, fontWeight = FontWeight.Medium)
            if (txAddress.value.isNotBlank()) {
                Text("${txName.value} — ${txAddress.value}", fontSize = 13.sp)
            }
            CardButton(
                onClick  = { selectingFor.value = "tx" },
                text     = if (isSingleRadio) "Select Device" else "Select TX Device",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))

            // RX Radio (hidden in split mode — the same radio handles both)
            if (!isSingleRadio) {
                Text("RX Radio (Downlink)", fontWeight = FontWeight.Medium)
                if (rxAddress.value.isNotBlank()) {
                    Text("${rxName.value} — ${rxAddress.value}", fontSize = 13.sp)
                }
                CardButton(
                    onClick  = { selectingFor.value = "rx" },
                    text     = "Select RX Device",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Paired device picker (inline, shown while selecting)
            if (selectingFor.value.isNotBlank()) {
                Text(
                    text       = "Paired Bluetooth Devices:",
                    fontWeight = FontWeight.Medium,
                    color      = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (pairedDevices.isEmpty()) {
                    Text(
                        "No paired devices found. Pair your BT adapter in Android Bluetooth settings first.",
                        fontSize = 13.sp
                    )
                } else {
                    pairedDevices.forEach { (name, address) ->
                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectingFor.value == "tx") {
                                        txAddress.value = address
                                        txName.value    = name
                                    } else {
                                        rxAddress.value = address
                                        rxName.value    = name
                                    }
                                    selectingFor.value = ""
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, modifier = Modifier.weight(1f))
                                Text(address, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Baud rate — FlowRow so all chips fit on narrow screens
            Text("Baud Rate:", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                baudRates.forEach { rate ->
                    FilterChip(
                        selected = rate == baudRate.intValue,
                        onClick  = { baudRate.intValue = rate },
                        label    = { Text(rate.toString(), fontSize = 12.sp) },
                        enabled  = enabled.value
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
