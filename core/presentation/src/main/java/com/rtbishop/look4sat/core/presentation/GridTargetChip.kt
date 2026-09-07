package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Format a Maidenhead locator for display. Per IARU convention and the user's
 * preference: field letters (1-2) and square digits (3-4) uppercase, sub-square
 * letters (5-6 of a 6/8-char locator) lowercase, e.g. "OL62" / "OL62kl".
 */
fun formatGridDisplay(grid: String): String? {
    val g = grid.trim().uppercase()
    if (g.length !in listOf(4, 6, 8) || g.any { !it.isLetterOrDigit() }) return null
    if (g.length == 4) return g
    if (g.length == 6) return g.take(4) + g.drop(4).lowercase()
    return g.take(4) + g.drop(4).take(2).lowercase() + g.drop(6)
}

/**
 * Top-bar chip for the mutual-match screen: navigation needle (rotated by the
 * great-circle bearing to the target grid, 0 deg = north), grid locator and
 * distance. Shows a dimmed placeholder when no target grid is entered.
 */
@Composable
fun GridTargetChip(
    grid: String?,
    distanceKm: Double?,
    bearingDeg: Double?,
    modifier: Modifier = Modifier
) {
    val configured = grid != null && distanceKm != null && bearingDeg != null
    val accent = MaterialTheme.colorScheme.tertiaryContainer
    val placeholder = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .height(36.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (configured) 0.9f else 0.45f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp)
    ) {
        NavigationNeedle(
            bearingDeg = bearingDeg ?: 0.0,
            color = if (configured) accent else placeholder,
            dimmed = !configured,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = grid?.let { formatGridDisplay(it) } ?: "----",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (configured) accent else placeholder,
            letterSpacing = 1.sp
        )
        Text(
            text = distanceKm?.let { "${"%,.0f".format(it)} km" } ?: "-- km",
            fontSize = 14.sp,
            color = if (configured) Color.White else placeholder,
            modifier = Modifier
                .background(
                    color = if (configured) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Map-style navigation needle: slim triangular pointer whose tip points at
 * [bearingDeg] (0 = north/up, clockwise positive).
 */
@Composable
private fun NavigationNeedle(
    bearingDeg: Double,
    color: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        if (dimmed) {
            drawCircle(color = color, radius = r * 0.92f, style = Stroke(width = 1.5f))
        }
        rotate(degrees = bearingDeg.toFloat(), pivot = Offset(cx, cy)) {
            val path = Path().apply {
                moveTo(cx, cy - r * 0.9f)          // tip (north before rotation)
                lineTo(cx - r * 0.42f, cy + r * 0.55f)
                lineTo(cx, cy + r * 0.28f)
                lineTo(cx + r * 0.42f, cy + r * 0.55f)
                close()
            }
            drawPath(path, color = color)
        }
    }
}

/** Great-circle distance in km between two positions. */
fun greatCircleDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val rad = Math.PI / 180.0
    val dLat = (lat2 - lat1) * rad
    val dLon = (lon2 - lon1) * rad
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * rad) * cos(lat2 * rad) * sin(dLon / 2) * sin(dLon / 2)
    return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

/** Initial great-circle bearing in degrees (0 = north, clockwise). */
fun greatCircleBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val rad = Math.PI / 180.0
    val dLon = (lon2 - lon1) * rad
    val y = sin(dLon) * cos(lat2 * rad)
    val x = cos(lat1 * rad) * sin(lat2 * rad) -
        sin(lat1 * rad) * cos(lat2 * rad) * cos(dLon)
    return (Math.atan2(y, x) / rad + 360.0) % 360.0
}
