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
package com.rtbishop.look4sat.feature.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Maidenhead locator grid overlay, drawn directly on the map canvas.
 *
 * Zoom levels (map maxZoom = 7):
 *  - zoom < GRID_ZOOM_SUB: 10° x 20° fields with 2-character labels (e.g. "PM")
 *  - zoom >= GRID_ZOOM_SUB: 1° x 2° squares with 4-character labels (e.g. "PM95")
 *
 * All geometry is computed per-frame from the projection, so the overlay
 * stays correct while panning/zooming. Grid lines that cross the antimeridian
 * are drawn in segments clamped to the visible bounding box.
 */
class MaidenheadGridOverlay : Overlay() {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 224, 130)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        style = android.graphics.Paint.Style.FILL
        color = Color.argb(220, 255, 224, 130)
        setShadowLayer(3f, 2f, 2f, Color.BLACK)
    }
    private val workedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = Color.argb(90, 76, 217, 100)
    }

    /** Worked gridsquares (4-char, uppercase) to highlight, e.g. {"OL62", "PM95"}. */
    var workedGrids: Set<String> = emptySet()

    /** The station's own 4-char gridsquare, drawn with a distinct outline. */
    var ownGrid: String? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        val projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        val cellLat = if (zoom >= GRID_ZOOM_SUB) SUB_SQUARE_LAT else FIELD_LAT
        val cellLon = if (zoom >= GRID_ZOOM_SUB) SUB_SQUARE_LON else FIELD_LON

        // Visible bounding box in geographic coordinates
        val north = projection.fromPixels(0, 0)
        val south = projection.fromPixels(canvas.width, canvas.height)
        val topLat = north.latitude.coerceIn(-90.0, 90.0)
        val bottomLat = south.latitude.coerceIn(-90.0, 90.0)
        val leftLon = south.longitude
        val rightLon = north.longitude

        val firstRow = floor(bottomLat / cellLat).toInt()
        val lastRow = ceil(topLat / cellLat).toInt()
        // Longitude cell indices may exceed the [-180, 180) range when the view
        // crosses the antimeridian; index arithmetic still works (PMxx at 360° == same grid)
        val firstCol = floor(leftLon / cellLon).toInt()
        val lastCol = ceil(rightLon / cellLon).toInt()

        // Worked-grid highlight fills (only meaningful at sub-square zoom)
        if (workedGrids.isNotEmpty() && zoom >= GRID_ZOOM_SUB) {
            for (row in firstRow..lastRow) {
                val lat = row * cellLat
                if (lat < -90.0 || lat >= 90.0) continue
                val topLatCell = lat + cellLat
                if (topLatCell > 90.0) continue
                val yTop = projectionToY(projection, topLatCell)
                val yBottom = projectionToY(projection, lat)
                if (yTop == null || yBottom == null) continue
                for (col in firstCol..lastCol) {
                    val lon = col * cellLon
                    val xLeft = projectionToX(projection, lon) ?: continue
                    val xRight = projectionToX(projection, lon + cellLon) ?: continue
                    if (xRight < 0f || xLeft > canvas.width) continue
                    if (cellLabel(lat, lon, zoom) in workedGrids) {
                        canvas.drawRect(xLeft, yTop, xRight, yBottom, workedPaint)
                    }
                }
            }
        }

        // Vertical lines (meridians)
        for (col in firstCol..lastCol) {
            val lon = col * cellLon
            val x = projectionToX(projection, lon)
            if (x == null) continue
            canvas.drawLine(x, 0f, x, canvas.height.toFloat(), linePaint)
        }
        // Horizontal lines (parallels)
        for (row in firstRow..lastRow) {
            val lat = row * cellLat
            if (lat <= -90.0 || lat >= 90.0) continue
            val y = projectionToY(projection, lat)
            if (y == null) continue
            canvas.drawLine(0f, y, canvas.width.toFloat(), y, linePaint)
        }

        // Labels: place at the top-left corner of each cell, only when the cell
        // is large enough on screen to hold a label (avoid clutter at low zoom)
        val showLabels = when {
            zoom >= GRID_ZOOM_SUB -> true
            zoom >= FIELD_ZOOM_LABELS -> true
            else -> false
        }
        if (!showLabels) return
        // Estimate on-screen cell height to avoid clutter at low zoom:
        // project two points 1° apart in latitude and measure the pixel distance.
        val y1 = projectionToY(projection, 0.0)
        val y2 = projectionToY(projection, 1.0)
        if (y1 == null || y2 == null) return
        val pixelsPerDegree = Math.abs(y2 - y1)
        if (pixelsPerDegree * cellLat < MIN_LABEL_CELL_PX) return

        val labelOffset = 8f
        for (row in firstRow..lastRow) {
            val lat = row * cellLat
            if (lat < -90.0 || lat >= 90.0) continue
            val topLatCell = lat + cellLat
            if (topLatCell > 90.0) continue
            val y = projectionToY(projection, topLatCell) ?: continue
            if (y < -labelPaint.textSize || y > canvas.height) continue
            for (col in firstCol..lastCol) {
                val lon = col * cellLon
                val x = projectionToX(projection, lon) ?: continue
                if (x < -200f || x > canvas.width) continue
                val label = cellLabel(lat, lon, zoom)
                canvas.drawText(label, x + labelOffset, y + labelPaint.textSize + labelOffset, labelPaint)
            }
        }
    }

    /** X pixel for a longitude (meridians are vertical in Web Mercator). */
    private fun projectionToX(projection: Projection, lon: Double): Float? {
        val geo = org.osmdroid.util.GeoPoint(0.0, lon)
        val p = projection.toPixels(geo, null)
        return if (p.x in -MAX_OVERSHOOT_PX..MAX_OVERSHOOT_PX + 4096) p.x.toFloat() else null
    }

    /** Y pixel for a latitude, or null when outside the viewport. */
    private fun projectionToY(projection: Projection, lat: Double): Float? {
        val geo = org.osmdroid.util.GeoPoint(lat, 0.0)
        val p = projection.toPixels(geo, null)
        return p.y.toFloat()
    }

    private fun cellLabel(lat: Double, lon: Double, zoom: Double): String {
        // Maidenhead field: longitude 20° fields A-R starting at -180,
        // latitude 10° fields A-R starting at -90.
        val lonNorm = normalizeLon(lon)
        val latNorm = lat.coerceIn(-90.0, 89.999)
        val fieldLon = ((lonNorm + 180.0) / FIELD_LON).toInt()
        val fieldLat = ((latNorm + 90.0) / FIELD_LAT).toInt()
        val field = "${'A' + fieldLon}${'A' + fieldLat}"
        if (zoom < GRID_ZOOM_SUB) return field
        // Square: 2° x 1° digits
        val squareLon = (((lonNorm + 180.0) % FIELD_LON) / SUB_SQUARE_LON).toInt()
        val squareLat = (((latNorm + 90.0) % FIELD_LAT) / SUB_SQUARE_LAT).toInt()
        return "$field$squareLon$squareLat"
    }

    private fun normalizeLon(lon: Double): Double {
        var l = lon % 360.0
        if (l >= 180.0) l -= 360.0
        if (l < -180.0) l += 360.0
        return l
    }

    private companion object {
        const val FIELD_LAT = 10.0
        const val FIELD_LON = 20.0
        const val SUB_SQUARE_LAT = 1.0
        const val SUB_SQUARE_LON = 2.0
        const val GRID_ZOOM_SUB = 5.0
        const val FIELD_ZOOM_LABELS = 3.0
        const val MIN_LABEL_CELL_PX = 48f
        const val MAX_OVERSHOOT_PX = 64
    }
}
