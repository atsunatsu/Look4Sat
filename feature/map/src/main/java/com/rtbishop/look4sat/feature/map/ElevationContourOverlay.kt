package com.rtbishop.look4sat.feature.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.clipLon
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.round

/**
 * Equal-elevation contours: the observer's look angle to the satellite is constant at
 * each point of a contour. The sub-satellite point is the spherical-circle centre.
 * altitude and EARTH_RADIUS are in km, lat/lon in radians in OrbitalPos.
 */
internal fun elevationContour(satellite: OrbitalPos, elevationDegrees: Double): List<GeoPoint> {
    if (elevationDegrees !in 0.0..90.0 || satellite.altitude <= 0.0) return emptyList()
    val earthRadiusKm = 6370.0
    val ratio = earthRadiusKm / (earthRadiusKm + satellite.altitude)
    val elevation = Math.toRadians(elevationDegrees)
    // sin(elevation) = (cos(beta) - ratio) / sqrt(1 + ratio^2 - 2*ratio*cos(beta)).
    // This closed form chooses the physical, near-side solution.
    val cosBeta = ratio * cos(elevation).let { it * it } +
        sin(elevation) * kotlin.math.sqrt(1.0 - ratio * ratio * cos(elevation) * cos(elevation))
    val beta = acos(cosBeta.coerceIn(-1.0, 1.0))
    val sinLat = sin(satellite.latitude)
    val cosLat = cos(satellite.latitude)
    return (0..360).map { step ->
        val bearing = Math.toRadians(step.toDouble())
        val lat = asin((sinLat * cos(beta) + cosLat * sin(beta) * cos(bearing)).coerceIn(-1.0, 1.0))
        val lon = satellite.longitude + atan2(
            sin(bearing) * sin(beta) * cosLat,
            cos(beta) - sinLat * sin(lat)
        )
        GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon))
    }
}

/**
 * Contours are sampled at one-degree bearings starting from north.
 * Bearing 90° is consistently on the east/right side, even if the circle spans
 * a pole. Choosing the point with maximum longitude can instead jump ~180°.
 */
internal fun elevationContourLabelAnchorIndex(circle: List<GeoPoint>): Int? =
    90.takeIf { circle.size > it }

/**
 * OrbitalPos longitude is 0°..360°, but map state and station positions use
 * clipLon()'s −180°..180° convention. Convert a contour point to that convention,
 * then choose the equivalent world copy nearest the *visible map centre*.
 *
 * Using the satellite's raw 0°..360° longitude here puts western labels a whole
 * world away from the map centre; OSMDroid then draws them at the 180° map edge.
 */
internal fun elevationContourLongitudeInMapWorld(
    longitudeDegrees: Double,
    mapCenterLongitudeDegrees: Double
): Double {
    val canonicalLongitude = clipLon(longitudeDegrees)
    return canonicalLongitude + 360.0 * round(
        (mapCenterLongitudeDegrees - canonicalLongitude) / 360.0
    )
}

/**
 * Project the *unwrapped* longitude to screen pixels. OSMDroid Projection.toPixels()
 * clips longitude to −180°..180° when horizontal repetition is enabled: a label
 * at 183° thus gets projected to exactly 180°, even if the ring continues east.
 * The no-wrap Projection methods preserve the same world copy as the polyline.
 */
internal fun elevationContourToPixels(projection: Projection, position: GeoPoint): Point = Point(
    TileSystem.truncateToInt(projection.getLongPixelXFromLongitude(position.longitude)),
    TileSystem.truncateToInt(projection.getLongPixelYFromLatitude(position.latitude))
)

/** Normalize contour points and split a polyline wherever it crosses the antimeridian. */
internal fun elevationContourSplitAtAntimeridian(points: List<GeoPoint>): List<List<GeoPoint>> {
    val segments = ArrayList<MutableList<GeoPoint>>()
    var segment = ArrayList<GeoPoint>()
    points.forEach { point ->
        val longitude = clipLon(point.longitude)
        if (segment.isNotEmpty() &&
            kotlin.math.abs(segment.last().longitude - longitude) > 180.0
        ) {
            if (segment.size >= 2) segments.add(segment)
            segment = ArrayList()
        }
        segment.add(GeoPoint(point.latitude, longitude))
    }
    if (segment.size >= 2) segments.add(segment)
    return segments
}

/** Duplicate only antimeridian-adjacent arcs into neighbouring OSMDroid world copies. */
internal fun elevationContourTurnsForPoints(points: List<GeoPoint>): IntArray =
    if (points.any { kotlin.math.abs(it.longitude) > 176.0 }) intArrayOf(-1, 0, 1)
    else intArrayOf(0)

/**
 * Shared with the matching-station marker in MapScreen: labels and markers must
 * use precisely the same shade of blue.
 */
internal val MATCHED_STATION_BLUE = 0xFF1976D2.toInt()
private val LOCAL_ELEVATION_YELLOW = 0xFFF5E6A6.toInt()

/**
 * Topocentric elevation of the selected satellite from another ground location.
 *
 * OrbitalPos latitude/longitude are radians; GeoPos uses degrees. Both the
 * matched-station reading and the existing local-station reading use the same
 * selected satellite and scrubbed instant (MapState.footprint).
 */
internal fun elevationAtGroundStation(satellite: OrbitalPos?, station: GeoPos?): Double? {
    if (satellite == null || station == null || !satellite.altitude.isFinite() ||
        satellite.altitude <= 0.0
    ) return null

    val observerLat = Math.toRadians(station.latitude)
    val observerLon = Math.toRadians(station.longitude)
    val cosCentralAngle = (
        sin(satellite.latitude) * sin(observerLat) +
        cos(satellite.latitude) * cos(observerLat) * cos(satellite.longitude - observerLon)
    ).coerceIn(-1.0, 1.0)
    val earthToSatelliteRadius = 6370.0 / (6370.0 + satellite.altitude)
    val sinCentralAngle = sqrt((1.0 - cosCentralAngle * cosCentralAngle).coerceAtLeast(0.0))
    return Math.toDegrees(atan2(cosCentralAngle - earthToSatelliteRadius, sinCentralAngle))
}

internal class ElevationContourOverlay : FolderOverlay() {
    // Soft pastel palette for the dark map: warm inner rings, cooler outer rings.
    // Angles are rendered in ascending order, so keep this order stable:
    // apricot orange → lavender → sage mint → glacier blue.
    private val palette = intArrayOf(
        0xFFEAA064.toInt(), // apricot orange
        0xFFB37BCB.toInt(), // lavender purple
        0xFF73BFA2.toInt(), // sage mint
        0xFF579FE8.toInt()  // glacier blue
    )

    fun update(satellite: OrbitalPos?, elevations: List<Double>, enabled: Boolean, mapView: MapView) {
        items.clear()
        if (!enabled || satellite == null) return
        // MapView/GeoPos use −180°..180°, unlike OrbitalPos's 0°..360° longitude.
        val mapCenterLongitude = mapView.mapCenter.longitude

        elevations.forEachIndexed { index, angle ->
            val circle = elevationContour(satellite, angle)
            if (circle.size < 360) return@forEachIndexed
            val color = palette[index % palette.size]
            val text = if (angle % 1.0 == 0.0) "${angle.toInt()}°" else "${angle}°"

            // Use the contour's due-east bearing as a stable right-side anchor.
            // Maximizing longitude jumps to the far side when a contour encloses
            // a pole (or the satellite passes near one).
            val anchorIndex = elevationContourLabelAnchorIndex(circle) ?: return@forEachIndexed
            val anchorPoint = circle[anchorIndex]
            // Store the canonical location; the label overlay selects the nearest
            // world copy dynamically so it remains correct while panning across ±180°.
            val anchorGeo = GeoPoint(anchorPoint.latitude, clipLon(anchorPoint.longitude))
            val anchorProjectionGeo = GeoPoint(
                anchorPoint.latitude,
                elevationContourLongitudeInMapWorld(anchorPoint.longitude, mapCenterLongitude)
            )
            val anchorPx = elevationContourToPixels(mapView.projection, anchorProjectionGeo)
            val labelTextSizePx = 12f * mapView.context.resources.displayMetrics.scaledDensity
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelTextSizePx }
            val halfGapPx = labelPaint.measureText(text) / 2f + 9f

            // Cut enough actual contour points to fit the label in SCREEN pixels.
            // A fixed angular gap would become tiny on zoom-in and huge on zoom-out.
            fun gapSteps(direction: Int): Int {
                var count = 0
                for (step in 1..70) {
                    val sample = circle[(anchorIndex + direction * step + 360) % 360]
                    val sampleGeo = GeoPoint(
                        sample.latitude,
                        elevationContourLongitudeInMapWorld(sample.longitude, mapCenterLongitude)
                    )
                    val p = elevationContourToPixels(mapView.projection, sampleGeo)
                    val distance = kotlin.math.hypot(
                        (p.x - anchorPx.x).toDouble(), (p.y - anchorPx.y).toDouble()
                    )
                    // Projection can wrap at the anti-meridian; do not interpret
                    // that world-width jump as a long segment of the contour.
                    if (distance > halfGapPx && distance < mapView.width.coerceAtLeast(1) * 0.8) break
                    count++
                }
                return count
            }
            val gap = maxOf(gapSteps(-1), gapSteps(1)).coerceAtMost(70)
            val startIndex = (anchorIndex + gap + 1) % 360
            val pointCount = 360 - 2 * gap - 1

            // Start immediately after the deleted right-hand section, traverse
            // the rest of the ring and stop before the gap. This also keeps the
            // top of the circle continuous instead of splitting at bearing=0.
            val contourPoints = (0 until pointCount).map { offset ->
                val point = circle[(startIndex + offset) % 360]
                GeoPoint(point.latitude, clipLon(point.longitude))
            }
            elevationContourSplitAtAntimeridian(contourPoints).forEach { arc ->
                elevationContourTurnsForPoints(arc).forEach { turn ->
                    val offset = turn * 360.0
                    val points = if (offset == 0.0) arc else arc.map {
                        GeoPoint(it.latitude, it.longitude + offset)
                    }
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color
                        alpha = 217
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                        pathEffect = DashPathEffect(floatArrayOf(13f, 9f), 0f)
                    }
                    add(Polyline().apply {
                        outlinePaint.set(stroke)
                        setPoints(points)
                    })
                }
            }
            // Text is a separate overlay *above* the segmented dashed lines;
            // it takes exactly the space cleared on the ring's rightmost edge.
            add(ElevationCircleLabelOverlay(anchorGeo, text, color, labelTextSizePx))
        }
    }
}

private class ElevationCircleLabelOverlay(
    private val anchor: GeoPoint,
    private val text: String,
    private val color: Int,
    private val textSizePx: Float
) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@ElevationCircleLabelOverlay.color
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.BLACK
        alpha = 210
        textSize = textSizePx
        strokeWidth = 3f
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
    }
    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val visibleWorldAnchor = GeoPoint(
            anchor.latitude,
            elevationContourLongitudeInMapWorld(anchor.longitude, mapView.mapCenter.longitude)
        )
        val point = elevationContourToPixels(mapView.projection, visibleWorldAnchor)
        // Paint baseline shifted so the number sits centrally in the missing section.
        val baseline = point.y - (fill.ascent() + fill.descent()) / 2f
        canvas.drawText(text, point.x.toFloat(), baseline, outline)
        canvas.drawText(text, point.x.toFloat(), baseline, fill)
    }
}

/**
 * On-map elevation readouts at both ground stations. The local reading is
 * supplied by the existing predictor, while the matched station's elevation
 * is calculated from the same OrbitalPos instant.
 */
internal class StationElevationOverlay : Overlay() {
    var position: GeoPos? = null
    var elevation: Double? = null
    var otherPosition: GeoPos? = null
    var otherElevation: Double? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        style = Paint.Style.FILL
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        drawElevation(canvas, mapView, position, elevation, LOCAL_ELEVATION_YELLOW)
        drawElevation(canvas, mapView, otherPosition, otherElevation, MATCHED_STATION_BLUE)
    }

    private fun drawElevation(
        canvas: Canvas,
        mapView: MapView,
        station: GeoPos?,
        value: Double?,
        color: Int
    ) {
        val position = station ?: return
        val angle = value?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val point = mapView.projection.toPixels(GeoPoint(position.latitude, position.longitude), Point())
        val label = String.format(java.util.Locale.US, "%.1f°", angle)
        val x = point.x + 18f
        val y = point.y - 12f
        textPaint.color = color
        canvas.drawText(label, x, y, outlinePaint)
        canvas.drawText(label, x, y, textPaint)
    }
}
