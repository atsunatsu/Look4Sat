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
import kotlin.math.max
import kotlin.math.min

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
    private val ownLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        color = Color.argb(255, 90, 200, 255)
    }

    /** Worked gridsquares (4-char, uppercase) to highlight, e.g. {"OL62", "PM95"}. */
    var workedGrids: Set<String> = emptySet()

    /** The station's own 4-char gridsquare, drawn with a distinct outline. */
    var ownGrid: String? = null

    /** Viewport width, refreshed each draw; used by projectionToX bounds. */
    private var canvasWidthPx = 1080f

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        val projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        canvasWidthPx = canvas.width.toFloat()
        val cellLat = if (zoom >= GRID_ZOOM_SUB) SUB_SQUARE_LAT else FIELD_LAT
        val cellLon = if (zoom >= GRID_ZOOM_SUB) SUB_SQUARE_LON else FIELD_LON

        // Visible bounding box in geographic coordinates
        val north = projection.fromPixels(0, 0)
        val south = projection.fromPixels(canvas.width, canvas.height)
        val topLat = max(north.latitude, south.latitude).coerceIn(-90.0, 90.0)
        val bottomLat = min(north.latitude, south.latitude).coerceIn(-90.0, 90.0)
        // Longitude: osmdroid normalizes to [-180, 180), so when the view
        // straddles the antimeridian (e.g. left edge 170°E, right edge 170°W)
        // the raw min/max swap sides and the bounding box spans the wrong way
        // (center on the Pacific → grid painted 180° off-screen). Unwrap by
        // shifting one edge by +360° so left < right again.
        var lonA = north.longitude
        var lonB = south.longitude
        if (lonA > lonB) {
            val t = lonA; lonA = lonB; lonB = t
        }
        if (lonB - lonA > 180.0) lonA += 360.0
        val leftLon = lonA
        val rightLon = lonB

        val firstRow = floor(bottomLat / cellLat).toInt()
        val lastRow = ceil(topLat / cellLat).toInt()
        // Longitude cell indices may exceed the [-180, 180) range when the view
        // crosses the antimeridian; index arithmetic still works (PMxx at 360° == same grid)
        val firstCol = floor(leftLon / cellLon).toInt()
        val lastCol = ceil(rightLon / cellLon).toInt()
        val centerLon = (leftLon + rightLon) / 2.0
        // World width in screen pixels for the current zoom (256 px per tile,
        // 2^zoom tiles across the whole 360° world). Used by the custom
        // Mercator-X computation in projectionToX.
        val worldWidthPx = 256.0 * Math.pow(2.0, zoom)
        // At low zoom the world is narrower than the viewport and osmdroid shows
        // repeating copies on both sides. The visible bounding box spans more
        // than 360° of longitude there; extend the column range by whole world
        // turns so meridians, fills and labels tile across the repeats too.
        val worldTurns = ceil(((rightLon - leftLon) / 360.0) - 1e-9).toInt().coerceAtLeast(0)
        val colRepeats = if (worldTurns > 0) worldTurns else 0

        // The station's own grid square (4-char, only meaningful at sub-square zoom)
        val ownCell = ownGrid?.takeIf { zoom >= GRID_ZOOM_SUB }

        // Worked-grid highlight fills.
        //  - Sub-square zoom: fill each worked 4-char cell directly.
        //  - Field zoom (two-char labels): do NOT fill whole fields; instead fill
        //    the individual 2°x1° squares that were worked, without drawing the
        //    square grid lines — so you see green patches inside the field.
        if (workedGrids.isNotEmpty()) {
            if (zoom >= GRID_ZOOM_SUB) {
                for (row in firstRow..lastRow) {
                    val lat = row * cellLat
                    if (lat < -90.0 || lat >= 90.0) continue
                    val topLatCell = lat + cellLat
                    if (topLatCell > 90.0) continue
                    val yTop = projectionToY(projection, topLatCell)
                    val yBottom = projectionToY(projection, lat)
                    if (yTop == null || yBottom == null) continue
                    for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
                        val lon = col * cellLon
                        val xLeft = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                        val xRight = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                        if (xRight < 0f || xLeft > canvas.width) continue
                        if (cellLabel(lat, lon, zoom) in workedGrids) {
                            canvas.drawRect(xLeft, yTop, xRight, yBottom, workedPaint)
                        }
                    }
                }
            } else {
                for (grid in workedGrids) {
                    val cell = gridCellBounds(grid) ?: continue
                    for (turn in -colRepeats..colRepeats) {
                        // Shift the cell by whole world turns to cover repeats.
                        val dLon = turn * 360.0
                        if (cell.lonRight + dLon <= leftLon || cell.lonLeft + dLon >= rightLon) continue
                        val yTop = projectionToY(projection, cell.latTop) ?: continue
                        val yBottom = projectionToY(projection, cell.latBottom) ?: continue
                        val xLeft = projectionToX(projection, cell.lonLeft + dLon, centerLon, worldWidthPx) ?: continue
                        val xRight = projectionToX(projection, cell.lonRight + dLon, centerLon, worldWidthPx) ?: continue
                        if (xRight < 0f || xLeft > canvas.width) continue
                        if (yBottom < 0f || yTop > canvas.height) continue
                        canvas.drawRect(xLeft, yTop, xRight, yBottom, workedPaint)
                    }
                }
            }
        }

        // Vertical lines (meridians)
        for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
            val lon = col * cellLon
            val x = projectionToX(projection, lon, centerLon, worldWidthPx)
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

        // The station's own grid square: redraw its four borders thicker on top.
        if (ownCell != null) {
            val ownColIdx = firstCol..lastCol
            for (row in firstRow..lastRow) {
                val lat = row * cellLat
                if (lat < -90.0 || lat >= 90.0) continue
                for (col in ownColIdx) {
                    val lon = col * cellLon
                    if (cellLabel(lat, lon, zoom) != ownCell) continue
                    val yTop = projectionToY(projection, lat + cellLat) ?: continue
                    val yBottom = projectionToY(projection, lat) ?: continue
                    val xLeft = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                    val xRight = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                    canvas.drawLine(xLeft, yTop, xRight, yTop, ownLinePaint)
                    canvas.drawLine(xLeft, yBottom, xRight, yBottom, ownLinePaint)
                    canvas.drawLine(xLeft, yTop, xLeft, yBottom, ownLinePaint)
                    canvas.drawLine(xRight, yTop, xRight, yBottom, ownLinePaint)
                }
            }
        }

        // Labels: centered in each cell, only when the cell is large enough on
        // screen to hold a label (avoid clutter at low zoom).
        // Field (2-char) labels show at every zoom, subject only to the pixel-
        // size check below; sub-square (4-char) labels only from LABEL_ZOOM_SUB
        // (one level above the grid lines, so zoom 6 shows lines but no names).
        val showLabels = zoom >= LABEL_ZOOM_SUB || cellLat == FIELD_LAT
        if (!showLabels) return
        // Estimate on-screen cell height to avoid clutter at low zoom:
        // project two points 1° apart in latitude and measure the pixel distance.
        val y1 = projectionToY(projection, 0.0)
        val y2 = projectionToY(projection, 1.0)
        if (y1 == null || y2 == null) return
        val pixelsPerDegree = Math.abs(y2 - y1)
        if (pixelsPerDegree * cellLat < MIN_LABEL_CELL_PX) return

        labelPaint.textAlign = Paint.Align.CENTER
        val fontMetrics = labelPaint.fontMetrics
        val textHalfHeight = (fontMetrics.descent + fontMetrics.ascent) / 2f
        for (row in firstRow..lastRow) {
            val lat = row * cellLat
            if (lat < -90.0 || lat >= 90.0) continue
            val topLatCell = lat + cellLat
            if (topLatCell > 90.0) continue
            val yTop = projectionToY(projection, topLatCell) ?: continue
            val yBottom = projectionToY(projection, lat) ?: continue
            val yCenter = (yTop + yBottom) / 2f - textHalfHeight
            if (yBottom < 0f || yTop > canvas.height) continue
            for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
                val lon = col * cellLon
                val xLeft = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                val xRight = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                if (xRight < 0f || xLeft > canvas.width) continue
                val label = cellLabel(lat, lon, zoom)
                canvas.drawText(label, (xLeft + xRight) / 2f, yCenter, labelPaint)
            }
        }
    }

    /** X pixel for a longitude (meridians are vertical in Web Mercator). */
    private fun projectionToX(projection: Projection, lon: Double, centerLon: Double, worldWidthPx: Double): Float? {
        // Do NOT use osmdroid's toPixels() here: at low zoom its wrap-around
        // logic (getCloserPixel) mis-projects longitudes far from the view
        // center, which makes meridians vanish while panning. Mercator X is
        // linear in longitude, so compute it directly:
        //   x = screenCenterX + (lon - centerLon) / 360 * worldWidthPx
        var delta = lon - centerLon
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        val geo = org.osmdroid.util.GeoPoint(0.0, centerLon)
        val p = projection.toPixels(geo, null)
        return (p.x + delta / 360.0 * worldWidthPx).toFloat()
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

    /**
     * Geographic bounds of a 4-char Maidenhead square (e.g. "OL62"):
     * 2° wide in longitude, 1° tall in latitude.
     */
    private fun gridCellBounds(grid: String): CellBounds? {
        val g = grid.trim().uppercase()
        if (g.length < 4) return null
        val fieldLon = g[0] - 'A'
        val fieldLat = g[1] - 'A'
        val sqLon = g[2] - '0'
        val sqLat = g[3] - '0'
        if (fieldLon !in 0..17 || fieldLat !in 0..17 || sqLon !in 0..9 || sqLat !in 0..9) return null
        val lonLeft = -180.0 + fieldLon * FIELD_LON + sqLon * SUB_SQUARE_LON
        val latBottom = -90.0 + fieldLat * FIELD_LAT + sqLat * SUB_SQUARE_LAT
        return CellBounds(lonLeft, lonLeft + SUB_SQUARE_LON, latBottom, latBottom + SUB_SQUARE_LAT)
    }

    private data class CellBounds(
        val lonLeft: Double,
        val lonRight: Double,
        val latBottom: Double,
        val latTop: Double
    )

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
        // Zoom at which the 2°x1° sub-square grid lines/fills appear.
        // 5.0 → 6.0: at zoom 5 a full field spans too little screen width and
        // the sub-square grid is too dense to read; 6 roughly doubles the
        // on-screen size of each square.
        const val GRID_ZOOM_SUB = 6.0
        // Zoom at which the 4-char sub-square names appear (one level above the
        // grid lines: zoom 6 = lines only, zoom 7+ = lines + names).
        const val LABEL_ZOOM_SUB = 7.0
        const val MIN_LABEL_CELL_PX = 48f
        const val MAX_OVERSHOOT_PX = 64
    }
}
