/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.settings

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.rotator.RotatorAzimuthRange
import com.rtbishop.look4sat.core.domain.rotator.RotatorConnectionState
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingPhase
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingState
import com.rtbishop.look4sat.core.domain.rotator.RotatorTransport
import com.rtbishop.look4sat.core.domain.rotator.RotatorCommandStatus
import com.rtbishop.look4sat.core.domain.rotator.RotatorFeedbackStatus
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog

@Composable
fun RotatorControlDialog(
    initialSettings: RotatorSettings,
    trackingState: RotatorTrackingState,
    onDismiss: () -> Unit,
    onSave: (RotatorSettings) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTestPoint: (Double, Double) -> Unit,
    onPark: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    var draft by remember { mutableStateOf(initialSettings) }
    var usbRevision by remember { mutableIntStateOf(0) }
    var bluetoothRevision by remember { mutableIntStateOf(0) }
    var pendingUsbSelection by remember { mutableStateOf<RadioDeviceUiEntry?>(null) }
    var usbPermissionDenied by remember { mutableStateOf(false) }
    var pendingNetworkConnect by remember { mutableStateOf(false) }
    var testAzimuth by remember { mutableStateOf("180") }
    var testElevation by remember { mutableStateOf("10") }

    val connected = trackingState.connectionState == RotatorConnectionState.CONNECTED
    val connecting = trackingState.connectionState == RotatorConnectionState.CONNECTING ||
        trackingState.connectionState == RotatorConnectionState.RECONNECTING
    val editable = !connected && !connecting
    val dirty = draft != initialSettings
    val valid = !draft.enabled || draft.isConfigured
    val connectionStatus = stringResource(connectionLabelResource(trackingState.connectionState))
    val bluetoothPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    LaunchedEffect(initialSettings) {
        if (!dirty) draft = initialSettings
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bluetoothRevision++
    }
    val networkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingNetworkConnect) onConnect()
        pendingNetworkConnect = false
    }

    val usbPermissionAction = remember(context.packageName) {
        "${context.packageName}.ROTATOR_USB_PERMISSION"
    }
    DisposableEffect(usbPermissionAction) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != usbPermissionAction) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val pending = pendingUsbSelection
                if (granted && pending != null) {
                    draft = draft.copy(deviceAddress = pending.address)
                    usbPermissionDenied = false
                } else {
                    usbPermissionDenied = true
                }
                pendingUsbSelection = null
                usbRevision++
            }
        }
        val filter = IntentFilter(usbPermissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val devices = remember(draft.transport, bluetoothRevision, usbRevision) {
        availableRotatorDevices(context, usbManager, draft.transport)
    }

    fun selectDevice(entry: RadioDeviceUiEntry) {
        if (draft.transport != RotatorTransport.USB_SERIAL || entry.hasUsbPermission) {
            draft = draft.copy(deviceAddress = entry.address)
            usbPermissionDenied = false
            return
        }
        val deviceId = entry.usbDeviceId ?: return
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return
        pendingUsbSelection = entry
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            deviceId,
            Intent(usbPermissionAction).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun requestConnect() {
        if (dirty || !draft.isConfigured) return
        when (draft.transport) {
            RotatorTransport.BLUETOOTH_SPP -> {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    onConnect()
                }
            }
            RotatorTransport.TCP, RotatorTransport.UDP -> {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pendingNetworkConnect = true
                    networkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onConnect()
                }
            }
            RotatorTransport.USB_SERIAL -> onConnect()
        }
    }

    SharedDialog(
        title = stringResource(R.string.rotator_settings_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = { onSave(draft.normalized()) },
        acceptText = stringResource(R.string.rotator_save),
        acceptEnabled = editable && dirty && valid
    ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = padding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SwitchSettingRow(
                    label = stringResource(R.string.rotator_enable),
                    checked = draft.enabled,
                    enabled = editable,
                    onCheckedChange = { draft = draft.copy(enabled = it) }
                )

                Text(stringResource(R.string.rotator_protocol), fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RotatorProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = draft.protocol == protocol,
                            onClick = {
                                val transport = preferredTransport(protocol, draft.transport)
                                draft = draft.copy(
                                    protocol = protocol,
                                    transport = transport,
                                    port = protocol.defaultPort ?: draft.port,
                                    baudRate = if (draft.protocol != protocol && protocol == RotatorProtocol.SPID_ROT2PROG) 600 else draft.baudRate
                                )
                            },
                            enabled = editable,
                            label = { Text(protocolLabel(protocol)) }
                        )
                    }
                }

                Text(stringResource(R.string.rotator_transport), fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RotatorTransport.entries.filter(draft.protocol::supportsTransport).forEach { transport ->
                        FilterChip(
                            selected = draft.transport == transport,
                            onClick = { draft = draft.copy(transport = transport) },
                            enabled = editable,
                            label = { Text(transportLabel(transport)) }
                        )
                    }
                }

                if (draft.transport == RotatorTransport.TCP || draft.transport == RotatorTransport.UDP) {
                    OutlinedTextField(
                        value = draft.host,
                        onValueChange = { draft = draft.copy(host = it) },
                        label = { Text(stringResource(R.string.rotator_host)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth()
                    )
                    RotatorNumberField(
                        value = draft.port.toString(),
                        label = stringResource(R.string.rotator_port),
                        enabled = editable,
                        integer = true
                    ) { it.toIntOrNull()?.let { value -> draft = draft.copy(port = value) } }
                } else {
                    Text(stringResource(R.string.rotator_device), fontWeight = FontWeight.Medium)
                    if (
                        draft.transport == RotatorTransport.BLUETOOTH_SPP &&
                        !bluetoothPermissionGranted
                    ) {
                        Button(
                            onClick = {
                                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            },
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.rotator_bluetooth_permission))
                        }
                    }
                    if (devices.isEmpty()) {
                        Text(
                            stringResource(R.string.rotator_no_devices),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        devices.forEach { entry ->
                            FilterChip(
                                selected = draft.deviceAddress == entry.address,
                                onClick = { if (entry.isSupported) selectDevice(entry) },
                                enabled = editable && entry.isSupported,
                                label = {
                                    Text(
                                        if (entry.hasUsbPermission) entry.name
                                        else "${entry.name} · ${stringResource(R.string.rotator_usb_permission)}"
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.deviceAddress,
                        onValueChange = { draft = draft.copy(deviceAddress = it) },
                        label = { Text(stringResource(R.string.rotator_device_address)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (usbPermissionDenied) {
                        Text(
                            stringResource(R.string.rotator_usb_permission_denied),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(stringResource(R.string.rotator_baud_rate), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ROTATOR_BAUD_RATES.forEach { baud ->
                            FilterChip(
                                selected = draft.baudRate == baud,
                                onClick = { draft = draft.copy(baudRate = baud) },
                                enabled = editable,
                                label = { Text(baud.toString()) }
                            )
                        }
                    }
                }

                if (draft.protocol == RotatorProtocol.CUSTOM_TEMPLATE) {
                    OutlinedTextField(
                        value = draft.customPointTemplate,
                        onValueChange = { draft = draft.copy(customPointTemplate = it) },
                        label = { Text(stringResource(R.string.rotator_custom_point)) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.customStopTemplate,
                        onValueChange = { draft = draft.copy(customStopTemplate = it) },
                        label = { Text(stringResource(R.string.rotator_custom_stop)) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.customQueryTemplate,
                        onValueChange = { draft = draft.copy(customQueryTemplate = it) },
                        label = { Text(stringResource(R.string.rotator_custom_query)) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.customResponseTemplate,
                        onValueChange = { draft = draft.copy(customResponseTemplate = it.take(256)) },
                        label = { Text(stringResource(R.string.rotator_response_template)) },
                        supportingText = { Text(stringResource(R.string.rotator_response_hint)) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(stringResource(R.string.rotator_pointing), fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RotatorAzimuthRange.entries.forEach { range ->
                        FilterChip(
                            selected = draft.azimuthRange == range,
                            onClick = { draft = draft.copy(azimuthRange = range) },
                            enabled = editable,
                            label = { Text(azimuthRangeLabel(range)) }
                        )
                    }
                }
                RotatorNumberField(
                    value = draft.minimumElevationDegrees.toPlainText(),
                    label = stringResource(R.string.rotator_min_elevation),
                    enabled = editable
                ) { it.toDoubleOrNull()?.let { value -> draft = draft.copy(minimumElevationDegrees = value) } }
                RotatorNumberField(
                    value = draft.prepositionLeadSeconds.toString(),
                    label = stringResource(R.string.rotator_preposition_lead),
                    enabled = editable,
                    integer = true
                ) { it.toIntOrNull()?.let { value -> draft = draft.copy(prepositionLeadSeconds = value) } }
                RotatorNumberField(
                    value = draft.trackingLeadSeconds.toString(),
                    label = stringResource(R.string.rotator_tracking_lead),
                    enabled = editable,
                    integer = true
                ) { it.toIntOrNull()?.let { value -> draft = draft.copy(trackingLeadSeconds = value) } }
                if (draft.azimuthRange == RotatorAzimuthRange.ZERO_TO_450) {
                    RotatorNumberField(
                        value = draft.azimuthLookAheadSeconds.toString(),
                        label = stringResource(R.string.rotator_azimuth_lookahead),
                        enabled = editable,
                        integer = true
                    ) { it.toIntOrNull()?.let { value -> draft = draft.copy(azimuthLookAheadSeconds = value) } }
                }
                RotatorNumberField(
                    value = draft.deadbandDegrees.toPlainText(),
                    label = stringResource(R.string.rotator_deadband),
                    enabled = editable
                ) { it.toDoubleOrNull()?.let { value -> draft = draft.copy(deadbandDegrees = value) } }
                RotatorNumberField(
                    value = draft.updateIntervalMillis.toString(),
                    label = stringResource(R.string.rotator_update_interval),
                    enabled = editable,
                    integer = true
                ) { it.toLongOrNull()?.let { value -> draft = draft.copy(updateIntervalMillis = value) } }
                RotatorNumberField(
                    value = draft.azimuthOffsetDegrees.toPlainText(),
                    label = stringResource(R.string.rotator_azimuth_offset),
                    enabled = editable
                ) { it.toDoubleOrNull()?.let { value -> draft = draft.copy(azimuthOffsetDegrees = value) } }
                RotatorNumberField(
                    value = draft.elevationOffsetDegrees.toPlainText(),
                    label = stringResource(R.string.rotator_elevation_offset),
                    enabled = editable
                ) { it.toDoubleOrNull()?.let { value -> draft = draft.copy(elevationOffsetDegrees = value) } }
                SwitchSettingRow(
                    stringResource(R.string.rotator_flip),
                    draft.flipOverheadPasses,
                    editable
                ) { draft = draft.copy(flipOverheadPasses = it) }
                SwitchSettingRow(
                    stringResource(R.string.rotator_magnetic),
                    draft.magneticCorrection,
                    editable
                ) { draft = draft.copy(magneticCorrection = it) }

                Text(stringResource(R.string.rotator_park), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotatorNumberField(
                        value = draft.parkAzimuthDegrees.toPlainText(),
                        label = stringResource(R.string.rotator_park_azimuth),
                        enabled = editable,
                        modifier = Modifier.weight(1f)
                    ) { it.toDoubleOrNull()?.let { value -> draft = draft.copy(parkAzimuthDegrees = value) } }
                    RotatorNumberField(
                        value = draft.parkElevationDegrees.toPlainText(),
                        label = stringResource(R.string.rotator_park_elevation),
                        enabled = editable,
                        modifier = Modifier.weight(1f)
                    ) { it.toDoubleOrNull()?.let { value -> draft = draft.copy(parkElevationDegrees = value) } }
                }
                SwitchSettingRow(
                    stringResource(R.string.rotator_park_on_los),
                    draft.parkOnLos,
                    editable
                ) { draft = draft.copy(parkOnLos = it) }
                SwitchSettingRow(
                    stringResource(R.string.rotator_park_on_disconnect),
                    draft.parkOnDisconnect,
                    editable
                ) { draft = draft.copy(parkOnDisconnect = it) }

                Text(
                    stringResource(
                        R.string.rotator_status,
                        connectionStatus
                    ),
                    color = if (trackingState.errorMessage == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    stringResource(
                        R.string.rotator_tracking_status,
                        stringResource(trackingLabelResource(trackingState.trackingPhase))
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                trackingState.commandedPosition?.let { position ->
                    Text(
                        stringResource(
                            R.string.rotator_target_position,
                            position.azimuthDegrees,
                            position.elevationDegrees
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(stringResource(R.string.rotator_confirmation_label, stringResource(when(trackingState.commandStatus) {
                    RotatorCommandStatus.IDLE -> R.string.rotator_command_idle
                    RotatorCommandStatus.SENT -> R.string.rotator_command_sent
                    RotatorCommandStatus.ACCEPTED -> R.string.rotator_command_accepted
                    RotatorCommandStatus.FEEDBACK -> R.string.rotator_command_feedback
                    RotatorCommandStatus.AT_TARGET -> R.string.rotator_command_at_target
                    RotatorCommandStatus.UNCONFIRMED -> R.string.rotator_command_unconfirmed
                    RotatorCommandStatus.REJECTED -> R.string.rotator_command_rejected
                    RotatorCommandStatus.TIMED_OUT -> R.string.rotator_command_timeout
                })),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(when(trackingState.feedbackStatus) {
                    RotatorFeedbackStatus.UNKNOWN -> R.string.rotator_feedback_waiting
                    RotatorFeedbackStatus.FRESH -> R.string.rotator_feedback_fresh
                    RotatorFeedbackStatus.STALE -> R.string.rotator_feedback_stale
                    RotatorFeedbackStatus.UNSUPPORTED -> R.string.rotator_feedback_unsupported
                }),color=if(trackingState.feedbackStatus==RotatorFeedbackStatus.STALE)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style=MaterialTheme.typography.bodySmall)
                trackingState.reportedPosition?.let { position ->
                    Text(
                        stringResource(
                            R.string.rotator_reported_position,
                            position.azimuthDegrees,
                            position.elevationDegrees
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                trackingState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (dirty) {
                    Text(
                        stringResource(R.string.rotator_unsaved),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!valid) {
                    Text(
                        stringResource(R.string.rotator_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { if (connected) onDisconnect() else requestConnect() },
                        enabled = !connecting && (connected || !dirty && draft.isConfigured),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(if (connected) R.string.rotator_disconnect else R.string.rotator_connect))
                    }
                    Button(onClick = onStop, enabled = connected, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.rotator_stop))
                    }
                    Button(onClick = onPark, enabled = connected, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.rotator_park_now))
                    }
                }
                RotatorDirectionDial(
                    target = testAzimuth.toDoubleOrNull()?.let { az -> testElevation.toDoubleOrNull()?.let { el ->
                        com.rtbishop.look4sat.core.domain.rotator.RotatorPosition(az,el)
                    } },
                    reported = trackingState.reportedPosition.takeIf { connected && trackingState.feedbackStatus == RotatorFeedbackStatus.FRESH },
                    range = draft.azimuthRange, enabled = connected,
                    onSelect = { testAzimuth = it.azimuthDegrees.toPlainText(); testElevation = it.elevationDegrees.toPlainText() }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotatorNumberField(
                        value = testAzimuth,
                        label = stringResource(R.string.rotator_test_azimuth),
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        onTextChange = { testAzimuth = it }
                    )
                    RotatorNumberField(
                        value = testElevation,
                        label = stringResource(R.string.rotator_test_elevation),
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        onTextChange = { testElevation = it }
                    )
                }
                Button(
                    onClick = {
                        val azimuth = testAzimuth.toDoubleOrNull()
                        val elevation = testElevation.toDoubleOrNull()
                        if (azimuth != null && elevation != null) onTestPoint(azimuth, elevation)
                    },
                    enabled = connected && testAzimuth.toDoubleOrNull()?.let { az ->
                        testElevation.toDoubleOrNull()?.let { el ->
                            com.rtbishop.look4sat.core.domain.rotator.RotatorManualControl.valid(
                                com.rtbishop.look4sat.core.domain.rotator.RotatorPosition(az,el),draft.azimuthRange)
                        }
                    } == true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rotator_test_point))
                }
            }
    }
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RotatorNumberField(
    value: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    integer: Boolean = false,
    onTextChange: ((String) -> Unit)? = null,
    onValueChange: (String) -> Unit = {}
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onTextChange?.invoke(it)
            onValueChange(it)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal
        ),
        singleLine = true,
        enabled = enabled,
        modifier = modifier
    )
}

private fun availableRotatorDevices(
    context: Context,
    usbManager: UsbManager,
    transport: RotatorTransport
): List<RadioDeviceUiEntry> = buildList {
    try {
        when (transport) {
            RotatorTransport.BLUETOOTH_SPP -> {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) return@buildList
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.bondedDevices?.forEach {
                    add(RadioDeviceUiEntry(it.name ?: "Unknown", it.address ?: ""))
                }
            }
            RotatorTransport.USB_SERIAL -> usbManager.deviceList.values.forEach { device ->
                device.usbSerialPorts().forEach { port ->
                    val product = runCatching { device.productName }.getOrNull() ?: "USB"
                    val identity = "%04X:%04X".format(device.vendorId, device.productId)
                    val selector = listOf(
                        device.deviceId,
                        port.controlInterfaceId,
                        port.dataInterfaceId,
                        device.vendorId,
                        device.productId
                    ).joinToString(":")
                    add(
                        RadioDeviceUiEntry(
                            name = "$product · ${port.driverName} #${port.dataInterfaceId} · $identity",
                            address = selector,
                            usbDeviceId = device.deviceId,
                            hasUsbPermission = usbManager.hasPermission(device),
                            isSupported = true
                        )
                    )
                }
            }
            else -> Unit
        }
    } catch (_: SecurityException) {
        // Permission affordance remains visible through the empty-state copy.
    }
}.sortedBy(RadioDeviceUiEntry::name)

private fun preferredTransport(
    protocol: RotatorProtocol,
    current: RotatorTransport
): RotatorTransport = when {
    protocol.supportsTransport(current) -> current
    protocol == RotatorProtocol.PST_ROTATOR -> RotatorTransport.UDP
    protocol == RotatorProtocol.ROTCTLD || protocol == RotatorProtocol.OZ9AAR_URC -> RotatorTransport.TCP
    else -> RotatorTransport.BLUETOOTH_SPP
}

private fun protocolLabel(protocol: RotatorProtocol): String = when (protocol) {
    RotatorProtocol.GS232 -> "Yaesu GS-232A/B"
    RotatorProtocol.EASYCOMM_I -> "EasyComm I"
    RotatorProtocol.EASYCOMM_II -> "EasyComm II"
    RotatorProtocol.EASYCOMM_III -> "EasyComm III"
    RotatorProtocol.SPID_ROT2PROG -> "SPID Rot2Prog"
    RotatorProtocol.SAEBRTRACK -> "SAEBRTrack"
    RotatorProtocol.ROTCTLD -> "Hamlib rotctld"
    RotatorProtocol.PST_ROTATOR -> "PstRotator"
    RotatorProtocol.OZ9AAR_URC -> "OZ9AAR URC"
    RotatorProtocol.CUSTOM_TEMPLATE -> "Custom"
}

private fun transportLabel(transport: RotatorTransport): String = when (transport) {
    RotatorTransport.BLUETOOTH_SPP -> "Bluetooth SPP"
    RotatorTransport.USB_SERIAL -> "USB Serial"
    RotatorTransport.TCP -> "TCP"
    RotatorTransport.UDP -> "UDP"
}

private fun azimuthRangeLabel(range: RotatorAzimuthRange): String = when (range) {
    RotatorAzimuthRange.ZERO_TO_360 -> "0–360°"
    RotatorAzimuthRange.MINUS_180_TO_180 -> "−180…+180°"
    RotatorAzimuthRange.ZERO_TO_450 -> "0–450°"
}

private fun connectionLabelResource(state: RotatorConnectionState): Int = when (state) {
    RotatorConnectionState.DISCONNECTED -> R.string.rotator_state_disconnected
    RotatorConnectionState.CONNECTING -> R.string.rotator_state_connecting
    RotatorConnectionState.CONNECTED -> R.string.rotator_state_connected
    RotatorConnectionState.RECONNECTING -> R.string.rotator_state_reconnecting
    RotatorConnectionState.ERROR -> R.string.rotator_state_error
}

private fun trackingLabelResource(phase: RotatorTrackingPhase): Int = when (phase) {
    RotatorTrackingPhase.IDLE -> R.string.rotator_phase_idle
    RotatorTrackingPhase.HOLDING -> R.string.rotator_phase_holding
    RotatorTrackingPhase.PREPOSITIONING -> R.string.rotator_phase_prepositioning
    RotatorTrackingPhase.TRACKING -> R.string.rotator_phase_tracking
    RotatorTrackingPhase.PARKING -> R.string.rotator_phase_parking
    RotatorTrackingPhase.PARKED -> R.string.rotator_phase_parked
    RotatorTrackingPhase.ERROR -> R.string.rotator_phase_error
}

private fun Double.toPlainText(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

private val ROTATOR_BAUD_RATES = listOf(600, 1_200, 2_400, 4_800, 9_600, 19_200, 38_400, 57_600, 115_200)
