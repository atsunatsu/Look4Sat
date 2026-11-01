package com.rtbishop.look4sat.feature.mutual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Sample the original Catmull-Rom-to-cubic-Bezier curve densely so the
 * minimum-elevation dash transition follows the smooth curve rather than
 * turning the whole plot into straight line segments.
 */
internal fun smoothElevationCurve(
    points: List<Offset>,
    elevations: List<Double>,
    stepsPerSegment: Int = 8
): Pair<List<Offset>, List<Double>> {
    if (points.size != elevations.size || points.size < 2) return points to elevations
    require(stepsPerSegment >= 1)
    val smoothPoints = ArrayList<Offset>(1 + (points.size - 1) * stepsPerSegment)
    val smoothElevations = ArrayList<Double>(1 + (points.size - 1) * stepsPerSegment)
    smoothPoints.add(points.first())
    smoothElevations.add(elevations.first())
    for (i in 1 until points.size) {
        val p0 = points.getOrNull(i - 2) ?: points[i - 1]
        val p1 = points[i - 1]
        val p2 = points[i]
        val p3 = points.getOrNull(i + 1) ?: points[i]
        val e0 = elevations.getOrNull(i - 2) ?: elevations[i - 1]
        val e1 = elevations[i - 1]
        val e2 = elevations[i]
        val e3 = elevations.getOrNull(i + 1) ?: elevations[i]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        val ec1 = e1 + (e2 - e0) / 6.0
        val ec2 = e2 - (e3 - e1) / 6.0
        for (step in 1..stepsPerSegment) {
            val t = step.toFloat() / stepsPerSegment
            val u = 1f - t
            fun at(a: Float, b: Float, c: Float, d: Float): Float =
                u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
            val x = at(p1.x, c1.x, c2.x, p2.x)
            val y = at(p1.y, c1.y, c2.y, p2.y)
            val td = t.toDouble()
            val ud = 1.0 - td
            val elevation = ud * ud * ud * e1 + 3.0 * ud * ud * td * ec1 +
                3.0 * ud * td * td * ec2 + td * td * td * e2
            smoothPoints.add(Offset(x, y))
            smoothElevations.add(elevation)
        }
    }
    return smoothPoints to smoothElevations
}

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
