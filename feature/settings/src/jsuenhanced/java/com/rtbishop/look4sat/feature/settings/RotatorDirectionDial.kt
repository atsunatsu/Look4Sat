package com.rtbishop.look4sat.feature.settings

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.rotator.RotatorAzimuthRange
import com.rtbishop.look4sat.core.domain.rotator.RotatorManualControl
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.presentation.R
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun RotatorDirectionDial(
    target: RotatorPosition?, reported: RotatorPosition?, range: RotatorAzimuthRange,
    enabled: Boolean, onSelect: (RotatorPosition) -> Unit
) {
    val select by rememberUpdatedState(onSelect)
    val currentTarget by rememberUpdatedState(target)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.rotator_direction_dial)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description }
            .pointerInput(enabled, range) {
                detectTapGestures { point ->
                    if (enabled) {
                        val radius = minOf(size.width,size.height) / 2f - 20.dp.toPx()
                        if (radius > 0f) RotatorManualControl.fromPlot(
                            ((point.x-size.width/2f)/radius).toDouble(),
                            ((point.y-size.height/2f)/radius).toDouble(), range,
                            currentTarget?.azimuthDegrees ?: 0.0
                        )?.let(select)
                    }
                }
            }) {
            val radius = size.minDimension/2f - 20.dp.toPx()
            if (radius <= 0f) return@Canvas
            val center = center
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = label.toArgb(); textSize = 12.sp.toPx(); textAlign = Paint.Align.CENTER
            }
            for (fraction in listOf(1f,2f/3f,1f/3f)) drawCircle(outline.copy(alpha=0.4f),radius*fraction,center,style=Stroke(1.dp.toPx()))
            drawLine(outline.copy(alpha=0.35f),center-Offset(radius,0f),center+Offset(radius,0f))
            drawLine(outline.copy(alpha=0.35f),center-Offset(0f,radius),center+Offset(0f,radius))
            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText("N",center.x,center.y-radius-6.dp.toPx(),pen)
            canvas.drawText("S",center.x,center.y+radius+15.dp.toPx(),pen)
            canvas.drawText("W",center.x-radius-12.dp.toPx(),center.y+4.dp.toPx(),pen)
            canvas.drawText("E",center.x+radius+12.dp.toPx(),center.y+4.dp.toPx(),pen)
            fun marker(position: RotatorPosition, color: Color, line: Boolean) {
                val flipped = position.elevationDegrees > 90.0
                val az = Math.toRadians(position.azimuthDegrees + if (flipped) 180.0 else 0.0)
                val el = if (flipped) 180.0-position.elevationDegrees else position.elevationDegrees
                val distance = radius*(1.0-el.coerceIn(0.0,90.0)/90.0)
                val point = center + Offset((sin(az)*distance).toFloat(),(-cos(az)*distance).toFloat())
                if (line) drawLine(color,center,point,2.dp.toPx())
                drawCircle(color,5.dp.toPx(),point)
            }
            reported?.let { marker(it,Color(0xFFEF6666),false) }
            target?.let { marker(it,primary.copy(alpha=if(enabled) 1f else 0.4f),true) }
        }
        Text(stringResource(R.string.rotator_dial_legend),style=MaterialTheme.typography.labelSmall,color=label)
    }
}
