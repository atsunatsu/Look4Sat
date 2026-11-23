package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.presentation.R

@Composable
internal fun UsbPttControls(control: RadioControlSubState, onAction: (RadarAction) -> Unit) {
    val action by rememberUpdatedState(onAction)
    val allowed by rememberUpdatedState(control.isReady && control.pttState == PttState.OFF)
    val keyed by rememberUpdatedState(control.pttState == PttState.ON)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) action(RadarAction.StopRemoteTx)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); action(RadarAction.StopRemoteTx) }
    }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .semantics {
                role = Role.Button
                onClick {
                    if (keyed) action(RadarAction.StopRemoteTx) else if (allowed) action(RadarAction.VoicePtt(true))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (allowed) {
                        down.consume()
                        action(RadarAction.VoicePtt(true))
                        try { waitForUpOrCancellation() } finally { action(RadarAction.VoicePtt(false)) }
                    }
                }
            }.padding(8.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(if (keyed) R.string.rc_usb_ptt_keyed else R.string.rc_usb_ptt_hold))
        }
        OutlinedButton(onClick = { action(RadarAction.StopRemoteTx) }) { Text(stringResource(R.string.rc_l4s_stop)) }
    }
}
