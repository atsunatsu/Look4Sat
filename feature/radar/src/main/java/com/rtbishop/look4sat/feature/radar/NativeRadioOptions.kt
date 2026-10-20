package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.repository.NativeCwOptions
import com.rtbishop.look4sat.core.domain.repository.NativeRadioDiagnostics
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlin.math.roundToInt

@Composable
internal fun NativeCwOptionsDialog(options: NativeCwOptions, enabled: Boolean,
    onDismiss: () -> Unit, onApply: (NativeCwOptions) -> Unit) {
    var draft by remember { mutableStateOf(options) }
    SharedDialog(title=stringResource(R.string.rc_cw_options),onDismissRequest=onDismiss,onCancel=onDismiss,
        onAccept={if(enabled && draft.valid())onApply(draft)}) { padding ->
        Column(Modifier.fillMaxWidth().heightIn(max=(LocalConfiguration.current.screenHeightDp*0.6f).dp)
            .verticalScroll(rememberScrollState()).padding(padding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            CwOptionSlider(stringResource(R.string.rc_l4s_wpm,draft.wpm),draft.wpm,10..45,enabled=enabled) {draft=draft.copy(wpm=it)}
            CwOptionSlider(stringResource(R.string.rc_cw_pitch,draft.pitch*10),draft.pitch,45..120,5,enabled) {draft=draft.copy(pitch=it)}
            CwOptionSlider(stringResource(R.string.rc_cw_volume,draft.volume),draft.volume,0..6,enabled=enabled) {draft=draft.copy(volume=it)}
            CwOptionSlider(stringResource(R.string.rc_cw_hang,draft.hang*10),draft.hang,1..200,enabled=enabled) {draft=draft.copy(hang=it)}
            val keyer=listOf("Iambic A","Iambic B","Ultimatic","Bug")[draft.keyer]
            CwOptionSlider(stringResource(R.string.rc_cw_keyer,keyer),draft.keyer,0..3,enabled=enabled) {draft=draft.copy(keyer=it)}
            CwOptionSlider(stringResource(R.string.rc_cw_spacing,if(draft.spacing==0)stringResource(R.string.rc_cw_spacing_off) else "${draft.spacing} WPM"),
                if(draft.spacing==0)4 else draft.spacing,4..44,enabled=enabled) {draft=draft.copy(spacing=if(it==4)0 else it)}
            Text(stringResource(R.string.rc_cw_save_on_radio))
        }
    }
}

@Composable
private fun CwOptionSlider(label: String, value: Int, range: IntRange, step: Int=1, enabled: Boolean,
    onChange: (Int) -> Unit) {
    Text(label)
    Slider(value=value.toFloat(),valueRange=range.first.toFloat()..range.last.toFloat(),
        steps=((range.last-range.first)/step-1).coerceAtLeast(0),enabled=enabled,
        onValueChange={onChange((range.first+((it-range.first)/step).roundToInt()*step).coerceIn(range.first,range.last))})
}

@Composable
internal fun NativeDiagnosticsDialog(value: NativeRadioDiagnostics?,onDismiss: () -> Unit,onRefresh: () -> Unit) {
    SharedDialog(title=stringResource(R.string.rc_native_diagnostics),onDismissRequest=onDismiss,onCancel=onDismiss) { padding ->
        Column(Modifier.fillMaxWidth().heightIn(max=(LocalConfiguration.current.screenHeightDp*0.6f).dp)
            .verticalScroll(rememberScrollState()).padding(padding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(value?.summary()?.replace(" ","\n") ?: "—")
            OutlinedButton(onClick=onRefresh,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rc_native_refresh))}
        }
    }
}
