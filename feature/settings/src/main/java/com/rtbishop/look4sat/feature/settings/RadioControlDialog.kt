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
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.RadioProtocolFamily
import com.rtbishop.look4sat.core.domain.model.radioModelDescriptor
import com.rtbishop.look4sat.core.domain.model.parseRadioTcpEndpoint
import com.rtbishop.look4sat.core.domain.model.supportedRadioBaudRates
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RadioControlDialog(
    initialSettings: RadioControlSettings,
    onDismiss: () -> Unit,
    onSave: (RadioControlSettings) -> Unit,
    frequencyLocked:Boolean=false,
    onOpenProfiles:()->Unit={},
    diagnosticCount:Int=0,
    onExportDiagnostics:()->Unit={},
    onClearDiagnostics:()->Unit={}
) {
    val context    = androidx.compose.ui.platform.LocalContext.current
    val padding    = LocalSpacing.current.large
    val enabled    = rememberSaveable { mutableStateOf(initialSettings.enabled) }
    val radioModel = rememberSaveable { mutableStateOf(initialSettings.radioModel) }
    val initialIsIcom = radioModelDescriptor(initialSettings.radioModel).protocolFamily ==
        RadioProtocolFamily.ICOM_CIV
    val initialBaudRates = radioBaudRates(initialSettings.radioModel)
    val splitMode  = rememberSaveable { mutableStateOf(initialSettings.splitMode && initialIsIcom) }
    val duplexMode = rememberSaveable { mutableStateOf(initialSettings.duplexMode) }
    val catTransport = rememberSaveable { mutableStateOf(initialSettings.catTransport) }
    val tcpProtocol = rememberSaveable { mutableStateOf(initialSettings.tcpProtocol) }
    val txAddress  = rememberSaveable { mutableStateOf(initialSettings.txRadioAddress) }
    val rxAddress  = rememberSaveable { mutableStateOf(initialSettings.rxRadioAddress) }
    val initialTxName = initialSettings.txRadioName.takeUnless {
        initialSettings.catTransport == RadioControlSettings.TRANSPORT_TCP
    }.orEmpty()
    val initialRxName = initialSettings.rxRadioName.takeUnless {
        initialSettings.catTransport == RadioControlSettings.TRANSPORT_TCP
    }.orEmpty()
    val txName     = rememberSaveable { mutableStateOf(initialTxName) }
    val rxName     = rememberSaveable { mutableStateOf(initialRxName) }
    val baudRate   = rememberSaveable {
        mutableIntStateOf(initialSettings.baudRate.takeIf { it in initialBaudRates } ?: initialBaudRates.first())
    }
    val dialSettleMillis = rememberSaveable {
        mutableStateOf(initialSettings.dialSettleMillis.toString())
    }
    val linearDialDeadbandHz = rememberSaveable {
        mutableStateOf(initialSettings.linearDialDeadbandHz.toString())
    }
    val fmDialDeadbandHz = rememberSaveable {
        mutableStateOf(initialSettings.fmDialDeadbandHz.toString())
    }
    val l4sDopplerStepHz = rememberSaveable { mutableStateOf(initialSettings.l4sDopplerStepHz.toString()) }
    val l4sCorrectionStepHz = rememberSaveable { mutableStateOf(initialSettings.l4sCorrectionEditStepHz.toString()) }
    val n76BluetoothAudio = rememberSaveable { mutableStateOf(initialSettings.n76BluetoothAudio) }
    var n76Options by remember { mutableStateOf(initialSettings.n76Options) }
    var showN76Options by rememberSaveable { mutableStateOf(false) }
    val sharedBusDelayMillis = rememberSaveable {
        mutableStateOf(initialSettings.sharedBusCommandDelayMillis.toString())
    }
    val initialCivAddress = initialSettings.civAddress
        ?: defaultCivAddress(initialSettings.radioModel)
    val civAddress = rememberSaveable {
        mutableStateOf(initialCivAddress?.let { "0x%02X".format(it) }.orEmpty())
    }
    val txAddressError = rememberSaveable { mutableStateOf(false) }
    val rxAddressError = rememberSaveable { mutableStateOf(false) }
    val civAddressError = rememberSaveable { mutableStateOf(false) }
    val txAddressByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialSettings.txRadioAddress)
    }
    val rxAddressByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialSettings.rxRadioAddress)
    }
    val txNameByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialTxName)
    }
    val rxNameByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialRxName)
    }
    val selectingFor = rememberSaveable { mutableStateOf("") } // "tx", "rx", or ""
    val bluetoothDevicesRevision = remember { mutableIntStateOf(0) }
    val usbDevicesRevision = remember { mutableIntStateOf(0) }
    val txUsbError = remember { mutableStateOf<UsbSelectionError?>(null) }
    val rxUsbError = remember { mutableStateOf<UsbSelectionError?>(null) }
    val usbPermissionDenied = remember { mutableStateOf(false) }
    val pendingUsbSelection = remember { mutableStateOf<PendingUsbSelection?>(null) }
    val usbManager = remember(context) { context.getSystemService(UsbManager::class.java) }
    val usbPermissionAction = remember(context.packageName) {
        "${context.packageName}.USB_CAT_PERMISSION"
    }
    val usbSerialDeviceFormat = stringResource(R.string.rc_usb_cdc_device)
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        bluetoothDevicesRevision.intValue++
    }

    val applyUsbSelection: (PendingUsbSelection) -> Unit = { selection ->
        if (selection.target == "tx") {
            txAddress.value = selection.address
            txName.value = selection.name
            txUsbError.value = null
        } else {
            rxAddress.value = selection.address
            rxName.value = selection.name
            rxUsbError.value = null
        }
        usbPermissionDenied.value = false
        selectingFor.value = ""
    }

    DisposableEffect(context, usbPermissionAction) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    usbPermissionAction -> {
                        usbDevicesRevision.intValue++
                        val selection = pendingUsbSelection.value
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && selection != null) {
                            applyUsbSelection(selection)
                        } else if (!granted) {
                            usbPermissionDenied.value = true
                        }
                        pendingUsbSelection.value = null
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> usbDevicesRevision.intValue++
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val selectDevice: (String) -> Unit = { target ->
        selectingFor.value = target
        usbPermissionDenied.value = false
        if (
            catTransport.value == RadioControlSettings.TRANSPORT_BLUETOOTH &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    val modelDescriptor = radioModelDescriptor(radioModel.value)
    val isIcom = modelDescriptor.protocolFamily == RadioProtocolFamily.ICOM_CIV
    val isL4s = modelDescriptor.protocolFamily == RadioProtocolFamily.L4S
    val isN76 = modelDescriptor.protocolFamily == RadioProtocolFamily.VERO_GAIA
    val isFtx1 = radioModel.value == RadioControlSettings.MODEL_YAESU_FTX1
    LaunchedEffect(catTransport.value) {
        val models=com.rtbishop.look4sat.core.domain.model.RadioModelCatalog.modelsForTransport(catTransport.value)
        if (radioModel.value !in models) {
            radioModel.value=models.first()
            baudRate.intValue=radioBaudRates(radioModel.value).first()
            defaultCivAddress(radioModel.value)?.let { civAddress.value="0x%02X".format(it) }
            splitMode.value=false
            txAddress.value=""; rxAddress.value=""; txName.value=""; rxName.value=""
        }
    }
    val supportsSatelliteMode = modelDescriptor.capabilities.satelliteMode &&
        !(catTransport.value == RadioControlSettings.TRANSPORT_TCP &&
            tcpProtocol.value == RadioControlSettings.TCP_PROTOCOL_HAMLIB)
    val isSingleRadio = modelDescriptor.capabilities.singleRadioSplit && (splitMode.value || isL4s || isN76)
    val requiredStopBits = modelDescriptor.serialStopBits

    val baudRates = radioBaudRates(radioModel.value)

    val pairedDevices: List<RadioDeviceUiEntry> = remember(
        catTransport.value,
        radioModel.value,
        usbSerialDeviceFormat,
        bluetoothDevicesRevision.intValue,
        usbDevicesRevision.intValue
    ) {
        buildList {
            try {
                if (catTransport.value == RadioControlSettings.TRANSPORT_USB) {
                    usbManager.deviceList.values.forEach { device ->
                        device.usbSerialPorts().forEach { port ->
                            val product = runCatching { device.productName }.getOrNull() ?: "USB"
                            val identity = "%04X:%04X".format(device.vendorId, device.productId)
                            val name = usbSerialDeviceFormat.format(
                                product,
                                port.driverName,
                                port.dataInterfaceId,
                                identity
                            )
                            val selector = listOf(
                                device.deviceId,
                                port.controlInterfaceId,
                                port.dataInterfaceId,
                                device.vendorId,
                                device.productId
                            ).joinToString(":")
                            add(
                                RadioDeviceUiEntry(
                                    name = name,
                                    address = selector,
                                    usbDeviceId = device.deviceId,
                                    hasUsbPermission = usbManager.hasPermission(device),
                                    isSupported = port.supportsLineConfiguration(
                                        interfaceCount = device.interfaceCount,
                                        stopBits = requiredStopBits
                                    )
                                )
                            )
                        }
                    }
                    return@buildList
                }
                if (catTransport.value != RadioControlSettings.TRANSPORT_BLUETOOTH) return@buildList
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@buildList
                }
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.bondedDevices?.forEach {
                    add(RadioDeviceUiEntry(it.name ?: "Unknown", it.address ?: ""))
                }
            } catch (_: SecurityException) { }
        }
    }

    val onAccept = accept@{
        txAddressError.value = false
        rxAddressError.value = false
        civAddressError.value = false
        txUsbError.value = null
        rxUsbError.value = null
        if (enabled.value && catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
            val bothBlank = !isSingleRadio && txAddress.value.isBlank() && rxAddress.value.isBlank()
            txAddressError.value = (isSingleRadio && txAddress.value.isBlank()) || bothBlank ||
                (txAddress.value.isNotBlank() && parseRadioTcpEndpoint(txAddress.value) == null)
            rxAddressError.value = !isSingleRadio && (bothBlank ||
                (rxAddress.value.isNotBlank() && parseRadioTcpEndpoint(rxAddress.value) == null))
        }
        if (enabled.value && catTransport.value == RadioControlSettings.TRANSPORT_USB) {
            val bothBlank = !isSingleRadio && txAddress.value.isBlank() && rxAddress.value.isBlank()
            txUsbError.value = when {
                (isSingleRadio && txAddress.value.isBlank()) || bothBlank -> UsbSelectionError.NOT_SELECTED
                txAddress.value.isBlank() -> null
                else -> validateUsbSelection(usbManager, txAddress.value, requiredStopBits)
            }
            rxUsbError.value = when {
                isSingleRadio -> null
                bothBlank -> UsbSelectionError.NOT_SELECTED
                rxAddress.value.isBlank() -> null
                else -> validateUsbSelection(usbManager, rxAddress.value, requiredStopBits)
            }
        }
        val parsedCivAddress = if (isIcom) parseCivAddress(civAddress.value) else null
        civAddressError.value = isIcom &&
            !(catTransport.value == RadioControlSettings.TRANSPORT_TCP &&
                tcpProtocol.value == RadioControlSettings.TCP_PROTOCOL_HAMLIB) &&
            parsedCivAddress == null
        if (
            txAddressError.value || rxAddressError.value || civAddressError.value ||
            txUsbError.value != null || rxUsbError.value != null
        ) return@accept
        val parsedL4sStep=l4sDopplerStepHz.value.toLongOrNull()
        if(isL4s && (parsedL4sStep==null || parsedL4sStep !in 10L..10_000L || parsedL4sStep%10!=0L)) return@accept
        if(frequencyLocked)return@accept
        val correctionStep=l4sCorrectionStepHz.value.toLongOrNull()
        if(isL4s && (correctionStep==null || correctionStep !in 10L..1000L || correctionStep%10!=0L))return@accept
        onSave(
            RadioControlSettings(
                enabled        = enabled.value,
                radioModel     = radioModel.value,
                txRadioAddress = txAddress.value,
                rxRadioAddress = if (isSingleRadio) "" else rxAddress.value,
                txRadioName    = if (
                    catTransport.value == RadioControlSettings.TRANSPORT_TCP
                ) "" else txName.value,
                rxRadioName    = if (
                    isSingleRadio || catTransport.value == RadioControlSettings.TRANSPORT_TCP
                ) "" else rxName.value,
                baudRate       = baudRate.intValue,
                splitMode      = splitMode.value,
                catTransport   = catTransport.value,
                duplexMode     = if (supportsSatelliteMode) {
                    duplexMode.value
                } else {
                    RadioControlSettings.DUPLEX_MODE_SPLIT
                },
                civAddress     = parsedCivAddress,
                tcpProtocol    = tcpProtocol.value,
                dialSettleMillis = dialSettleMillis.value.toLongOrNull() ?: 1_500L,
                linearDialDeadbandHz = linearDialDeadbandHz.value.toLongOrNull() ?: 20L,
                fmDialDeadbandHz = fmDialDeadbandHz.value.toLongOrNull() ?: 200L,
                sharedBusCommandDelayMillis = sharedBusDelayMillis.value.toLongOrNull() ?: 0L,
                l4sDopplerStepHz = parsedL4sStep ?: 250L,
                l4sCorrectionEditStepHz = correctionStep ?: 10L,
                n76BluetoothAudio = n76BluetoothAudio.value,
                n76Options = n76Options
            )
        )
        onDismiss()
    }

    SharedDialog(
        title    = stringResource(R.string.rc_settings_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.82f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = padding)
        ) {

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

            Text(stringResource(R.string.rc_cat_transport), fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioControlSettings.SUPPORTED_TRANSPORTS.filter {
                    if (isN76) it == RadioControlSettings.TRANSPORT_BLUETOOTH
                    else !(isL4s || isFtx1) || it == RadioControlSettings.TRANSPORT_USB
                }.forEach { transport ->
                    FilterChip(
                        selected = catTransport.value == transport,
                        onClick = {
                            txAddressByTransport[catTransport.value] = txAddress.value
                            rxAddressByTransport[catTransport.value] = rxAddress.value
                            txNameByTransport[catTransport.value] = txName.value
                            rxNameByTransport[catTransport.value] = rxName.value
                            catTransport.value = transport
                            txAddress.value = txAddressByTransport[transport].orEmpty()
                            rxAddress.value = rxAddressByTransport[transport].orEmpty()
                            txName.value = txNameByTransport[transport].orEmpty()
                            rxName.value = rxNameByTransport[transport].orEmpty()
                            txAddressError.value = false
                            rxAddressError.value = false
                            txUsbError.value = null
                            rxUsbError.value = null
                            usbPermissionDenied.value = false
                            selectingFor.value = ""
                        },
                        label = { Text(transport, fontSize = 12.sp) },
                        enabled = enabled.value
                    )
                }
            }
            if (catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
                Text(stringResource(R.string.rc_tcp_protocol), fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RadioControlSettings.SUPPORTED_TCP_PROTOCOLS.forEach { protocol ->
                        FilterChip(
                            selected = tcpProtocol.value == protocol,
                            onClick = { tcpProtocol.value = protocol },
                            label = {
                                Text(
                                    stringResource(
                                        if (protocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB) {
                                            R.string.rc_tcp_protocol_hamlib
                                        } else {
                                            R.string.rc_tcp_protocol_raw
                                        }
                                    ),
                                    fontSize = 12.sp
                                )
                            },
                            enabled = enabled.value
                        )
                    }
                }
            }
            run {
            Spacer(modifier = Modifier.height(6.dp))

            // Radio model — FlowRow so chips wrap on small screens
            Text(
                text       = stringResource(R.string.rc_radio_model),
                fontWeight = FontWeight.Medium,
                color      = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                com.rtbishop.look4sat.core.domain.model.RadioModelCatalog.modelsForTransport(catTransport.value).forEach { model ->
                    FilterChip(
                        selected = radioModel.value == model,
                        onClick  = {
                            val oldDefaultAddress = defaultCivAddress(radioModel.value)
                            val currentAddress = parseCivAddress(civAddress.value)
                            val modelChanged = radioModel.value != model
                            radioModel.value = model
                            if (model == RadioControlSettings.MODEL_VERO_N76) splitMode.value = true
                            if (radioModelDescriptor(model).protocolFamily == RadioProtocolFamily.L4S ||
                                model == RadioControlSettings.MODEL_YAESU_FTX1) {
                                splitMode.value = true
                                if (catTransport.value != RadioControlSettings.TRANSPORT_USB) {
                                    txAddressByTransport[catTransport.value] = txAddress.value
                                    txNameByTransport[catTransport.value] = txName.value
                                    catTransport.value = RadioControlSettings.TRANSPORT_USB
                                    txAddress.value = txAddressByTransport[RadioControlSettings.TRANSPORT_USB].orEmpty()
                                    txName.value = txNameByTransport[RadioControlSettings.TRANSPORT_USB].orEmpty()
                                }
                            }
                            val newCapabilities = radioModelDescriptor(model).capabilities
                            if (!newCapabilities.singleRadioSplit) splitMode.value = false
                            if (!newCapabilities.satelliteMode) {
                                duplexMode.value = RadioControlSettings.DUPLEX_MODE_SPLIT
                            }
                            val modelRates = radioBaudRates(model)
                            if ((modelChanged && model in setOf(RadioControlSettings.MODEL_ICOM_IC820,RadioControlSettings.MODEL_YAESU_FTX1)) || baudRate.intValue !in modelRates) {
                                baudRate.intValue = modelRates.first()
                            }
                            if (
                                oldDefaultAddress == null || currentAddress == oldDefaultAddress ||
                                currentAddress == null
                            ) {
                                defaultCivAddress(model)?.let { civAddress.value = "0x%02X".format(it) }
                            }
                            civAddressError.value = false
                            txUsbError.value = null
                            rxUsbError.value = null
                        },
                        label    = { Text(model, fontSize = 12.sp) },
                        enabled  = enabled.value
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (isFtx1) Text(stringResource(R.string.rc_ftx1_usb_hint),style=MaterialTheme.typography.bodySmall)
            if (isN76) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rc_n76_bluetooth_audio), modifier = Modifier.weight(1f))
                    Switch(checked = n76BluetoothAudio.value, onCheckedChange = { n76BluetoothAudio.value = it },
                        enabled = enabled.value && !frequencyLocked)
                }
                OutlinedButton(onClick = { showN76Options = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled.value && !frequencyLocked) {
                    Text(stringResource(R.string.rc_n76_options))
                }
            }
            if (
                isIcom &&
                !(catTransport.value == RadioControlSettings.TRANSPORT_TCP &&
                    tcpProtocol.value == RadioControlSettings.TCP_PROTOCOL_HAMLIB)
            ) {
                OutlinedTextField(
                    value = civAddress.value,
                    onValueChange = {
                        civAddress.value = it
                        civAddressError.value = false
                    },
                    label = { Text(stringResource(R.string.rc_civ_address)) },
                    supportingText = if (civAddressError.value) {
                        { Text(stringResource(R.string.rc_civ_address_error)) }
                    } else null,
                    isError = civAddressError.value,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled.value
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Single-radio duplex control for Icom split or dedicated satellite mode.
            if (radioModel.value == RadioControlSettings.MODEL_ICOM_IC820) {
                Text(
                    text = stringResource(R.string.rc_ic820_limits),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (isL4s) {
                val step=l4sDopplerStepHz.value.toLongOrNull()
                OutlinedTextField(value=l4sDopplerStepHz.value,onValueChange={l4sDopplerStepHz.value=it},
                    label={Text(stringResource(R.string.rc_l4s_step))},
                    isError=step==null || step !in 10L..10_000L || step%10!=0L,
                    enabled=enabled.value && !frequencyLocked,singleLine=true,modifier=Modifier.fillMaxWidth())
                val correctionStep=l4sCorrectionStepHz.value.toLongOrNull()
                OutlinedTextField(value=l4sCorrectionStepHz.value,onValueChange={l4sCorrectionStepHz.value=it},
                    label={Text(stringResource(R.string.rc_l4s_correction_step))},
                    isError=correctionStep==null || correctionStep !in 10L..1000L || correctionStep%10!=0L,
                    enabled=enabled.value && !frequencyLocked,singleLine=true,modifier=Modifier.fillMaxWidth())
                if(frequencyLocked)Text(stringResource(R.string.rc_l4s_edit_locked),color=MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick=onOpenProfiles,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rc_l4s_profiles))}
            }
            if (modelDescriptor.capabilities.singleRadioSplit && !isL4s && !isN76) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.rc_split_mode),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked         = splitMode.value,
                        onCheckedChange = { splitMode.value = it },
                        enabled         = enabled.value
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (isSingleRadio && supportsSatelliteMode) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.rc_satellite_mode),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = duplexMode.value == RadioControlSettings.DUPLEX_MODE_SATELLITE,
                        onCheckedChange = { enabled ->
                            duplexMode.value = if (enabled) {
                                RadioControlSettings.DUPLEX_MODE_SATELLITE
                            } else {
                                RadioControlSettings.DUPLEX_MODE_SPLIT
                            }
                        },
                        enabled = enabled.value
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // TX radio (the sole CAT connection in single-radio duplex mode).
            val txLabel = stringResource(when {
                isL4s -> R.string.rc_l4s_radio
                isSingleRadio -> R.string.rc_single_radio
                else -> R.string.rc_tx_radio
            })
            Text(txLabel, fontWeight = FontWeight.Medium)
            if (catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
                OutlinedTextField(
                    value = txAddress.value,
                    onValueChange = {
                        txAddress.value = it
                        txAddressError.value = false
                    },
                    label = { Text(stringResource(R.string.rc_tx_tcp_address)) },
                    singleLine = true,
                    isError = txAddressError.value,
                    supportingText = if (txAddressError.value) {
                        { Text(stringResource(R.string.rc_tcp_address_error)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled.value
                )
            } else {
                if (txAddress.value.isNotBlank()) {
                    Text("${txName.value} — ${txAddress.value}", fontSize = 13.sp)
                }
                CardButton(
                    onClick  = { selectDevice("tx") },
                    text     = stringResource(if (isSingleRadio) R.string.rc_select_device else R.string.rc_select_tx_device),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            txUsbError.value?.takeIf {
                catTransport.value == RadioControlSettings.TRANSPORT_USB
            }?.let { error ->
                Text(
                    text = stringResource(error.messageResource),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // RX Radio (hidden in split mode — the same radio handles both)
            if (!isSingleRadio) {
                Text(stringResource(R.string.rc_rx_radio), fontWeight = FontWeight.Medium)
                if (catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
                    OutlinedTextField(
                        value = rxAddress.value,
                        onValueChange = {
                            rxAddress.value = it
                            rxAddressError.value = false
                        },
                        label = { Text(stringResource(R.string.rc_rx_tcp_address)) },
                        singleLine = true,
                        isError = rxAddressError.value,
                        supportingText = if (rxAddressError.value) {
                            { Text(stringResource(R.string.rc_tcp_address_error)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled.value
                    )
                } else {
                    if (rxAddress.value.isNotBlank()) {
                        Text("${rxName.value} — ${rxAddress.value}", fontSize = 13.sp)
                    }
                    CardButton(
                        onClick  = { selectDevice("rx") },
                        text     = stringResource(R.string.rc_select_rx_device),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                rxUsbError.value?.takeIf {
                    catTransport.value == RadioControlSettings.TRANSPORT_USB
                }?.let { error ->
                    Text(
                        text = stringResource(error.messageResource),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Paired device picker (inline, shown while selecting)
            Text(stringResource(R.string.control_diagnostics_count, diagnosticCount))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onExportDiagnostics,modifier=Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_diagnostics_export))
                }
                OutlinedButton(onClick=onClearDiagnostics,modifier=Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_diagnostics_clear))
                }
            }
            if (selectingFor.value.isNotBlank()) {
                Text(
                    text       = stringResource(R.string.rc_paired_devices),
                    fontWeight = FontWeight.Medium,
                    color      = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (pairedDevices.isEmpty()) {
                    Text(
                        stringResource(R.string.rc_no_paired_devices),
                        fontSize = 13.sp
                    )
                } else {
                    pairedDevices.forEach { entry ->
                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isSupported) {
                                    if (catTransport.value == RadioControlSettings.TRANSPORT_USB) {
                                        val device = usbManager.deviceList.values.firstOrNull {
                                            it.deviceId == entry.usbDeviceId
                                        }
                                        if (device == null) {
                                            if (selectingFor.value == "tx") {
                                                txUsbError.value = UsbSelectionError.DEVICE_MISSING
                                            } else {
                                                rxUsbError.value = UsbSelectionError.DEVICE_MISSING
                                            }
                                            usbDevicesRevision.intValue++
                                            return@clickable
                                        }
                                        val selection = PendingUsbSelection(
                                            selectingFor.value,
                                            entry.name,
                                            entry.address
                                        )
                                        if (!usbManager.hasPermission(device)) {
                                            pendingUsbSelection.value = selection
                                            usbPermissionDenied.value = false
                                            val permissionIntent = PendingIntent.getBroadcast(
                                                context,
                                                device.deviceId,
                                                Intent(usbPermissionAction)
                                                    .setPackage(context.packageName),
                                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                            )
                                            usbManager.requestPermission(device, permissionIntent)
                                            return@clickable
                                        }
                                        applyUsbSelection(selection)
                                    } else {
                                        if (selectingFor.value == "tx") {
                                            txAddress.value = entry.address
                                            txName.value = entry.name
                                        } else {
                                            rxAddress.value = entry.address
                                            rxName.value = entry.name
                                        }
                                        selectingFor.value = ""
                                    }
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.name,
                                        modifier = Modifier.weight(1f),
                                        color = if (entry.isSupported) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(entry.address, fontSize = 12.sp)
                                }
                                if (!entry.isSupported) {
                                    Text(
                                        stringResource(R.string.rc_usb_port_incompatible),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else if (
                                    catTransport.value == RadioControlSettings.TRANSPORT_USB &&
                                    !entry.hasUsbPermission
                                ) {
                                    Text(
                                        stringResource(R.string.rc_usb_permission_tap),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                if (
                    catTransport.value == RadioControlSettings.TRANSPORT_USB &&
                    usbPermissionDenied.value
                ) {
                    Text(
                        stringResource(R.string.rc_usb_permission_denied),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (catTransport.value != RadioControlSettings.TRANSPORT_TCP && !isN76) {
                // Baud rate belongs to the local serial link. TCP bridges/rigctld own their serial settings.
                Text(stringResource(R.string.rc_baud_rate), fontWeight = FontWeight.Medium)
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
            Text(stringResource(R.string.rc_dial_follow), fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialSettleMillis.value,
                    onValueChange = { dialSettleMillis.value = it },
                    label = { Text(stringResource(R.string.rc_dial_settle_ms)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = sharedBusDelayMillis.value,
                    onValueChange = { sharedBusDelayMillis.value = it },
                    label = { Text(stringResource(R.string.rc_shared_bus_delay_ms)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = linearDialDeadbandHz.value,
                    onValueChange = { linearDialDeadbandHz.value = it },
                    label = { Text(stringResource(R.string.rc_linear_deadband_hz)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fmDialDeadbandHz.value,
                    onValueChange = { fmDialDeadbandHz.value = it },
                    label = { Text(stringResource(R.string.rc_fm_deadband_hz)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
    if (showN76Options) {
        val container = (context.applicationContext as com.rtbishop.look4sat.core.domain.repository.IContainerProvider).getMainContainer()
        val inputs by container.audioHub.inputDevices.collectAsState()
        N76OptionsDialog(n76Options, n76BluetoothAudio.value, inputs,
            onSave = { n76Options = it }, onDismiss = { showN76Options = false })
    }
}

private fun radioBaudRates(model: String): List<Int> = supportedRadioBaudRates(model)

private fun defaultCivAddress(model: String): Int? = radioModelDescriptor(model).defaultCivAddress

private fun parseCivAddress(value: String): Int? {
    val text = value.trim()
    val parsed = if (text.startsWith("0x", ignoreCase = true)) {
        text.substring(2).toIntOrNull(16)
    } else {
        text.toIntOrNull()
    }
    return parsed?.takeIf { it in 0..0xFF }
}

internal data class UsbSerialUiPort(
    val driverName: String,
    val controlInterfaceId: Int,
    val dataInterfaceId: Int,
    val portIndex: Int
) {
    fun supportsLineConfiguration(interfaceCount: Int, stopBits: Int): Boolean {
        val restrictedCp2105Port = driverName == "CP210x" && interfaceCount == 2 && portIndex == 1
        return !restrictedCp2105Port || stopBits != 2
    }
}

internal data class RadioDeviceUiEntry(
    val name: String,
    val address: String,
    val usbDeviceId: Int? = null,
    val hasUsbPermission: Boolean = true,
    val isSupported: Boolean = true
)

private data class PendingUsbSelection(
    val target: String,
    val name: String,
    val address: String
)

private enum class UsbSelectionError(val messageResource: Int) {
    NOT_SELECTED(R.string.rc_usb_device_required),
    DEVICE_MISSING(R.string.rc_usb_device_missing),
    PERMISSION_REQUIRED(R.string.rc_usb_permission_required),
    INCOMPATIBLE(R.string.rc_usb_port_incompatible)
}

private data class UsbSerialUiSelector(
    val deviceId: Int,
    val controlInterfaceId: Int,
    val dataInterfaceId: Int,
    val vendorId: Int?,
    val productId: Int?
)

private fun validateUsbSelection(
    manager: UsbManager,
    value: String,
    stopBits: Int
): UsbSelectionError? {
    val selector = parseUsbSerialUiSelector(value) ?: return UsbSelectionError.DEVICE_MISSING
    val device = resolveUsbSerialUiDevice(manager.deviceList.values, selector)
        ?: return UsbSelectionError.DEVICE_MISSING
    val port = device.usbSerialPorts().firstOrNull {
        it.controlInterfaceId == selector.controlInterfaceId &&
            it.dataInterfaceId == selector.dataInterfaceId
    } ?: return UsbSelectionError.DEVICE_MISSING
    if (!port.supportsLineConfiguration(device.interfaceCount, stopBits)) {
        return UsbSelectionError.INCOMPATIBLE
    }
    return if (manager.hasPermission(device)) null else UsbSelectionError.PERMISSION_REQUIRED
}

private fun parseUsbSerialUiSelector(value: String): UsbSerialUiSelector? {
    val parts = value.split(':')
    if (parts.size != 3 && parts.size != 5) return null
    val deviceId = parts[0].toIntOrNull() ?: return null
    val controlId = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val dataId = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val vendorId = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..0xFFFF }
    val productId = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it in 0..0xFFFF }
    if (parts.size == 5 && (vendorId == null || productId == null)) return null
    return UsbSerialUiSelector(deviceId, controlId, dataId, vendorId, productId)
}

private fun resolveUsbSerialUiDevice(
    devices: Collection<UsbDevice>,
    selector: UsbSerialUiSelector
): UsbDevice? {
    val matchesIdentity: (UsbDevice) -> Boolean = { device ->
        selector.vendorId == null ||
            device.vendorId == selector.vendorId && device.productId == selector.productId
    }
    devices.firstOrNull { it.deviceId == selector.deviceId && matchesIdentity(it) }?.let { return it }
    if (selector.vendorId == null || selector.productId == null) return null
    return devices.filter(matchesIdentity).singleOrNull()
}

internal fun UsbDevice.usbSerialPorts(): List<UsbSerialUiPort> {
    val interfaces = (0 until interfaceCount).map(::getInterface)
    val controls = interfaces.filter {
        it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == 0x02
    }
    val dataInterfaces = interfaces.filter { intf ->
        intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA &&
            (0 until intf.endpointCount).map(intf::getEndpoint).any {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
            } &&
            (0 until intf.endpointCount).map(intf::getEndpoint).any {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
            }
    }
    val cdcPorts = controls.mapNotNull { control ->
        dataInterfaces.firstOrNull { it.id == control.id + 1 }?.let {
            UsbSerialUiPort("CDC-ACM", control.id, it.id, interfaces.indexOf(it))
        }
    }.ifEmpty {
        if (controls.size == 1 && dataInterfaces.size == 1) {
            val data = dataInterfaces.single()
            listOf(UsbSerialUiPort("CDC-ACM", controls.single().id, data.id, interfaces.indexOf(data)))
        } else {
            emptyList()
        }
    }
    if (cdcPorts.isNotEmpty()) return cdcPorts

    val driverName = when (vendorId) {
        0x10C4 -> "CP210x"
        0x0403 -> "FTDI"
        0x1A86, 0x4348 -> "CH34x"
        else -> return emptyList()
    }
    val serialInterfaces = interfaces.mapIndexedNotNull { index, intf ->
        val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
        val hasBulkPair = endpoints.any {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
        } && endpoints.any {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
        }
        if (hasBulkPair) intf to index else null
    }
    val ports = if (driverName == "CH34x") serialInterfaces.takeLast(1) else serialInterfaces
    return ports.map { (intf, index) -> UsbSerialUiPort(driverName, intf.id, intf.id, index) }
}
