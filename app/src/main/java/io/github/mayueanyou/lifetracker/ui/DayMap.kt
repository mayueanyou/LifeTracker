package io.github.mayueanyou.lifetracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mayueanyou.lifetracker.engine.Entry
import io.github.mayueanyou.lifetracker.engine.Fix
import io.github.mayueanyou.lifetracker.engine.Geo
import io.github.mayueanyou.lifetracker.engine.Mode
import io.github.mayueanyou.lifetracker.engine.RoutePoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.max

val DRIVING = 0xFF0EA5E9.toInt()
val WALKING = 0xFF22C55E.toInt()
val CYCLING = 0xFFA78BFA.toInt()
val SELECTED = 0xFFFB923C.toInt()
val PIN = 0xFF6366F1.toInt()
val PIN_ON = 0xFFF97316.toInt()

fun modeColour(mode: Mode) = when (mode) {
    Mode.DRIVING -> DRIVING
    Mode.WALKING -> WALKING
    Mode.CYCLING -> CYCLING
    Mode.MOVING -> DRIVING
}

/**
 * The day on a map, drawn the way the Record web app draws it: each journey
 * as its route in its mode's colour, a sample of its points as dots, arrows
 * riding inside some of them to show the way you went, and one numbered pin
 * per place -- a place you returned to shares one pin ("1·3"). Whatever is
 * selected turns orange, is drawn on top, and the other routes fade right back.
 */
@Composable
fun DayMap(
    entries: List<Entry>,
    selected: Int?,
    here: Fix?,
    fallback: Fix?,
    centreOn: Fix?,
    centreRequest: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(13.0)
        }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { draw(it, context, entries, selected, here, onSelect) })

    // A new day is fitted to the screen once; selecting within it moves to the selection.
    LaunchedEffect(entries, fallback) { fit(map, entries, here ?: fallback) }
    LaunchedEffect(selected) { if (selected != null) focus(map, entries, selected) }
    LaunchedEffect(centreRequest) {
        val at = centreOn ?: return@LaunchedEffect
        map.controller.animateTo(GeoPoint(at.lat, at.lon), max(map.zoomLevelDouble, 16.0), 400L)
    }
}

private class Spot(val indices: MutableList<Int>, val name: String?, val lat: Double, val lon: Double)

private fun draw(map: MapView, context: Context, entries: List<Entry>, selected: Int?, here: Fix?, onSelect: (Int) -> Unit) {
    map.overlays.clear()
    val density = context.resources.displayMetrics.density
    val pickedRoute = selected != null && entries.getOrNull(selected) is Entry.JourneyEntry

    val routes = ArrayList<DotsOverlay.Route>()
    // The picked journey goes last, so it is drawn over any other that runs along the same streets.
    val order = entries.indices.sortedBy { it == selected }
    order.forEach { i ->
        val e = entries[i]
        if (e !is Entry.JourneyEntry) return@forEach
        val j = e.journey
        val on = i == selected
        val colour = if (on) SELECTED else modeColour(j.mode)
        // With one journey picked the others fall well back -- faint, and without arrows.
        val alpha = if (on) 1f else if (pickedRoute) 0.15f else 0.6f
        val points = if (j.route.size > 1) j.route.map { GeoPoint(it.lat, it.lon) }
        else listOf(GeoPoint(j.fromLat, j.fromLon), GeoPoint(j.toLat, j.toLon))
        val line = Polyline(map).apply {
            setPoints(points)
            outlinePaint.color = withAlpha(colour, alpha)
            outlinePaint.strokeWidth = (if (on) 6f else 3f) * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            if (j.route.size <= 1) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * density, 8 * density), 0f)
            setOnClickListener { _, _, _ -> onSelect(i); true }
        }
        map.overlays.add(line)
        if (j.route.size > 1) routes += DotsOverlay.Route(j.route, colour, if (on) 1f else if (pickedRoute) 0.18f else 0.95f, on,
            quiet = pickedRoute && !on)
    }
    map.overlays.add(DotsOverlay(routes, density))

    // One pin per place: the same name nearby, or an unnamed spot within 80 m.
    val spots = ArrayList<Spot>()
    var stayNo = 0
    val labels = HashMap<Int, Int>()
    entries.forEachIndexed { i, e ->
        if (e !is Entry.StayEntry) return@forEachIndexed
        stayNo++
        labels[i] = stayNo
        val s = e.stay
        val name = s.place?.takeIf { it != "Unknown place" }
        val spot = spots.firstOrNull { sp ->
            val first = (entries[sp.indices[0]] as Entry.StayEntry).stay
            val apart = Geo.metres(first.lat, first.lon, s.lat, s.lon)
            if (name != null) sp.name == name && apart < 500 else sp.name == null && apart < 80
        }
        if (spot != null) spot.indices += i else spots += Spot(mutableListOf(i), name, s.lat, s.lon)
    }
    for (spot in spots) {
        val stays = spot.indices.map { (entries[it] as Entry.StayEntry).stay }
        val lat = stays.map { it.lat }.average()
        val lon = stays.map { it.lon }.average()
        val text = spot.indices.joinToString("·") { labels[it].toString() }
        val on = selected in spot.indices
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(lat, lon)
            icon = BitmapDrawable(context.resources, pinBitmap(text, if (on) PIN_ON else PIN, density, on))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = stays.first().place
            setOnMarkerClickListener { _, _ -> onSelect(spot.indices.first()); true }
        })
    }

    if (here != null) {
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(here.lat, here.lon)
            icon = BitmapDrawable(context.resources, hereBitmap(density))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> true }
        })
    }
    map.invalidate()
}

private fun fit(map: MapView, entries: List<Entry>, here: Fix?) {
    val points = ArrayList<GeoPoint>()
    for (e in entries) when (e) {
        is Entry.StayEntry -> points += GeoPoint(e.stay.lat, e.stay.lon)
        is Entry.JourneyEntry -> e.journey.route.forEach { points += GeoPoint(it.lat, it.lon) }
    }
    if (points.isEmpty() && here != null) points += GeoPoint(here.lat, here.lon)
    val apply = {
        when {
            points.size > 1 -> map.zoomToBoundingBox(boxAround(points), false)
            points.size == 1 -> { map.controller.setZoom(16.0); map.controller.setCenter(points[0]) }
        }
    }
    // Fitting a box needs the map's size; before its first layout osmdroid
    // knows none and falls back to a view of the whole world.
    if (map.isLayoutOccurred) map.post { apply() }
    else map.addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
}

private fun focus(map: MapView, entries: List<Entry>, index: Int) {
    when (val e = entries.getOrNull(index)) {
        is Entry.JourneyEntry -> {
            val pts = e.journey.route.map { GeoPoint(it.lat, it.lon) }
            if (pts.size > 1) map.zoomToBoundingBox(boxAround(pts), true)
        }
        is Entry.StayEntry -> {
            map.controller.animateTo(GeoPoint(e.stay.lat, e.stay.lon), max(map.zoomLevelDouble, 16.0), 400L)
        }
        null -> Unit
    }
}

/**
 * The points' box with a margin, never thinner than a few hundred metres
 * either way: a route straight north or along one street has a box of no
 * width, and osmdroid zooms such a box out to the whole wrong side of the world.
 */
private fun boxAround(points: List<GeoPoint>): BoundingBox {
    val box = BoundingBox.fromGeoPoints(points)
    val minSpan = 0.003   // degrees, about 300 m
    val latPad = max(box.latitudeSpan * 0.15, (minSpan - box.latitudeSpan) / 2)
    // Plain east minus west: osmdroid's date-line span reads a box of no width as the whole globe.
    val lonSpan = box.lonEast - box.lonWest
    val lonPad = max(lonSpan * 0.15, (minSpan - lonSpan) / 2)
    return BoundingBox(box.latNorth + latPad, box.lonEast + lonPad, box.latSouth - latPad, box.lonWest - lonPad)
}

private fun withAlpha(colour: Int, alpha: Float) = (colour and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

private fun pinBitmap(text: String, colour: Int, density: Float, on: Boolean): Bitmap {
    val h = (if (on) 32 else 26) * density
    val w = max(h, h + (text.length - 1) * 7 * density)
    val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * density }
    val rect = android.graphics.RectF(density, density, w - density, h - density)
    c.drawRoundRect(rect, h / 2, h / 2, fill)
    c.drawRoundRect(rect, h / 2, h / 2, ring)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12 * density; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    c.drawText(text, w / 2, h / 2 - (label.descent() + label.ascent()) / 2, label)
    return bmp
}

private fun hereBitmap(density: Float): Bitmap {
    val size = (18 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawCircle(size / 2f, size / 2f, size / 2f - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() })
    c.drawCircle(size / 2f, size / 2f, size / 2f - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * density
    })
    return bmp
}

/**
 * The dots along each route, and the arrows among them. Dots are a sample --
 * one every 150 m at most twenty a route -- and arrows sit every 120 m, at
 * most twelve, each aimed at a point 30 m further on so GPS wobble does not
 * make it twitch. Drawn on the map's own canvas, turned to the screen at
 * every redraw, so they stay true as the map zooms.
 */
private class DotsOverlay(private val routes: List<Route>, private val density: Float) : Overlay() {
    class Route(val points: List<RoutePoint>, val colour: Int, val alpha: Float, val selected: Boolean, val quiet: Boolean = false)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = map.projection
        val p = Point()
        val q = Point()
        for (route in routes) {
            val along = progress(route.points)
            val arrows = if (route.quiet) emptySet() else arrowPoints(along)
            val dots = dotPoints(along, arrows)
            fill.color = withAlpha(route.colour, route.alpha)
            for (k in route.points.indices) {
                if (k !in dots && k !in arrows) continue
                val pt = route.points[k]
                projection.toPixels(GeoPoint(pt.lat, pt.lon), p)
                if (k in arrows) {
                    val r = (if (route.selected) 7.5f else 6.5f) * density
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, fill)
                    val aim = ahead(route.points, along, k)
                    projection.toPixels(GeoPoint(aim.lat, aim.lon), q)
                    val angle = Math.toDegrees(atan2((q.y - p.y).toDouble(), (q.x - p.x).toDouble())).toFloat()
                    canvas.save()
                    canvas.translate(p.x.toFloat(), p.y.toFloat())
                    canvas.rotate(angle)
                    val chevron = Path().apply {
                        moveTo(r * .55f, 0f); lineTo(-r * .4f, -r * .5f); lineTo(-r * .15f, 0f); lineTo(-r * .4f, r * .5f); close()
                    }
                    canvas.drawPath(chevron, white)
                    canvas.restore()
                } else {
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), (if (route.selected) 3.4f else 2.6f) * density, fill)
                }
            }
        }
    }

    private fun progress(points: List<RoutePoint>): DoubleArray {
        val along = DoubleArray(points.size)
        for (k in 1 until points.size) along[k] = along[k - 1] + Geo.metres(points[k - 1].lat, points[k - 1].lon, points[k].lat, points[k].lon)
        return along
    }

    private fun arrowPoints(along: DoubleArray): Set<Int> {
        val total = along.lastOrNull() ?: return emptySet()
        val spacing = max(120.0, total / 12)
        val picked = HashSet<Int>()
        var next = spacing / 2
        for (k in 0 until along.size - 1) if (along[k] >= next) { picked += k; next = along[k] + spacing }
        if (picked.isEmpty() && along.size > 2) picked += along.size / 2
        return picked
    }

    private fun dotPoints(along: DoubleArray, arrows: Set<Int>): Set<Int> {
        val total = along.lastOrNull() ?: return emptySet()
        val spacing = max(150.0, total / 20)
        val picked = HashSet<Int>()
        var last = Double.NEGATIVE_INFINITY
        for (k in along.indices) {
            if (k in arrows) { last = along[k]; continue }
            if (k == 0 || k == along.size - 1 || along[k] - last >= spacing) { picked += k; last = along[k] }
        }
        return picked
    }

    private fun ahead(points: List<RoutePoint>, along: DoubleArray, k: Int): RoutePoint {
        var j = k + 1
        while (j < points.size - 1 && along[j] - along[k] < 30) j++
        return points[j]
    }
}
