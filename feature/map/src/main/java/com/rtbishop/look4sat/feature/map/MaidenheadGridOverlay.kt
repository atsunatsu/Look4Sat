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
import kotlin.math.roundToInt

/** Wavelog's palette normalization: 0 is red; the lower of data maximum and filter maximum is green. */
internal fun gridActivityColorIndex(percentage: Int, dataMax: Int, filterMax: Int): Int {
    val scaleMax = min(dataMax.coerceIn(0, 100), filterMax.coerceIn(0, 100))
    if (scaleMax == 0) return 0
    return (percentage.coerceIn(0, 100) * 100f / scaleMax).roundToInt().coerceIn(0, 100)
}

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
    // 网格标签与首通呼号标签同字号 (用户要求 2026-09-21: 字体一样大)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LABEL_TEXT_SIZE
        style = android.graphics.Paint.Style.FILL
        color = Color.argb(220, 255, 224, 130)
        setShadowLayer(3f, 2f, 2f, Color.BLACK)
    }
    // 首通呼号标签: 与网格标签同字号.
    private val firstCallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LABEL_TEXT_SIZE
        style = android.graphics.Paint.Style.FILL
        color = Color.argb(230, 255, 224, 130)
        setShadowLayer(3f, 2f, 2f, Color.BLACK)
    }
    // Smaller second line when GridMaster activity values are shown inside squares.
    private val activityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LABEL_TEXT_SIZE * 0.72f
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.argb(220, 255, 224, 130)
        setShadowLayer(3f, 2f, 2f, Color.BLACK)
    }
    private val workedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = Color.argb(90, 76, 217, 100)
    }
    // 标记(想通联)网格填充: 红色, 透明度与绿色 workedPaint 一致(alpha 90).
    private val markedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = Color.argb(90, 255, 59, 48)
    }
    private val activityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val activityColors = IntArray(101) { p ->
        // Soft red -> amber -> green; subtle enough to preserve the existing grid lines.
        val first = if (p <= 50) intArrayOf(242, 96, 99) else intArrayOf(248, 205, 105)
        val second = if (p <= 50) intArrayOf(248, 205, 105) else intArrayOf(100, 200, 142)
        val fraction = if (p <= 50) p / 50f else (p - 50) / 50f
        Color.argb(
            110,
            (first[0] + (second[0] - first[0]) * fraction).toInt(),
            (first[1] + (second[1] - first[1]) * fraction).toInt(),
            (first[2] + (second[2] - first[2]) * fraction).toInt()
        )
    }
    private data class ActivityCell(val grid: String, val bounds: CellBounds, val percentage: Int)
    private var activityCells: List<ActivityCell> = emptyList()
    private var activityMaxPercentage = 0

    /** GridMaster share of participating SAT logs containing a given 4-character grid. */
    var activityPercentages: Map<String, Int> = emptyMap()
        set(value) {
            if (field === value) return
            field = value
            activityMaxPercentage = value.values.maxOrNull()?.coerceIn(0, 100) ?: 0
            activityCells = value.mapNotNull { (grid, percentage) ->
                gridCellBounds(grid)?.let { ActivityCell(grid, it, percentage.coerceIn(0, 100)) }
            }
        }
    var showActivityOverlay: Boolean = false
    var activityFilterMin: Int = 0
    var activityFilterMax: Int = 100
    /** When false, hide personal green/red fills and include those squares in the activity map. */
    var showWorkedAndMarkedWithActivity: Boolean = false

    private val roamStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        // Same alpha as the green worked fill (90) — user req: stripe opacity
        // must match the green grid. Wide 14f stripes at 44f spacing read as a
        // sparse GridMaster-style zebra (user picked spacing 44 / width 14).
        color = Color.argb(90, 66, 133, 244)
    }
    private val ownLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f
        style = Paint.Style.STROKE
        // 与普通网格线同色(淡黄), 仅加粗 — 用户要求当前网格框线不换色
        color = Color.argb(160, 255, 224, 130)
    }
    private val selectedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        color = Color.argb(255, 76, 217, 100)
    }

    /**
     * Recolor the amber line/label paints for the active map theme: dark amber with a white halo
     * on the light map, light amber with a black halo on the dark map. The worked / marked / roam /
     * selected colors stay as they are — they read on both backgrounds.
     */
    fun applyTheme(isLightUi: Boolean) {
        val line = if (isLightUi) Color.argb(200, 113, 92, 12) else Color.argb(160, 255, 224, 130)
        val label = if (isLightUi) Color.argb(235, 30, 25, 5) else Color.argb(220, 255, 224, 130)
        val halo = if (isLightUi) Color.WHITE else Color.BLACK
        linePaint.color = line
        // ownLine stays the same color as the normal grid lines (only thicker).
        ownLinePaint.color = line
        labelPaint.color = label
        labelPaint.setShadowLayer(3f, 2f, 2f, halo)
        firstCallPaint.color = label
        firstCallPaint.setShadowLayer(3f, 2f, 2f, halo)
        activityLabelPaint.color = label
        activityLabelPaint.setShadowLayer(3f, 2f, 2f, halo)
    }

    /** Worked gridsquares (4-char, uppercase) to highlight, e.g. {"OL62", "PM95"}. */
    var workedGrids: Set<String> = emptySet()

    /** Marked gridsquares (4-char, uppercase) — unworked grids the user has
     *  marked stations they want to contact in; drawn red, same alpha as the
     *  green worked fill. Value is the ordered mark list (first = pinned);
     *  label mode shows the FIRST marked callsign. */
    var markedGrids: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>> = emptyMap()

    /** Grid-mode first-call labels: label worked (green) cells with the first
     *  callsign worked in that grid instead of the Maidenhead code; non-worked
     *  cells get no label at all. Only meaningful at sub-square zoom. */
    var showFirstCallLabels: Boolean = false

    /** Worked grid -> first callsign worked in it (earliest QSO by time).
     *  Only read when [showFirstCallLabels] is on. */
    var firstCallsByGrid: Map<String, String> = emptyMap()

    /**
     * Gridsquares the station operated from (4-char, uppercase) — drawn with
     * blue 45° stripes (GridMaster-style zebra), e.g. {"OL62", "PM95"}.
     */
    var roamedGrids: Set<String> = emptySet()

    /** The station's own 4-char gridsquare, drawn with a distinct outline. */
    var ownGrid: String? = null

    /** The currently tapped worked grid (4-char) highlighted with a distinct outline, or null. */
    var selectedGrid: String? = null

    /** Viewport width, refreshed each draw; used by projectionToX bounds. */
    private var canvasWidthPx = 1080f

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        val projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        canvasWidthPx = canvas.width.toFloat()
        val cellLat = if (zoom >= GRID_ZOOM_SUB) SUB_SQUARE_LAT else FIELD_LAT
        val cellLon = if (zoom >= GRID_ZOOM_SUB) SUB_SQUARE_LON else FIELD_LON

        // Visible bounding box. Do NOT derive longitude bounds from the left/
        // right edge pixels: osmdroid normalizes them to [-180,180), which both
        // garbles antimeridian-crossing views AND makes any global view (span
        // > 180°) look like an antimeridian crossing — unwrapping then produced
        // an inverted range and ALL meridians vanished at low zoom. Instead
        // anchor on the view-center longitude and expand by the half-width in
        // degrees; the range is continuous (may exceed ±180) and cell indices
        // beyond 18 wrap correctly via normalizeLon().
        val north = projection.fromPixels(0, 0)
        val south = projection.fromPixels(canvas.width, canvas.height)
        val topLat = max(north.latitude, south.latitude).coerceIn(-90.0, 90.0)
        val bottomLat = min(north.latitude, south.latitude).coerceIn(-90.0, 90.0)
        val worldWidthPx = 256.0 * Math.pow(2.0, zoom)
        val viewCenter = projection.fromPixels(canvas.width / 2, canvas.height / 2)
        val centerLon = viewCenter.longitude
        val halfSpanDeg = (canvas.width / 2.0) / worldWidthPx * 360.0
        val leftLon = centerLon - halfSpanDeg
        val rightLon = centerLon + halfSpanDeg

        val firstRow = floor(bottomLat / cellLat).toInt()
        val lastRow = ceil(topLat / cellLat).toInt()
        // Longitude cell indices are in the continuous unwrapped space and may
        // exceed the [-180, 180) range when the view crosses the antimeridian
        // or spans world repeats; labels normalize each cell back.
        val firstCol = floor(leftLon / cellLon).toInt()
        val lastCol = ceil(rightLon / cellLon).toInt()
        // At low zoom the world is narrower than the viewport and osmdroid shows
        // repeating copies on both sides. The visible bounding box spans more
        // than 360° of longitude there; extend the column range by whole world
        // turns so meridians, fills and labels tile across the repeats too.
        val worldTurns = ceil(((rightLon - leftLon) / 360.0) - 1e-9).toInt().coerceAtLeast(0)
        val colRepeats = if (worldTurns > 0) worldTurns else 0

        // The station's own grid: always keep a bold outline of the 4-char
        // square. At sub-square zoom it matches the visible cell grid; at field
        // zoom (2-char labels) the own 2°x1° square is drawn on top of the
        // field grid so the operator still sees exactly where they are (user
        // req: "field zoom must also draw the own 4-char grid, bolded").
        val ownCell = ownGrid
        // The tapped worked grid gets a distinct outline (sub-square zoom only).
        val selectedCell = selectedGrid?.takeIf { zoom >= GRID_ZOOM_SUB }

        // Two modes: preserve personal green/red fills, or show the full GridMaster
        // heatmap (including worked/marked grids) when that preference is off.
        // At either zoom level, heatmap geometry always uses four-character squares.
        val drawPersonalGridFills = !showActivityOverlay || showWorkedAndMarkedWithActivity
        if (showActivityOverlay && activityCells.isNotEmpty()) {
            val pixelsPerDegreeLon = worldWidthPx / 360.0
            for (entry in activityCells) {
                if (drawPersonalGridFills && (entry.grid in workedGrids || entry.grid in markedGrids)) continue
                if (entry.percentage !in activityFilterMin..activityFilterMax) continue
                val cell = entry.bounds
                if (cell.latTop < bottomLat || cell.latBottom > topLat) continue
                val yTop = projectionToY(projection, cell.latTop) ?: continue
                val yBottom = projectionToY(projection, cell.latBottom) ?: continue
                if (yBottom < 0f || yTop > canvas.height) continue
                // Match Wavelog: normalize against min(dataset maximum, filter maximum),
                // leaving the slider's minimum as an inclusion filter only.
                activityPaint.color = activityColors[
                    gridActivityColorIndex(entry.percentage, activityMaxPercentage, activityFilterMax)
                ]
                for (turn in -colRepeats..colRepeats) {
                    val lonLeft = cell.lonLeft + turn * 360.0
                    val lonRight = cell.lonRight + turn * 360.0
                    if (lonRight < leftLon || lonLeft > rightLon) continue
                    // Linear unwrapped longitude projection avoids the +/-180-degree
                    // clipping that occurs when osmdroid wraps normalized tile X.
                    val xLeft = (canvas.width / 2.0 +
                        (lonLeft - centerLon) * pixelsPerDegreeLon).toFloat()
                    val xRight = (canvas.width / 2.0 +
                        (lonRight - centerLon) * pixelsPerDegreeLon).toFloat()
                    if (xRight < 0f || xLeft > canvas.width) continue
                    canvas.drawRect(xLeft, yTop, xRight, yBottom, activityPaint)
                }
            }
        }

        // Worked-grid highlight fills.
        //  - Sub-square zoom: fill each worked 4-char cell directly.
        //  - Field zoom (two-char labels): do NOT fill whole fields; instead fill
        //    the individual 2°x1° squares that were worked, without drawing the
        //    square grid lines — so you see green patches inside the field.
        if (drawPersonalGridFills && (workedGrids.isNotEmpty() || markedGrids.isNotEmpty())) {
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
                        // World-repeat copies: keep the turn offset in pixels
                        // (colRepeats is 0 at this zoom today, but the same
                        // alpha-stacking fix applies if it ever becomes > 0).
                        val xLeftBase = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                        val xRightBase = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                        val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                        val xRight = xRightBase + turn * worldWidthPx.toFloat()
                        if (xRight < 0f || xLeft > canvas.width) continue
                        val gridLabel = cellLabel(lat, lon, zoom)
                        if (gridLabel in workedGrids) {
                            canvas.drawRect(xLeft, yTop, xRight, yBottom, workedPaint)
                        }
                        if (gridLabel in markedGrids && gridLabel !in workedGrids) {
                            canvas.drawRect(xLeft, yTop, xRight, yBottom, markedPaint)
                        }
                    }
                }
            } else {
                for (grid in workedGrids) {
                    val cell = gridCellBounds(grid) ?: continue
                    // World-repeat copies: a 360° turn shifts the cell by a
                    // full world width in pixels. projectionToX() normalizes
                    // longitude back into [-180,180), so WITHOUT adding the
                    // turn*worldWidthPx offset every copy lands on the SAME
                    // screen x and the cell gets painted 2*colRepeats+1 times
                    // in place — alpha stacks and the green turns brighter and
                    // more opaque. Keep the world-width offset on the x.
                    for (turn in -colRepeats..colRepeats) {
                        val dLon = turn * 360.0
                        if (cell.lonRight + dLon <= leftLon || cell.lonLeft + dLon >= rightLon) continue
                        val yTop = projectionToY(projection, cell.latTop) ?: continue
                        val yBottom = projectionToY(projection, cell.latBottom) ?: continue
                        val xLeftBase = projectionToX(projection, cell.lonLeft, centerLon, worldWidthPx) ?: continue
                        val xRightBase = projectionToX(projection, cell.lonRight, centerLon, worldWidthPx) ?: continue
                        val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                        val xRight = xRightBase + turn * worldWidthPx.toFloat()
                        if (xRight < 0f || xLeft > canvas.width) continue
                        if (yBottom < 0f || yTop > canvas.height) continue
                        canvas.drawRect(xLeft, yTop, xRight, yBottom, workedPaint)
                    }
                }
                for (grid in markedGrids.keys) {
                    if (grid in workedGrids) continue
                    val cell = gridCellBounds(grid) ?: continue
                    for (turn in -colRepeats..colRepeats) {
                        val dLon = turn * 360.0
                        if (cell.lonRight + dLon <= leftLon || cell.lonLeft + dLon >= rightLon) continue
                        val yTop = projectionToY(projection, cell.latTop) ?: continue
                        val yBottom = projectionToY(projection, cell.latBottom) ?: continue
                        val xLeftBase = projectionToX(projection, cell.lonLeft, centerLon, worldWidthPx) ?: continue
                        val xRightBase = projectionToX(projection, cell.lonRight, centerLon, worldWidthPx) ?: continue
                        val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                        val xRight = xRightBase + turn * worldWidthPx.toFloat()
                        if (xRight < 0f || xLeft > canvas.width) continue
                        if (yBottom < 0f || yTop > canvas.height) continue
                        canvas.drawRect(xLeft, yTop, xRight, yBottom, markedPaint)
                    }
                }
            }
        }

        // Roamed/activated grid stripes: blue 45° zebra (GridMaster style) over
        // every cell the station operated from. Drawn AFTER the worked fills so
        // a worked+roamed cell shows blue stripes with green between them — the
        // stripe paint is fully opaque, so the two colors never alpha-blend
        // into a teal/green mix. Same geometry as the worked fills (per-4-char
        // cell at both zoom levels). The station's OWN grid is excluded: it
        // already carries the bold outline and must not be striped (user req).
        if (roamedGrids.isNotEmpty()) {
            if (zoom >= GRID_ZOOM_SUB) {
                for (row in firstRow..lastRow) {
                    val lat = row * cellLat
                    if (lat < -90.0 || lat >= 90.0) continue
                    val topLatCell = lat + cellLat
                    if (topLatCell > 90.0) continue
                    val yTop = projectionToY(projection, topLatCell) ?: continue
                    val yBottom = projectionToY(projection, lat) ?: continue
                    for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
                        val lon = col * cellLon
                        val xLeftBase = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                        val xRightBase = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                        val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                        val xRight = xRightBase + turn * worldWidthPx.toFloat()
                        if (xRight < 0f || xLeft > canvas.width) continue
                        val label = cellLabel(lat, lon, zoom)
                        if (label in roamedGrids && label != ownGrid) {
                            drawStripes(canvas, xLeft, yTop, xRight, yBottom, zoom)
                        }
                    }
                }
            } else {
                for (grid in roamedGrids) {
                    // The own grid keeps only its bold outline — no stripes.
                    if (grid == ownGrid) continue
                    val cell = gridCellBounds(grid) ?: continue
                    for (turn in -colRepeats..colRepeats) {
                        val dLon = turn * 360.0
                        if (cell.lonRight + dLon <= leftLon || cell.lonLeft + dLon >= rightLon) continue
                        val yTop = projectionToY(projection, cell.latTop) ?: continue
                        val yBottom = projectionToY(projection, cell.latBottom) ?: continue
                        val xLeftBase = projectionToX(projection, cell.lonLeft, centerLon, worldWidthPx) ?: continue
                        val xRightBase = projectionToX(projection, cell.lonRight, centerLon, worldWidthPx) ?: continue
                        val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                        val xRight = xRightBase + turn * worldWidthPx.toFloat()
                        if (xRight < 0f || xLeft > canvas.width) continue
                        if (yBottom < 0f || yTop > canvas.height) continue
                        drawStripes(canvas, xLeft, yTop, xRight, yBottom, zoom)
                    }
                }
            }
        }

        // Vertical lines (meridians). Worked-grid cells are NOT excluded:
        // the boundary lines between adjacent worked squares must stay visible
        // so the grid structure remains readable (reverted 2026-09-09 — the
        // earlier skip logic removed those shared edges entirely).
        for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
            val lon = col * cellLon
            // World-repeat copies: keep the turn offset in pixels, otherwise
            // every turn normalizes to the same x and the line paints over
            // itself 2*colRepeats+1 times (alpha stacking).
            val xBase = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
            val x = xBase + turn * worldWidthPx.toFloat()
            for (row in firstRow..lastRow) {
                val lat = row * cellLat
                if (lat < -90.0 || lat >= 90.0) continue
                val topLatCell = lat + cellLat
                if (topLatCell > 90.0) continue
                val yTop = projectionToY(projection, topLatCell) ?: continue
                val yBottom = projectionToY(projection, lat) ?: continue
                canvas.drawLine(x, yTop, x, yBottom, linePaint)
            }
        }
        // Horizontal lines (parallels).
        for (row in firstRow..lastRow) {
            val lat = row * cellLat
            if (lat <= -90.0 || lat >= 90.0) continue
            val y = projectionToY(projection, lat)
            if (y == null) continue
            for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
                val lon = col * cellLon
                // World-repeat copies: keep the turn offset in pixels (same
                // alpha-stacking fix as the vertical lines above).
                val xLeftBase = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                val xRightBase = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                val xRight = xRightBase + turn * worldWidthPx.toFloat()
                if (xRight < 0f || xLeft > canvas.width) continue
                canvas.drawLine(xLeft, y, xRight, y, linePaint)
            }
        }

        // The station's own grid square: redraw its four borders thicker on top.
        // The tapped worked grid gets the same treatment in a different color.
        if (ownCell != null || selectedCell != null) {
            if (zoom >= GRID_ZOOM_SUB) {
                for (row in firstRow..lastRow) {
                    val lat = row * cellLat
                    if (lat < -90.0 || lat >= 90.0) continue
                    for (col in firstCol..lastCol) {
                        val lon = col * cellLon
                        val label = cellLabel(lat, lon, zoom)
                        if (label != ownCell && label != selectedCell) continue
                        val yTop = projectionToY(projection, lat + cellLat) ?: continue
                        val yBottom = projectionToY(projection, lat) ?: continue
                        val xLeft = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                        val xRight = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                        val paint = if (label == selectedCell) selectedLinePaint else ownLinePaint
                        canvas.drawLine(xLeft, yTop, xRight, yTop, paint)
                        canvas.drawLine(xLeft, yBottom, xRight, yBottom, paint)
                        canvas.drawLine(xLeft, yTop, xLeft, yBottom, paint)
                        canvas.drawLine(xRight, yTop, xRight, yBottom, paint)
                    }
                }
            } else {
                // Field zoom: the visible grid shows 2-char fields only, but the
                // station's own 2°x1° square still gets its bold outline drawn
                // on top of the field grid (user req). selectedCell is null here.
                val grid = ownCell
                if (grid != null) {
                    val cell = gridCellBounds(grid)
                    if (cell != null) {
                        for (turn in -colRepeats..colRepeats) {
                            val dLon = turn * 360.0
                            if (cell.lonRight + dLon <= leftLon || cell.lonLeft + dLon >= rightLon) continue
                            val yTop = projectionToY(projection, cell.latTop) ?: continue
                            val yBottom = projectionToY(projection, cell.latBottom) ?: continue
                            val xLeftBase = projectionToX(projection, cell.lonLeft, centerLon, worldWidthPx) ?: continue
                            val xRightBase = projectionToX(projection, cell.lonRight, centerLon, worldWidthPx) ?: continue
                            val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                            val xRight = xRightBase + turn * worldWidthPx.toFloat()
                            if (xRight < 0f || xLeft > canvas.width) continue
                            if (yBottom < 0f || yTop > canvas.height) continue
                            canvas.drawLine(xLeft, yTop, xRight, yTop, ownLinePaint)
                            canvas.drawLine(xLeft, yBottom, xRight, yBottom, ownLinePaint)
                            canvas.drawLine(xLeft, yTop, xLeft, yBottom, ownLinePaint)
                            canvas.drawLine(xRight, yTop, xRight, yBottom, ownLinePaint)
                        }
                    }
                }
            }
        }

        // Labels: centered in each cell, only when the cell is large enough on
        // screen to hold a label (avoid clutter at low zoom).
        // Field (2-char) labels show at every zoom, subject only to the pixel-
        // size check below; sub-square (4-char) labels appear together with the
        // grid LINES at GRID_ZOOM_SUB (user req 2026-09-21: 与首通呼号同一显示缩放).
        // In first-call mode, 2-char field zoom still uses the normal field
        // letters. At 4-char zoom, callsigns replace grid codes; if GridMaster
        // activity is enabled, percentages are shown beneath the callsigns
        // (or alone in unworked cells).
        // 网格标签与首通呼号同一显示缩放 (用户要求 2026-09-21): 子方块(4字符)
        // 标签与首通呼号都在网格线出现的 GRID_ZOOM_SUB 同时显示, 不再晚一档.
        val showLabels = zoom >= GRID_ZOOM_SUB || cellLat == FIELD_LAT
        if (!showLabels) return
        // Estimate on-screen cell height to avoid clutter at low zoom:
        // project two points 1° apart in latitude and measure the pixel distance.
        val y1 = projectionToY(projection, 0.0)
        val y2 = projectionToY(projection, 1.0)
        if (y1 == null || y2 == null) return
        val pixelsPerDegree = Math.abs(y2 - y1)
        // 两种模式同一像素门限: GRID_ZOOM_SUB 处 1° 格约 45px, 低于 48px
        // 常规阈值, 统一用放宽的 0.6x 门限, 保证网格标签与首通呼号同时出现.
        val minCellPx = MIN_LABEL_CELL_PX * 0.6f
        if (pixelsPerDegree * cellLat < minCellPx) return

        val activePaint = if (showFirstCallLabels && zoom >= GRID_ZOOM_SUB) firstCallPaint else labelPaint
        activePaint.textAlign = Paint.Align.CENTER
        val fontMetrics = activePaint.fontMetrics
        val textHalfHeight = (fontMetrics.descent + fontMetrics.ascent) / 2f
        for (row in firstRow..lastRow) {
            val lat = row * cellLat
            if (lat < -90.0 || lat >= 90.0) continue
            val topLatCell = lat + cellLat
            if (topLatCell > 90.0) continue
            val yTop = projectionToY(projection, topLatCell) ?: continue
            val yBottom = projectionToY(projection, lat) ?: continue
            // Polar bands (80..90 / -90..-80) extend beyond the map's latitude
            // limit, so their geometric center falls off-screen and the label
            // would never be visible. Center the label in the VISIBLE part of
            // the cell instead; fully off-screen cells are still skipped.
            val visTop = maxOf(yTop, 0f)
            val visBottom = minOf(yBottom, canvas.height.toFloat())
            if (visBottom < 0f || visTop > canvas.height) continue
            val yCenter = (visTop + visBottom) / 2f - textHalfHeight
            for (turn in -colRepeats..colRepeats) for (col in firstCol..lastCol) {
                val lon = col * cellLon
                // World-repeat copies: keep the turn offset in pixels (same
                // alpha-stacking fix as the lines above).
                val xLeftBase = projectionToX(projection, lon, centerLon, worldWidthPx) ?: continue
                val xRightBase = projectionToX(projection, lon + cellLon, centerLon, worldWidthPx) ?: continue
                val xLeft = xLeftBase + turn * worldWidthPx.toFloat()
                val xRight = xRightBase + turn * worldWidthPx.toFloat()
                if (xRight < 0f || xLeft > canvas.width) continue
                val label = cellLabel(lat, lon, zoom)
                val xCenter = (xLeft + xRight) / 2f
                if (zoom < GRID_ZOOM_SUB) {
                    // Field zoom is independent of first-call mode: always label
                    // the two-letter Maidenhead fields, with no percentages.
                    canvas.drawText(label, xCenter, yCenter, labelPaint)
                } else {
                    val percent = if (showActivityOverlay) {
                        activityPercentages[label]?.takeIf { it in activityFilterMin..activityFilterMax }
                    } else null
                    val mainLabel = if (showFirstCallLabels) {
                        when {
                            label in workedGrids -> firstCallsByGrid[label]
                            label in markedGrids -> markedGrids[label]?.firstOrNull()?.call
                            else -> null
                        }
                    } else label
                    // Keep the text centred for both single- and double-line
                    // layouts, including activity-only squares in first-call mode.
                    if (mainLabel != null) {
                        canvas.drawText(
                            mainLabel, xCenter, if (percent != null) yCenter - 9f else yCenter,
                            if (showFirstCallLabels) firstCallPaint else labelPaint
                        )
                    }
                    if (percent != null) {
                        canvas.drawText(
                            "${percent}%", xCenter,
                            if (mainLabel != null) yCenter + 12f else yCenter,
                            activityLabelPaint
                        )
                    }
                }
            }
        }
    }

    /**
     * Draws 45° diagonal stripes (GridMaster-style zebra) clipped to the cell
     * rectangle, from top-left to bottom-right. The paint is nearly opaque so
     * stripes stay blue even over a green worked fill underneath.
     */
    private fun drawStripes(canvas: Canvas, xLeft: Float, yTop: Float, xRight: Float, yBottom: Float, zoom: Double) {
        if (xRight <= xLeft || yBottom <= yTop) return
        canvas.save()
        canvas.clipRect(xLeft, yTop, xRight, yBottom)
        val height = yBottom - yTop
        // Geographic density is kept constant: spacing is the reference pixel
        // spacing (STRIPE_SPACING_PX at the map's max zoom) scaled by 2^(zoom-max),
        // so stripes shrink/grow with the map instead of staying fixed on screen.
        val spacing = STRIPE_SPACING_PX * Math.pow(2.0, zoom - MAX_GRID_ZOOM).toFloat()
        // Start one stripe-width left of the cell so the top-left corner is
        // always covered; each stripe runs from (x, top) to (x+height, bottom).
        var x = xLeft - height
        while (x < xRight) {
            canvas.drawLine(x, yTop, x + height, yBottom, roamStripePaint)
            x += spacing
        }
        canvas.restore()
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
        // Web Mercator is undefined beyond ±85.0511° (osmdroid clamps the world
        // to TileSystemWebMercator limits). Projecting the polar field rows
        // (lat=±90: Maidenhead row R = 80..90°N, row A = -90..-80°S) yields
        // ±9.2e18 pixel coordinates that the canvas cannot rasterize, so the
        // polar grid lines silently vanish. Clamp to the Mercator limit: the
        // polar rows then project onto the screen edge and their lines are
        // drawn (the off-limit sliver beyond 85.05° is invisible anyway).
        val clamped = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val geo = org.osmdroid.util.GeoPoint(clamped, 0.0)
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

    internal companion object {
        const val FIELD_LAT = 10.0
        const val FIELD_LON = 20.0
        const val SUB_SQUARE_LAT = 1.0
        const val SUB_SQUARE_LON = 2.0
        // Web Mercator latitude limit (osmdroid TileSystemWebMercator): projecting
        // beyond it produces ±9.2e18 pixel coordinates the canvas cannot draw.
        const val MAX_MERCATOR_LAT = 85.05112877980658
        // Zoom at which the 2°x1° sub-square grid lines/fills appear.
        // 5.0 → 6.0: at zoom 5 a full field spans too little screen width and
        // the sub-square grid is too dense to read; 6 roughly doubles the
        // on-screen size of each square.
        const val GRID_ZOOM_SUB = 6.0
        // 网格标签与首通呼号标签统一字号 (用户要求 2026-09-21: 字体一样大).
        const val LABEL_TEXT_SIZE = 20f
        const val MIN_LABEL_CELL_PX = 48f
        const val MAX_OVERSHOOT_PX = 64
        /** Center-to-center spacing of the roamed-grid zebra stripes, in px. */
        // 44f spacing (was 16f — too dense per user) with 14f-wide stripes;
        // sparse GridMaster-style zebra.
        const val STRIPE_SPACING_PX = 44f
        /**
         * Reference zoom for stripe spacing: the map's max zoom (MapScreen sets
         * maxZoomLevel = 7.0). At this zoom stripes are STRIPE_SPACING_PX apart;
         * at lower zooms spacing scales by 2^(zoom-max) so the geographic
         * density matches the max-zoom look at every level.
         */
        const val MAX_GRID_ZOOM = 7.0
    }
}
