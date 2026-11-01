package com.rtbishop.look4sat.feature.mutual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** One above/below-threshold section; crossings are interpolated between samples. */
internal data class ThresholdStrokeSegment(
    val start: Offset,
    val end: Offset,
    val solid: Boolean
)

/** Never change which mutual passes qualify: only segment their visual strokes. */
internal fun thresholdStrokeSegments(
    positions: List<Offset>,
    elevations: List<Double>,
    minElevation: Double,
    horizonOnly: Boolean = false
): List<ThresholdStrokeSegment> {
    if (positions.size != elevations.size || positions.size < 2) return emptyList()
    val result = ArrayList<ThresholdStrokeSegment>()
    for (i in 1 until positions.size) {
        val a = elevations[i - 1]
        val b = elevations[i]
        if (!a.isFinite() || !b.isFinite() || (horizonOnly && (a <= 0.0 || b <= 0.0))) continue
        val start = positions[i - 1]
        val end = positions[i]
        val solidAtStart = a > minElevation
        val solidAtEnd = b > minElevation
        if (solidAtStart == solidAtEnd) {
            result.add(ThresholdStrokeSegment(start, end, solidAtStart))
        } else {
            val fraction = ((minElevation - a) / (b - a)).toFloat().coerceIn(0f, 1f)
            val crossing = Offset(
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction
            )
            result.add(ThresholdStrokeSegment(start, crossing, solidAtStart))
            result.add(ThresholdStrokeSegment(crossing, end, solidAtEnd))
        }
    }
    return result
}

/** Merge adjacent strokes before applying the dash effect (short per-sample dashes
 * otherwise restart on every segment and look indistinguishable from solid).
 */
internal fun DrawScope.drawElevationThresholdTrack(
    positions: List<Offset>,
    elevations: List<Double>,
    minElevation: Double,
    color: Color,
    strokeWidth: Float,
    horizonOnly: Boolean = false
) {
    val segments = thresholdStrokeSegments(positions, elevations, minElevation, horizonOnly)
    val solid = Path()
    val dashed = Path()
    var previous: ThresholdStrokeSegment? = null
    for (segment in segments) {
        val path = if (segment.solid) solid else dashed
        if (previous?.solid != segment.solid || previous?.end != segment.start) {
            path.moveTo(segment.start.x, segment.start.y)
        }
        path.lineTo(segment.end.x, segment.end.y)
        previous = segment
    }
    if (!dashed.isEmpty) {
        drawPath(
            dashed, color,
            style = Stroke(strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        )
    }
    if (!solid.isEmpty) drawPath(solid, color, style = Stroke(strokeWidth))
}
