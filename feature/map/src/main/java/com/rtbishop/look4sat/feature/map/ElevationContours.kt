/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.rtbishop.look4sat.core.domain.predict.DEG2RAD
import com.rtbishop.look4sat.core.domain.predict.EARTH_RADIUS
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.predict.RAD2DEG
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Each input row has its own distinct color; the same palette colors its dashed contour. */
internal val ELEVATION_CONTOUR_COLORS = intArrayOf(
    Color.rgb(255, 112, 67), Color.rgb(66, 165, 245),
    Color.rgb(102, 187, 106), Color.rgb(171, 71, 188),
    Color.rgb(255, 202, 40), Color.rgb(38, 198, 218),
    Color.rgb(236, 64, 122), Color.rgb(189, 189, 189)
)

/**
 * Geographic locus where a satellite is seen at [elevationDeg] above the local horizon.
 * With r = Earth radius, R = r + satellite altitude, the ground central angle is
 * acos((r / R) * cos(elevation)) - elevation. Zero degrees is the footprint boundary.
 * All coordinates use the SAME timestamp/sub-satellite point as the map's footprint.
 */
internal fun elevationContourPoints(position: OrbitalPos, elevationDeg: Double): List<GeoPos> {
    if (!elevationDeg.isFinite() || elevationDeg !in 0.0..90.0 || position.altitude <= 0.0) {
        return emptyList()
    }
    val elevation = elevationDeg * DEG2RAD
    val radius = acos((EARTH_RADIUS / (EARTH_RADIUS + position.altitude) * cos(elevation)).coerceIn(-1.0, 1.0)) -
        elevation
    val sinLat = sin(position.latitude)
    val cosLat = cos(position.latitude)
    val cosRadius = cos(radius)
    val sinRadius = sin(radius)
    return (0..360).map { azimuth ->
        val bearing = azimuth * DEG2RAD
        val lat = asin((sinLat * cosRadius + cosLat * sinRadius * cos(bearing)).coerceIn(-1.0, 1.0))
        val lon = position.longitude + atan2(
            sin(bearing) * sinRadius * cosLat,
            cosRadius - sinLat * sin(lat)
        )
        GeoPos(lat * RAD2DEG, normalizeContourLongitude(lon * RAD2DEG))
    }
}

private fun normalizeContourLongitude(longitude: Double): Double =
    ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

/**
 * Reuses the existing osmdroid line objects while the user scrubs the map timeline.
 * Like the ordinary footprint, contours are split at ±180° and copied into adjacent
 * world turns so the visible ring does not jump across the map near the date line.
 */
internal class ElevationContourOverlay : FolderOverlay() {
    private val linePool = ArrayList<Polyline>()

    fun update(position: OrbitalPos?, elevations: List<Double>, density: Float) {
        items.clear()
        if (position == null || elevations.isEmpty()) return
        var lineIndex = 0
        elevations.take(ELEVATION_CONTOUR_COLORS.size).forEachIndexed { colorIndex, elevation ->
            val points = elevationContourPoints(position, elevation)
            if (points.isEmpty()) return@forEachIndexed
            splitGroundTrackAtDateLine(points).filter { it.size >= 2 }.forEach { arc ->
                val turns = if (arc.any { abs(it.longitude) > 176.0 }) intArrayOf(-1, 0, 1)
                else intArrayOf(0)
                for (turn in turns) {
                    while (linePool.size <= lineIndex) linePool.add(Polyline())
                    val line = linePool[lineIndex++]
                    line.outlinePaint.apply {
                        color = ELEVATION_CONTOUR_COLORS[colorIndex]
                        strokeWidth = 2.5f * density
                        style = Paint.Style.STROKE
                        pathEffect = DashPathEffect(floatArrayOf(9f * density, 7f * density), 0f)
                    }
                    line.setPoints(arc.map { GeoPoint(it.latitude, it.longitude + turn * 360.0) })
                    add(line)
                }
            }
        }
    }
}

/** On-map readout adjacent to the local station marker, also updated when time is scrubbed. */
internal class StationElevationOverlay : Overlay() {
    var station: GeoPos? = null
    var elevationDeg: Double? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
    }
    private val bounds = RectF()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val point = station ?: return
        val elevation = elevationDeg?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val density = mapView.context.resources.displayMetrics.scaledDensity
        labelPaint.textSize = 13f * density
        val pixel = mapView.projection.toPixels(GeoPoint(point.latitude, point.longitude), null)
        val text = String.format(Locale.US, "%.1f°", elevation)
        val x = pixel.x + 12f * density
        val y = pixel.y - 15f * density
        val pad = 5f * density
        val fm = labelPaint.fontMetrics
        bounds.set(x - pad, y + fm.ascent - pad, x + labelPaint.measureText(text) + pad, y + fm.descent + pad)
        canvas.drawRoundRect(bounds, 5f * density, 5f * density, backgroundPaint)
        canvas.drawText(text, x, y, labelPaint)
    }
}
