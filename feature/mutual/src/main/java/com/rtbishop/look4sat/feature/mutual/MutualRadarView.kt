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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CIRCLES = 3 // 30°, 60°, 90° rings
private const val STROKE_WIDTH = 2.5f

/**
 * Polar radar chart showing the satellite track as seen from both stations
 * on one plot. Both stations use solid strokes above their own minimum
 * elevation and dashed strokes below it; color distinguishes station identity.
 * Controlled: [progress] (0..1) and [onProgressChange] are owned by the parent
 * so the time cursor can be shared with the elevation curve chart.
 */
@Composable
fun MutualRadarView(
    trackSamples: List<TrackSample>,
    minElevationA: Double = 0.0,
    minElevationB: Double = 0.0,
    progress: Float = 0.5f,
    labelA: String = "Station A",
    labelB: String = "Station B",
    modifier: Modifier = Modifier
) {
    if (trackSamples.isEmpty()) return

    val colorA = MaterialTheme.colorScheme.primary
    val colorB = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    // Current-time samples (both stations' position at the shared cursor)
    val t0 = trackSamples.first().time
    val t1 = trackSamples.last().time
    val selectedTime = t0 + ((t1 - t0) * progress).toLong()
    val visibleSamples = trackSamples.filter { it.time <= selectedTime }
    val currentSample = visibleSamples.lastOrNull() ?: trackSamples.first()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(4.dp)
        ) {
            val radius = size.minDimension / 2f * 0.92f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Grid: elevation rings + azimuth spokes + cardinal labels
            drawRadarGrid(center, radius, gridColor, textColor, measurer)

            // Both tracks remain above the horizon; stroke style changes exactly
            // at each station's minimum-elevation crossing (interpolated).
            drawElevationThresholdTrack(
                trackSamples.map { sph2Cart(center, it.azimuthA, it.elevationA, radius) },
                trackSamples.map { it.elevationA },
                minElevationA, colorA, STROKE_WIDTH, horizonOnly = true
            )
            drawElevationThresholdTrack(
                trackSamples.map { sph2Cart(center, it.azimuthB, it.elevationB, radius) },
                trackSamples.map { it.elevationB },
                minElevationB, colorB, STROKE_WIDTH, horizonOnly = true
            )

            // AOS / LOS markers on both tracks
            val first = trackSamples.first()
            val last = trackSamples.last()
            val aosA = sph2Cart(center, first.azimuthA, first.elevationA, radius)
            val aosB = sph2Cart(center, first.azimuthB, first.elevationB, radius)
            val losA = sph2Cart(center, last.azimuthA, last.elevationA, radius)
            val losB = sph2Cart(center, last.azimuthB, last.elevationB, radius)
            // AOS: hollow circle, LOS: filled circle
            if (first.elevationA > 0f) drawCircle(colorA, 7f, aosA, style = Stroke(2.5f))
            if (first.elevationB > 0f) drawCircle(colorB, 7f, aosB, style = Stroke(2.5f))
            if (last.elevationA > 0f) drawCircle(colorA, 5f, losA)
            if (last.elevationB > 0f) drawCircle(colorB, 5f, losB)

            // Shared time-cursor positions (both stations at selectedTime)
            val cursorA = sph2Cart(center, currentSample.azimuthA, currentSample.elevationA, radius)
            val cursorB = sph2Cart(center, currentSample.azimuthB, currentSample.elevationB, radius)
            drawCircle(colorA, 14f, cursorA, style = Stroke(3f))
            drawCircle(colorA, 6f, cursorA)
            drawCircle(colorB, 14f, cursorB, style = Stroke(3f))
            drawCircle(colorB, 6f, cursorB)

            // Center dot
            drawCircle(gridColor, 4f, center)
        }

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("● $labelA", color = colorA, fontSize = 12.sp)
            Text("● $labelB", color = colorB, fontSize = 12.sp)
        }
    }
}

private fun DrawScope.drawRadarGrid(
    center: Offset,
    radius: Float,
    color: Color,
    textColor: Color,
    measurer: TextMeasurer
) {
    // Elevation rings: 30°, 60°, 90°(center) — outer edge is horizon (0°)
    val step = radius / CIRCLES
    for (i in 0 until CIRCLES) {
        drawCircle(color, radius - step * i, center, style = Stroke(1.5f))
    }
    // Cardinal spokes
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.5f)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.5f)
    // Diagonal spokes (lighter)
    val diag = radius * 0.7071f
    drawLine(
        color.copy(alpha = 0.5f),
        Offset(center.x - diag, center.y - diag), Offset(center.x + diag, center.y + diag), 1f
    )
    drawLine(
        color.copy(alpha = 0.5f),
        Offset(center.x - diag, center.y + diag), Offset(center.x + diag, center.y - diag), 1f
    )

    // Elevation ring labels: 30° on the outer ring, 60° on the middle ring, 90° at center
    // (outer edge is the 0° horizon). Labels sit just above their ring.
    val style = TextStyle(color = textColor, fontSize = 11.sp)
    drawText(measurer, "30°", Offset(center.x + 6f, (center.y - (radius - step)) - 24f), style = style)
    drawText(measurer, "60°", Offset(center.x + 6f, (center.y - (radius - 2 * step)) - 24f), style = style)
    drawText(measurer, "90°", Offset(center.x + 6f, center.y - 18f), style = style)
    // Cardinal labels
    drawText(measurer, "N", Offset(center.x - 8f, center.y - radius - 20f), style = style)
    drawText(measurer, "E", Offset(center.x + radius + 4f, center.y - 10f), style = style)
    drawText(measurer, "S", Offset(center.x - 6f, center.y + radius + 2f), style = style)
    drawText(measurer, "W", Offset(center.x - radius - 24f, center.y - 10f), style = style)
}

/** Normalize an azimuth delta (degrees) into the [-180, 180] range. */
private fun azimuthDelta(deltaDeg: Double): Double {
    var d = deltaDeg % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

/** Convert azimuth (deg, 0=N, clockwise) and elevation (deg) to canvas offset. */
private fun sph2Cart(center: Offset, azimDeg: Double, elevDeg: Double, r: Float): Offset {
    val azimRad = azimDeg * PI / 180.0
    val elevRad = elevDeg * PI / 180.0
    val radius = r * (PI / 2 - elevRad) / (PI / 2)
    return Offset(
        x = center.x + (radius * cos(PI / 2 - azimRad)).toFloat(),
        y = center.y - (radius * sin(PI / 2 - azimRad)).toFloat()
    )
}
