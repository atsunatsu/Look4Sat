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

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import android.content.Context
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.PrimaryIconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import com.rtbishop.look4sat.core.presentation.defaultScreenOrder
import com.rtbishop.look4sat.core.presentation.defaultSubMenuOrder
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsDestination() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(uiState, viewModel::onAction)

    // WaveLog 网格不一致确认弹窗(4.5.2)
    val gridConfirm = viewModel.gridConfirm
    if (gridConfirm != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveGridConfirm(false) },
            title = { Text(text = stringResource(id = R.string.wavelog_title)) },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.wavelog_grid_mismatch,
                        gridConfirm.userGrid.take(4),
                        gridConfirm.stationGrid.take(4)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveGridConfirm(true) }) {
                    Text(text = stringResource(id = R.string.wavelog_ignore))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveGridConfirm(false) }) {
                    Text(text = stringResource(id = R.string.wavelog_cancel))
                }
            }
        )
    }

    // WaveLog 错误详情弹窗(可一键复制)
    val wavelogError = viewModel.wavelogError
    if (wavelogError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.wavelogError = null },
            title = { Text(text = stringResource(id = R.string.wavelog_title)) },
            text = {
                Text(
                    text = wavelogError,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WaveLog error", wavelogError))
                    viewModel.wavelogError = null
                }) {
                    Text(text = stringResource(id = R.string.wavelog_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.wavelogError = null }) {
                    Text(text = stringResource(id = R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsScreen(uiState: SettingsState, onAction: (SettingsAction) -> Unit) {
    val dialogs = rememberDialogVisibility()
    val pendingCustomSourcesGrant = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingCustomSourcesDeny = remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberSettingsPermissions(
        sendAction = onAction,
        onBluetoothGranted = { dialogs.bluetooth = true },
        onNetworkGranted = { dialogs.network = true },
        onCustomSourcesPermissionGranted = {
            pendingCustomSourcesGrant.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        },
        onCustomSourcesPermissionDenied = {
            pendingCustomSourcesDeny.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        }
    )

    // Dialogs
    if (dialogs.position) {
        PositionDialog(
            uiState.positionSettings.stationPos.latitude,
            uiState.positionSettings.stationPos.longitude,
            dismiss = { dialogs.position = false },
            save = { lat, lon -> onAction(SettingsAction.SetGeoPosition(lat, lon)) }
        )
    }
    if (dialogs.locator) {
        LocatorDialog(
            uiState.positionSettings.stationPos.qthLocator,
            dismiss = { dialogs.locator = false },
            save = { onAction(SettingsAction.SetQthPosition(it)) }
        )
    }
    if (dialogs.dataSources) {
        DataSourcesDialog(
            useCustomTle = uiState.dataSourcesSettings.useCustomTLE,
            useCustomTransceivers = uiState.dataSourcesSettings.useCustomTransceivers,
            tleUrl = uiState.dataSourcesSettings.tleUrl,
            transceiversUrl = uiState.dataSourcesSettings.transceiversUrl,
            requestCustomSourcesPermission = { onGranted, onDenied ->
                pendingCustomSourcesGrant.value = onGranted
                pendingCustomSourcesDeny.value = onDenied
                permissions.launchCustomSourcesPermission()
            },
            onImportTle = { permissions.launchTleImport(); dialogs.dataSources = false },
            onImportTransceivers = { permissions.launchTransceiverImport(); dialogs.dataSources = false },
            onDismiss = { dialogs.dataSources = false },
            onSave = { useCustomTle, useCustomTransceivers, tleUrl, transceiversUrl ->
                val current = uiState.dataSourcesSettings
                val newSettings = current.copy(
                    useCustomTLE = if (!useCustomTle || tleUrl.isNotBlank()) useCustomTle else current.useCustomTLE,
                    tleUrl = if (!useCustomTle || tleUrl.isNotBlank()) tleUrl else current.tleUrl,
                    useCustomTransceivers = if (!useCustomTransceivers || transceiversUrl.isNotBlank()) useCustomTransceivers else current.useCustomTransceivers,
                    transceiversUrl = if (!useCustomTransceivers || transceiversUrl.isNotBlank()) transceiversUrl else current.transceiversUrl
                )
                if (newSettings != current) onAction(SettingsAction.UpdateDataSources(newSettings))
                if (newSettings.useCustomTLE || newSettings.useCustomTransceivers) {
                    onAction(SettingsAction.UpdateFromWeb)
                }
            }
        )
    }
    if (dialogs.network) {
        NetworkOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.network = false },
            onSave = { rotState, rotAddr, rotPort, rotFmt, freqState, freqAddr, freqPort, freqFmt ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            rotatorState = rotState, rotatorAddress = rotAddr,
                            rotatorPort = rotPort, rotatorFormat = rotFmt,
                            frequencyState = freqState, frequencyAddress = freqAddr,
                            frequencyPort = freqPort, frequencyFormat = freqFmt
                        )
                    )
                )
            }
        )
    }
    if (dialogs.bluetooth) {
        BluetoothOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.bluetooth = false },
            onSave = { rotState, rotAddr, rotFmt, freqState, freqAddr, freqFmt ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            bluetoothRotatorState = rotState, bluetoothRotatorAddress = rotAddr,
                            bluetoothRotatorFormat = rotFmt, bluetoothFrequencyState = freqState,
                            bluetoothFrequencyAddress = freqAddr, bluetoothFrequencyFormat = freqFmt
                        )
                    )
                )
            }
        )
    }
    if (dialogs.radioControl) {
        RadioControlDialog(
            initialSettings = uiState.radioControlSettings,
            onDismiss = { dialogs.radioControl = false },
            onSave = { onAction(SettingsAction.UpdateRadioControl(it)) }
        )
    }

    // URLs for top bar
    val uriHandler = LocalUriHandler.current
    val appUrl = stringResource(R.string.prefs_app_url)
    val donateUrl = stringResource(R.string.prefs_donate_url)
    val fdroidTitle = stringResource(R.string.prefs_fdroid_title)
    val fdroidUrl = stringResource(R.string.prefs_fdroid_url)
    val gitHubTitle = stringResource(R.string.prefs_github_title)
    val gitHubUrl = stringResource(R.string.prefs_github_url)
    val licenseUrl = stringResource(R.string.prefs_license_url)
    val privacyUrl = stringResource(R.string.prefs_privacy_url)
    val safeOpenUri: (String) -> Unit = { url ->
        try { uriHandler.openUri(url) } catch (_: Exception) {}
    }

    ScreenColumn(
        topBar = { isVerticalLayout ->
            if (isVerticalLayout) {
                TopBar {
                    TopCard(
                        onClick = { safeOpenUri(appUrl) },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryIconCard(onClick = { safeOpenUri(donateUrl) }, resId = R.drawable.ic_pound)
                }
                TopBar {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(fdroidUrl) },
                            resId = R.drawable.ic_fdroid,
                            text = fdroidTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            } else {
                TopBar {
                    PrimaryIconCard(onClick = { safeOpenUri(donateUrl) }, resId = R.drawable.ic_pound)
                    TopCard(
                        onClick = { safeOpenUri(appUrl) },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(fdroidUrl) },
                            resId = R.drawable.ic_fdroid,
                            text = fdroidTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            }
        }
    ) { _ ->
        val isVerticalLayout = isVerticalLayout()
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isVerticalLayout) 1 else 2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
        ) {
            item {
                LocationCard(
                    settings = uiState.positionSettings,
                    setGpsPos = permissions.launchLocation,
                    showPosDialog = { dialogs.position = true },
                    showLocDialog = { dialogs.locator = true },
                    dismissPosMessage = { onAction(SettingsAction.DismissPosMessages) },
                    onAction = onAction
                )
            }
            item {
                DataCard(
                    settings = uiState.dataSettings,
                    updateFromWeb = { onAction(SettingsAction.UpdateFromWeb) },
                    clearAllData = { onAction(SettingsAction.ClearAllData) },
                    showDataSourcesDialog = { dialogs.dataSources = true }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutputCard(
                    onNetworkClick = permissions.launchNetwork,
                    onBluetoothClick = permissions.launchBluetooth,
                    onRadioControlClick = { dialogs.radioControl = true }
                )
            }
            item { OtherCard(uiState.otherSettings, onAction) }
            item { WavelogCard(uiState.otherSettings, onAction) }
            item {
                UiSettingsCard(
                    hiddenScreens = uiState.otherSettings.hiddenScreens,
                    screenOrder = uiState.otherSettings.screenOrder,
                    subMenuOrder = uiState.otherSettings.subMenuOrder,
                    onToggle = { name -> onAction(SettingsAction.ToggleScreen(name)) },
                    onReorder = { order -> onAction(SettingsAction.ReorderScreens(order)) },
                    onResetOrder = { onAction(SettingsAction.ResetMenuOrder) },
                    onUpdateMenu = { main, sub -> onAction(SettingsAction.UpdateMenuOrder(main, sub)) }
                )
            }
            item { CardCredits() }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LocationCardPreview() = MainTheme {
    val stationPos = GeoPos(0.0, 0.0, 0.0, "IO91vl", 0L)
    val settings = PositionSettings(true, stationPos, 0)
    LocationCard(settings = settings, setGpsPos = {}, showPosDialog = {}, {}, {}) {}
}

@Composable
private fun LocationCard(
    settings: PositionSettings,
    setGpsPos: () -> Unit,
    showPosDialog: () -> Unit,
    showLocDialog: () -> Unit,
    dismissPosMessage: () -> Unit,
    onAction: (SettingsAction) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_loc_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.stationPos.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.loc_lat_text, settings.stationPos.latitude))
                Text(text = stringResource(R.string.loc_lon_text, settings.stationPos.longitude))
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.loc_qth_text, settings.stationPos.qthLocator))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                CardButton(
                    onClick = setGpsPos,
                    text = stringResource(id = R.string.prefs_loc_gps_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showPosDialog,
                    text = stringResource(id = R.string.prefs_loc_input_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showLocDialog,
                    text = stringResource(id = R.string.prefs_loc_qth_title),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    if (settings.messageResId != 0) {
        val errorString = stringResource(id = settings.messageResId)
        LaunchedEffect(key1 = settings.messageResId) {
            onAction(SettingsAction.ShowToast(errorString))
            dismissPosMessage()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DataCardPreview() = MainTheme {
    val settings = DataSettings(true, 5000, 2500, 0L)
    DataCard(settings = settings, updateFromWeb = {}, clearAllData = {}, showDataSourcesDialog = {})
}

@Composable
private fun DataCard(
    settings: DataSettings,
    updateFromWeb: () -> Unit,
    clearAllData: () -> Unit,
    showDataSourcesDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_data_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.prefs_data_entries, settings.entriesTotal))
                Text(text = stringResource(R.string.prefs_data_radios, settings.radiosTotal))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = updateFromWeb,
                    text = stringResource(id = R.string.prefs_data_update),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = showDataSourcesDialog,
                    text = stringResource(id = R.string.prefs_data_import),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = clearAllData,
                    text = stringResource(id = R.string.prefs_data_clear),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OutputCardPreview() = MainTheme { OutputCard({}, {}, {}) }

@Composable
private fun OutputCard(
    onNetworkClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onRadioControlClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_data_output_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onNetworkClick,
                    text = stringResource(id = R.string.prefs_net_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onBluetoothClick,
                    text = stringResource(id = R.string.prefs_bt_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onRadioControlClick,
                    text = stringResource(id = R.string.prefs_cat_output),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtherCardPreview() = MainTheme {
    val values = OtherSettings(
        stateOfAutoUpdate = true,
        stateOfSensors = true,
        stateOfSweep = true,
        stateOfUtc = false,
        stateOfLightTheme = false,
        stateOfNightMode = false,
        shouldSeeWarning = false,
        shouldSeeWhatsNew = false
    )
    OtherCard(settings = values) {}
}

@Composable
private fun OtherCard(settings: OtherSettings, onAction: (SettingsAction) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_other_title),
                color = MaterialTheme.colorScheme.primary
            )
            SwitchRow(R.string.prefs_other_switch_utc, settings.stateOfUtc) {
                onAction(SettingsAction.ToggleUtc(it))
            }
            SwitchRow(R.string.prefs_other_switch_update, settings.stateOfAutoUpdate) {
                onAction(SettingsAction.ToggleUpdate(it))
            }
            SwitchRow(R.string.prefs_other_switch_sweep, settings.stateOfSweep) {
                onAction(SettingsAction.ToggleSweep(it))
            }
            SwitchRow(R.string.prefs_other_switch_sensors, settings.stateOfSensors) {
                onAction(SettingsAction.ToggleSensor(it))
            }
            SwitchRow(R.string.prefs_other_switch_night_mode, settings.stateOfNightMode) {
                onAction(SettingsAction.ToggleNightMode(it))
            }
        }
    }
}

/**
 * UI 设置卡片: 控制底部菜单栏各页面显示/隐藏。
 * 顺序固定(卫星/过境/雷达/匹配/漫游/地图/设置), 关闭后其余自动靠拢;
 * "设置"页固定显示(防止失去设置入口)。
 */
/** WaveLog 日志服务器设置卡片(4.5.2): 照用户截图布局(深色卡片/标签左输入右/按钮行) */
@Composable
private fun WavelogCard(
    settings: OtherSettings,
    onAction: (SettingsAction) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_wavelog_title),
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = settings.wavelogUrl,
                onValueChange = {
                    onAction(SettingsAction.UpdateWavelogSettings(it, settings.wavelogApiKey, settings.wavelogStationId, settings.wavelogAutoUpload))
                },
                label = { Text(stringResource(id = R.string.prefs_wavelog_url_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.wavelogApiKey,
                onValueChange = {
                    onAction(SettingsAction.UpdateWavelogSettings(settings.wavelogUrl, it, settings.wavelogStationId, settings.wavelogAutoUpload))
                },
                label = { Text(stringResource(id = R.string.prefs_wavelog_key_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.wavelogStationId,
                onValueChange = {
                    onAction(SettingsAction.UpdateWavelogSettings(settings.wavelogUrl, settings.wavelogApiKey, it, settings.wavelogAutoUpload))
                },
                label = { Text(stringResource(id = R.string.prefs_wavelog_station_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow(R.string.prefs_wavelog_auto, settings.wavelogAutoUpload) {
                onAction(SettingsAction.UpdateWavelogSettings(settings.wavelogUrl, settings.wavelogApiKey, settings.wavelogStationId, it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(
                    onClick = { onAction(SettingsAction.TestWavelogConnection) },
                    text = stringResource(id = R.string.prefs_wavelog_test),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = { onAction(SettingsAction.UploadWavelogQueue) },
                    text = stringResource(id = R.string.prefs_wavelog_upload),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun UiSettingsCard(
    hiddenScreens: List<String>,
    screenOrder: List<String>,
    subMenuOrder: List<String>,
    onToggle: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onResetOrder: () -> Unit,
    onUpdateMenu: (List<String>, List<String>) -> Unit
) {
    val screens = listOf(
        R.string.nav_sat to "Satellites",
        R.string.nav_pass to "Passes",
        R.string.nav_radar to "Radar",
        R.string.nav_mutual to "Mutual",
        R.string.nav_roaming to "Roaming",
        R.string.nav_cw to "CwDecode",
        R.string.nav_log to "WavelogLog",
        R.string.nav_amsat to "AMSAT",
        R.string.nav_map to "Map",
        R.string.nav_prefs to "Settings"
    ) // name 必须与 Screen.screenId 一致 (R8 安全)
    // 主菜单项: 排除更多菜单项, Settings 固定最后
    val mainItems = remember(screens, screenOrder, subMenuOrder) {
        val sub = subMenuOrder.ifEmpty { defaultSubMenuOrder }
        screens.map { it.second }
            .filter { it !in sub }
            .sortedBy { name ->
                screenOrder.indexOf(name).let { idx ->
                    if (idx != -1) idx else defaultScreenOrder.indexOf(name).let {
                        if (it != -1) it else Int.MAX_VALUE
                    }
                }
            }
            .sortedWith(compareBy { if (it == "Settings") 1 else 0 })
    }
    // 更多菜单项
    val subItems = remember(screens, subMenuOrder) {
        val sub = subMenuOrder.ifEmpty { defaultSubMenuOrder }
        sub.filter { s -> screens.any { (_, name) -> name == s } }
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_ui_title),
                color = MaterialTheme.colorScheme.primary
            )
            screens.forEach { (labelRes, name) ->
                if (name == "Settings") {
                    // 设置页固定显示, 开关禁用
                    SwitchRow(labelRes, true, null)
                } else {
                    SwitchRow(labelRes, name !in hiddenScreens) {
                        onToggle(name)
                    }
                }
            }
            Text(
                text = stringResource(id = R.string.prefs_ui_order_title),
                color = MaterialTheme.colorScheme.primary
            )
            // 主菜单区(底部栏, 最多 5 项, 设置锁定最后)
            Text(
                text = stringResource(id = R.string.prefs_ui_main_title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DragOrderList(
                items = mainItems,
                labels = screens,
                locked = setOf("Settings"),
                moveLabel = stringResource(id = R.string.prefs_ui_move_out),
                moveEnabled = { name -> name != "Settings" },
                onMove = { name ->
                    onUpdateMenu(
                        mainItems.filter { it != name },
                        (subMenuOrder.ifEmpty { defaultSubMenuOrder } + name).distinct()
                    )
                },
                onReorder = { main -> onUpdateMenu(main, subMenuOrder) }
            )
            // 更多菜单区(进「更多」的页面)
            Text(
                text = stringResource(id = R.string.prefs_ui_sub_title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DragOrderList(
                items = subItems,
                labels = screens,
                moveLabel = stringResource(id = R.string.prefs_ui_move_back),
                // 按钮常显; 主菜单满 5 时自动让位(最后一个非"设置"项移入更多, 交换)
                moveEnabled = { true },
                onMove = { name ->
                    val mainWithoutSettings = mainItems.filter { it != "Settings" }
                    val needSwap = mainWithoutSettings.size >= 5
                    val evicted = if (needSwap) mainWithoutSettings.last() else null
                    val newMain = if (needSwap) {
                        (mainWithoutSettings.dropLast(1) + name).distinct()
                    } else {
                        (mainWithoutSettings + name).distinct()
                    }
                    val newSub = (subItems.filter { it != name } +
                        if (evicted != null) listOf(evicted) else emptyList()).distinct()
                    onUpdateMenu(newMain, newSub)
                },
                onReorder = { sub -> onUpdateMenu(screenOrder, sub) }
            )
            TextButton(onClick = onResetOrder) {
                Text(text = stringResource(id = R.string.prefs_ui_order_reset))
            }
        }
    }
}

/** 页面顺序拖拽列表: 每行右侧抓手, 按住上下拖动实时换位, 松手持久化 */
@Composable
private fun DragOrderList(
    items: List<String>,
    labels: List<Pair<Int, String>>,
    locked: Set<String> = emptySet(),
    moveLabel: String? = null,
    moveEnabled: (String) -> Boolean = { true },
    onMove: ((String) -> Unit)? = null,
    onReorder: (List<String>) -> Unit
) {
    val itemHeight = 48.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val listState = rememberLazyListState()
    val localItems = remember(items) { mutableStateListOf<String>().apply { addAll(items) } }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    // 固定高度: 嵌套在 LazyVerticalGrid 内必须给有界高度, 否则无限高度约束崩溃
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight * localItems.size)
    ) {
        itemsIndexed(localItems, key = { _, name -> name }) { index, name ->
            val isDragging = draggingIndex == index
            val isLocked = name in locked
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .animateItem()
            ) {
                Text(
                    text = stringResource(id = labels.first { it.second == name }.first),
                    modifier = Modifier.weight(1f)
                )
                if (moveLabel != null && onMove != null && moveEnabled(name)) {
                    TextButton(onClick = { onMove(name) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(moveLabel, fontSize = 11.sp)
                    }
                }
                if (isLocked) {
                    // 锁定行(设置页): 无抓手, 固定最后
                    Text(
                        text = stringResource(id = R.string.prefs_ui_order_locked),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .padding(20.dp)
                            .pointerInput(name) {
                                detectDragGestures(
                                    onDragStart = { draggingIndex = localItems.indexOf(name) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount.y
                                        val currentIndex = draggingIndex
                                        val target = (currentIndex + (dragOffset / itemHeightPx).roundToInt())
                                            .coerceIn(0, localItems.size - 1)
                                        if (target != currentIndex) {
                                            localItems.add(target, localItems.removeAt(currentIndex))
                                            dragOffset += (currentIndex - target) * itemHeightPx
                                            draggingIndex = target
                                        }
                                    },
                                    onDragEnd = {
                                        onReorder(localItems.toList())
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    }
                                )
                            }
                    ) {
                        // 小圆点手柄(触控区 padding 20dp 居中 -> 8dp 圆点)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(labelResId: Int, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(id = labelResId))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun formatUpdateTime(updateTime: Long): String {
    val timePattern = stringResource(id = R.string.prefs_updated_time)
    val placeholder = stringResource(id = R.string.pass_time_placeholder)
    val updateDate = remember(updateTime) {
        if (updateTime != 0L) {
            SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(updateTime))
        } else {
            placeholder
        }
    }
    return stringResource(id = R.string.prefs_updated_title, updateDate)
}

@Composable
private fun UpdateIndicator(isUpdating: Boolean, modifier: Modifier = Modifier) = if (isUpdating) {
    LinearProgressIndicator(modifier = modifier.padding(start = 6.dp))
} else {
    LinearProgressIndicator(
        progress = { 0f },
        drawStopIndicator = {},
        modifier = modifier.padding(start = 6.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun CardCreditsPreview() = MainTheme { CardCredits() }

@Composable
private fun CardCredits(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_outro_title),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_thanks)
            )
            // 感谢列表与保修声明之间保留约两行间距 (用户要求)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(id = R.string.prefs_outro_license),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TopCard(onClick: () -> Unit, modifier: Modifier = Modifier, version: String) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(
                painter = painterResource(id = R.drawable.ic_satellites),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.prefs_app_title, version),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
        }
    }
}

@Composable
private fun BotCard(onClick: () -> Unit, resId: Int, text: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = resId), contentDescription = null)
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

// region Dialog visibility state holder

@Stable
private class DialogVisibility {
    var position by mutableStateOf(false)
    var locator by mutableStateOf(false)
    var dataSources by mutableStateOf(false)
    var network by mutableStateOf(false)
    var bluetooth by mutableStateOf(false)
    var radioControl by mutableStateOf(false)
}

@Composable
private fun rememberDialogVisibility(): DialogVisibility {
    return rememberSaveable(saver = run {
        androidx.compose.runtime.saveable.Saver(
            save = {
                listOf(it.position, it.locator, it.dataSources, it.network, it.bluetooth, it.radioControl)
            },
            restore = {
                DialogVisibility().apply {
                    position = it[0]; locator = it[1]; dataSources = it[2]
                    network = it[3]; bluetooth = it[4]; radioControl = it[5]
                }
            }
        )
    }) { DialogVisibility() }
}

// endregion

// region Permission launchers holder

@Stable
private class SettingsPermissions(
    val launchLocation: () -> Unit,
    val launchTleImport: () -> Unit,
    val launchTransceiverImport: () -> Unit,
    val launchBluetooth: () -> Unit,
    val launchNetwork: () -> Unit,
    val launchCustomSourcesPermission: () -> Unit
)

@Composable
private fun rememberSettingsPermissions(
    sendAction: (SettingsAction) -> Unit,
    onBluetoothGranted: () -> Unit,
    onNetworkGranted: () -> Unit,
    onCustomSourcesPermissionGranted: () -> Unit,
    onCustomSourcesPermissionDenied: () -> Unit
): SettingsPermissions {
    val locationError = stringResource(R.string.prefs_loc_gps_error)
    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) sendAction(SettingsAction.SetGpsPosition)
        else sendAction(SettingsAction.ShowToast(locationError))
    }

    val satellitesImportError = stringResource(R.string.prefs_data_import_satellites_error)
    val transceiversImportError = stringResource(R.string.prefs_data_import_transceivers_error)

    val tleRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTLEFromFile(it.toString(), satellitesImportError)) }
    }

    val transceiversRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTransceiversFromFile(it.toString(), transceiversImportError)) }
    }

    val bluetoothError = stringResource(R.string.prefs_bt_perm_error)
    val bluetoothPerm = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH_CONNECT
    val bluetoothRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onBluetoothGranted()
        else sendAction(SettingsAction.ShowToast(bluetoothError))
    }

    val networkError = stringResource(R.string.prefs_net_perm_error)
    val networkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onNetworkGranted()
        else sendAction(SettingsAction.ShowToast(networkError))
    }

    val customSourcesRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onCustomSourcesPermissionGranted()
        } else {
            onCustomSourcesPermissionDenied()
            sendAction(SettingsAction.ShowToast(networkError))
        }
    }

    return remember {
        SettingsPermissions(
            launchLocation = {
                locationRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            },
            launchTleImport = { tleRequest.launch("*/*") },
            launchTransceiverImport = { transceiversRequest.launch("*/*") },
            launchBluetooth = { bluetoothRequest.launch(bluetoothPerm) },
            launchNetwork = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    networkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onNetworkGranted()
                }
            },
            launchCustomSourcesPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    customSourcesRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onCustomSourcesPermissionGranted()
                }
            }
        )
    }
}

// endregion
