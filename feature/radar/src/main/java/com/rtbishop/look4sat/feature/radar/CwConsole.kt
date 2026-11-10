package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.repository.CwMessageTemplate
import com.rtbishop.look4sat.core.domain.repository.CwTemplateException
import com.rtbishop.look4sat.core.domain.repository.CwTemplateValues
import com.rtbishop.look4sat.core.domain.repository.CwTransmitPresets
import com.rtbishop.look4sat.core.domain.repository.CwTransmitText
import com.rtbishop.look4sat.core.domain.repository.NativeCwOptions
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator as DopplerFrequencyCalculatorUtil
import com.rtbishop.look4sat.core.domain.utility.toDegrees

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CwConsole(
    state: RadarState,
    logViewModel: LogViewModel,
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit,
    onFixGrid: (List<String>) -> Unit,
    connectRadios: () -> Unit = {}
) {
    val log by logViewModel.uiState.collectAsStateWithLifecycle()
    val control = state.radioControl
    val remote = control.remoteRadio
    val caps = remote?.capabilities
    val draft = state.cwConsole.draft
    val minWpm = caps?.minimumWpm ?: 10
    val maxWpm = (caps?.maximumWpm ?: 45).coerceAtLeast(minWpm)
    val wpm = (remote?.nativeCwOptions?.wpm ?: remote?.nativeCwWpm ?: 20).coerceIn(minWpm, maxWpm)
    val available = control.transmitOwner == RadioTransmitOwner.NONE && (remote?.txSource ?: 0) == 0
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val stepPrefs = remember(context) {
        context.getSharedPreferences("cw_console", android.content.Context.MODE_PRIVATE)
    }
    var frequencyStepHz by remember(context) {
        mutableStateOf(stepPrefs.getLong("frequency_step_hz", 1_000L)
            .takeIf { saved -> CW_CALCULATOR_FREQUENCY_STEPS.any { it.first == saved } } ?: 1_000L)
    }
    val errorLabel: (Throwable) -> String = { failure ->
        if (failure is CwTemplateException) {
            val field = when (failure.variable) {
                "CALL" -> context.getString(R.string.cw_console_their_call)
                "MYCALL" -> context.getString(R.string.cw_console_my_call)
                "RST" -> context.getString(R.string.cw_console_report)
                "GRID" -> context.getString(R.string.cw_console_grid_field)
                else -> failure.variable
            }
            context.getString(if (failure.missing) R.string.cw_console_missing_variable else R.string.cw_console_unknown_variable,
                if (failure.missing) field else failure.variable)
        } else failure.message ?: context.getString(R.string.cw_console_invalid_message)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var editingPreset by rememberSaveable { mutableIntStateOf(-1) }
    var presetText by rememberSaveable { mutableStateOf("") }
    var presetError by remember { mutableStateOf<String?>(null) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    fun edit(value: CwConsoleDraft) { onAction(RadarAction.SetCwConsoleDraft(value)); error = null }
    val close = { onAction(RadarAction.CloseCwConsole) }
    val myCallsign = draft.myCallsign ?: log.ownCallsign
    val logTarget = remember(state.currentPass, state.transceivers.transmitters,
        state.transceivers.selectedUuid, state.calculatorTxHz, state.calculatorRxHz) {
        state.logRecordTarget()
    }
    val cwLogOverrides = LogRecordOverrides(myCallsign, draft.sentReport, mode = "CW")
    // A roaming/multi-grid station may list several grids; use the first grid in spoken CW.
    val grid = log.stationGrid.split(',', ';', ' ').firstOrNull(String::isNotBlank).orEmpty()
    val values = CwTemplateValues(log.callsignInput, myCallsign, draft.sentReport, grid)

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.rc_l4s_cw), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = close) {
                            Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.radar_back))
                        }
                    },
                    actions = {
                        val connected = control.txPanel.isConnected || control.rxPanel.isConnected
                        TextButton(
                            onClick = { if (connected) onAction(RadarAction.DisconnectRadios) else connectRadios() },
                            modifier = Modifier.height(40.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(stringResource(if (connected) R.string.rc_panel_disconnect else R.string.rc_panel_connect), maxLines = 1) }
                        TextButton(
                            onClick = { onAction(RadarAction.ToggleTracking) },
                            enabled = control.isTracking || !control.frequencyEditingLocked,
                            modifier = Modifier.height(40.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(stringResource(if (control.isTracking) R.string.rc_panel_stop else R.string.rc_panel_track), maxLines = 1) }
                    }
                )
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        CwConsoleHeader(state, log.callsignInput, myCallsign, draft.sentReport, grid,
                            onTheirCall = logViewModel::updateCallsign,
                            onMyCall = { edit(draft.copy(myCallsign = it.uppercase(java.util.Locale.ROOT))) },
                            onReport = { edit(draft.copy(sentReport = it.filter(Char::isDigit).take(3))) })
                    }
                    item {
                        BoxWithConstraints {
                            val columns = cwConsolePresetColumns(maxWidth.value, configuration.fontScale)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                (0 until 4 step columns).forEach { start ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        (start until start + columns).forEach { index ->
                                            val preset = control.cwPresets.getOrElse(index) { "" }
                                            Surface(shape = MaterialTheme.shapes.medium,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                                modifier = Modifier.weight(1f).heightIn(min = 56.dp).combinedClickable(
                                                    enabled = available, role = Role.Button,
                                                    onLongClickLabel = stringResource(R.string.rc_l4s_edit_preset, index + 1),
                                                    onClick = {
                                                        runCatching { CwMessageTemplate.expand(preset, values) }
                                                            .onSuccess { edit(draft.copy(message = it)) }
                                                            .onFailure { error = errorLabel(it) }
                                                    }, onLongClick = { editingPreset = index; presetText = preset; presetError = null })) {
                                                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("F${index + 1}", color = MaterialTheme.colorScheme.primary)
                                                    Text(preset.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(value = draft.message, onValueChange = { edit(draft.copy(message = it.take(4096))) },
                            enabled = available, label = { Text(stringResource(R.string.cw_console_message)) },
                            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().testTag("cwConsoleMessage"))
                        remote?.let { Text(stringResource(R.string.rc_l4s_progress, it.cwPosition, it.cwLength, it.cwState.name)) }
                        FlowRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching { CwTransmitText.chunks(draft.message, caps?.maxCwText ?: 1) }
                                    .onSuccess { onAction(RadarAction.SendCw(draft.message, wpm)) }
                                    .onFailure { error = errorLabel(it) }
                            }, enabled = control.canSendL4sCw() && draft.message.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.rc_l4s_send))
                            }
                            OutlinedButton(onClick = { onAction(RadarAction.StopRemoteTx) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.rc_l4s_stop))
                            }
                            Button(
                                onClick = {
                                    logViewModel.record(logTarget.satName, "CW", logTarget.txHz, logTarget.rxHz,
                                        log.stationGrid, logTarget.passWindow, cwLogOverrides)
                                },
                                enabled = !log.recordSaving && log.callsignInput.isNotBlank() && logTarget.satName.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("cwConsoleLogButton")
                            ) { Text("Log") }
                            if (caps?.cwOptions == true) OutlinedButton(onClick = { showOptions = true },
                                enabled = !control.frequencyEditingLocked && remote?.nativeCwOptions != null,
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.rc_cw_options))
                            }
                        }
                        val message = error ?: remote?.error ?: control.errorMessage
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (message == null && available && !control.canSendL4sCw()) {
                            val reason = when {
                                remote?.connected != true -> R.string.cw_console_disconnected
                                remote?.nativeMenuOpen == true -> R.string.rc_l4s_menu_open
                                !control.isReady -> R.string.rc_l4s_need_tracking
                                control.txPanel.mode != "CW" -> R.string.rc_l4s_need_cw_mode
                                caps?.cwText != true -> R.string.rc_l4s_no_cw
                                else -> R.string.rc_l4s_busy
                            }
                            Text(stringResource(reason), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item { HorizontalDivider(); Text(stringResource(R.string.radar_cw_decoder), color = MaterialTheme.colorScheme.primary) }
                    item { SharedCwDecoderPanel(state.cw, onAction, requestMicPermission, logViewModel::updateCallsign) }
                    item {
                        // Both calculators start with the same uncorrected transponder entry.
                        val linearTransponders = remember(state.transceivers.nominalTransmitters) {
                            state.transceivers.nominalTransmitters.filter(DopplerFrequencyCalculatorUtil::isNamedLinearTransponder)
                                .let(DopplerFrequencyCalculatorUtil::deduplicateTransponders)
                        }
                        val selectedTransponder = remember(linearTransponders, state.transceivers.selectedUuid) {
                            linearTransponders.firstOrNull { it.uuid == state.transceivers.selectedUuid }
                                ?: linearTransponders.firstOrNull()
                        }
                        selectedTransponder?.let { transponder ->
                            LaunchedEffect(transponder.uuid) {
                                onAction(RadarAction.CalculatorTransponderChanged(transponder.catnum))
                            }
                            HorizontalDivider()
                            Text(stringResource(R.string.radar_tab_calculator), color = MaterialTheme.colorScheme.primary)
                            DopplerFrequencyCalculator(
                                transponder = transponder,
                                orbitalPos = state.orbitalPos,
                                offsetKHz = state.calculatorOffsetKHz,
                                onOffsetChange = { onAction(RadarAction.ChangeCalculatorOffset(it)) },
                                onFrequencyChange = { tx, rx -> onAction(RadarAction.UpdateCalculatorFrequency(tx, rx)) },
                                onTuningChange = { if (!control.frequencyEditingLocked && (control.txPanel.isConnected || control.rxPanel.isConnected)) onAction(RadarAction.TuneCalculator(it)) },
                                initialTxBaseHz = control.txBaseFrequencyHz.takeIf {
                                    transponder.uuid == control.selectedTransponderUuid },
                                reference = control.calculatorReferenceFor(transponder.uuid,
                                    DopplerFrequencyCalculatorUtil.parseOffsetHz(state.calculatorOffsetKHz)),
                                editingEnabled = !control.frequencyEditingLocked || transponder.uuid != control.selectedTransponderUuid ||
                                    (!control.txPanel.isConnected && !control.rxPanel.isConnected),
                                displayNominalFrequencies = true,
                                frequencyStepHz = frequencyStepHz,
                                onFrequencyStepChange = { selectedStep ->
                                    frequencyStepHz = selectedStep
                                    stepPrefs.edit().putLong("frequency_step_hz", selectedStep).apply()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item { HorizontalDivider(); Text(stringResource(R.string.radar_tab_logs), color = MaterialTheme.colorScheme.primary) }
                    item {
                        LogPage(state, logViewModel, onFixGrid,
                            modifier = Modifier.fillMaxWidth().height(maxOf(320, configuration.screenHeightDp / 2).dp),
                            overrides = cwLogOverrides, showCallsignInput = false, showRecordButton = false)
                    }
                }
            }
        }
        if (editingPreset in 0..3) {
            val dismiss = { editingPreset = -1; presetError = null }
            BackHandler(onBack = dismiss)
            CwPresetEditor(index = editingPreset, text = presetText, error = presetError,
                onText = { presetText = it.take(4096); presetError = null },
                onDismiss = dismiss, onAccept = {
                    runCatching { CwTransmitPresets.normalize(presetText) }
                        .onSuccess { onAction(RadarAction.SaveCwPreset(editingPreset, it)); dismiss() }
                        .onFailure { presetError = errorLabel(it) }
                })
        }
        if (showOptions) NativeCwOptionsDialog(remote?.nativeCwOptions ?: NativeCwOptions(),
            enabled = !control.frequencyEditingLocked, onDismiss = { showOptions = false },
            macroStatus = remote?.handsetMacroStatus,
            onSync = if (caps?.handsetMacros == true) ({
                val index = control.cwPresets.indexOfFirst(CwMessageTemplate::hasVariables)
                if (index >= 0) error = context.getString(R.string.cw_console_sync_plain_text, index + 1)
                else onAction(RadarAction.SyncHandsetCwPresets)
            }) else null,
            syncError = error ?: remote?.error ?: control.errorMessage,
            onCancelSync = { onAction(RadarAction.CancelHandsetCwSync) },
            onApply = { onAction(RadarAction.SetNativeCwOptions(it)); showOptions = false })
        }
    }
}

@Composable
private fun CwPresetEditor(index: Int, text: String, error: String?, onText: (String) -> Unit,
    onDismiss: () -> Unit, onAccept: () -> Unit) {
    // Keep the editor in this window; the native decoder host must remain attached.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.64f))
        .clickable(onClick = onDismiss).safeDrawingPadding().imePadding().padding(16.dp),
        contentAlignment = Alignment.Center) {
        Surface(onClick = {}, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rc_l4s_edit_preset, index + 1),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(value = text, onValueChange = onText,
                    modifier = Modifier.fillMaxWidth().testTag("cwPresetText"),
                    label = { Text(stringResource(R.string.cw_console_message)) }, maxLines = 5)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CwMessageTemplate.variables.forEach { variable ->
                        val label = when (variable) {
                            "CALL" -> R.string.cw_console_their_call
                            "MYCALL" -> R.string.cw_console_my_call
                            "RST" -> R.string.cw_console_report
                            else -> R.string.cw_console_grid_field
                        }
                        TextButton(onClick = { onText((text + " {$variable}").trim()) }) {
                            Text("${stringResource(label)} {$variable}")
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                    TextButton(onClick = onAccept) { Text(stringResource(R.string.btn_accept)) }
                }
            }
        }
    }
}

@Composable
private fun CwConsoleHeader(
    state: RadarState, theirCall: String, myCall: String, report: String, grid: String,
    onTheirCall: (String) -> Unit, onMyCall: (String) -> Unit, onReport: (String) -> Unit
) {
    val fontScale = LocalConfiguration.current.fontScale
    val satellite: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = Color.Transparent
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.currentPass?.name.orEmpty(), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium)
                Text("${if (state.isTimeAos) "AOS" else "LOS"} ${state.currentTime}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val radar: @Composable () -> Unit = {
        Column(modifier = Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            state.orbitalPos?.let { position ->
                Text(stringResource(R.string.cw_console_azimuth, position.azimuth.toDegrees()),
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.cw_console_elevation, position.elevation.toDegrees()),
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                RadarViewCompose(item = position, items = state.satTrack, azimElev = state.orientationValues,
                    shouldShowSweep = state.shouldShowSweep, shouldUseCompass = state.shouldUseCompass,
                    sunPosition = state.sunPosition, moonPosition = state.moonPosition,
                    modifier = Modifier.padding(top = 4.dp).size(128.dp), compactPassPreview = true)
            }
        }
    }
    val contacts: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = theirCall, onValueChange = onTheirCall,
                label = { Text(stringResource(R.string.cw_console_their_call)) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            val own: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(value = myCall, onValueChange = onMyCall,
                    label = { Text(stringResource(R.string.cw_console_my_call)) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium, modifier = modifier)
            }
            val rst: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(value = report, onValueChange = onReport,
                    label = { Text(stringResource(R.string.cw_console_report), style = MaterialTheme.typography.labelSmall) },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium, modifier = modifier)
            }
            BoxWithConstraints {
                if (maxWidth >= 190.dp && fontScale <= 1.15f) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    own(Modifier.weight(1.5f)); rst(Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    own(Modifier.fillMaxWidth()); rst(Modifier.fillMaxWidth())
                }
            }
            if (grid.isNotBlank()) Text(stringResource(R.string.cw_console_grid, grid), style = MaterialTheme.typography.bodySmall)
        }
    }
    BoxWithConstraints {
        if (cwConsoleHeaderStacked(maxWidth.value, fontScale)) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { satellite() }
                radar()
            }
            contacts()
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { satellite(); contacts() }
            radar()
        }
    }
}
