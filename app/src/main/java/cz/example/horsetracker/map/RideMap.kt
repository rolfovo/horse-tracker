package cz.example.horsetracker.map

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.example.horsetracker.geo.Geo
import cz.example.horsetracker.ride.MapState
import cz.example.horsetracker.ride.SpeedSegment
import cz.example.horsetracker.ride.Waypoint
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonOptions
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RideMap(modifier: Modifier = Modifier, mapState: MapState, autoCenter: Boolean, onWaypointTap: (Waypoint) -> Unit = {}) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle(context)
    val controller = remember { MapController() }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view -> updateMap(view, mapState, autoCenter, controller, onWaypointTap) },
    )
}

private class MapController {
    var hasInitialCamera = false
    var lastCameraUpdateMs: Long = 0L
    var lastCameraLat: Double? = null
    var lastCameraLon: Double? = null
    var lastFollowHash: Int = 0
    var latestState: MapState = MapState()
    var onWaypointTap: ((Waypoint) -> Unit)? = null
    var clickListenerAttached = false
}

@Composable
private fun rememberMapViewWithLifecycle(context: Context): MapView {
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, mapView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return mapView
}

private const val SOURCE_SEGMENTS = "segments"
private const val SOURCE_WAYPOINTS = "waypoints"
private const val SOURCE_FOLLOW = "follow"
private const val SOURCE_USER = "user"
private const val SOURCE_USER_ARROW = "user_arrow"
private const val SOURCE_SNAP = "snap"
private val DEFAULT_MAP_CENTER = LatLng(49.8175, 15.4730)
private const val DEFAULT_MAP_ZOOM = 7.2
private const val TRACK_SOURCE_BUFFER = 256
private const val TRACK_SOURCE_TOLERANCE = 0.0f

private fun updateMap(
    mapView: MapView,
    mapState: MapState,
    autoCenter: Boolean,
    controller: MapController,
    onWaypointTap: (Waypoint) -> Unit,
) {
    mapView.getMapAsync { map ->
        controller.latestState = mapState
        controller.onWaypointTap = onWaypointTap
        if (!controller.clickListenerAttached) {
            map.addOnMapClickListener { latLng ->
                val waypoint =
                    controller.latestState.waypoints.minByOrNull { wp ->
                        Geo.haversineMeters(latLng.latitude, latLng.longitude, wp.lat, wp.lon)
                    }
                if (waypoint != null) {
                    val distance = Geo.haversineMeters(latLng.latitude, latLng.longitude, waypoint.lat, waypoint.lon)
                    if (distance <= 35.0) {
                        controller.onWaypointTap?.invoke(waypoint)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            controller.clickListenerAttached = true
        }

        if (map.style == null) {
            map.setStyle(buildOsmRasterStyle(mapView.context)) { style ->
                attachLayers(style)
                render(style, mapState)
            }
        } else {
            render(map.style!!, mapState)
        }

        val followHash = mapState.followRoute.hashCode()
        val followChanged = followHash != controller.lastFollowHash
        if (followChanged && mapState.followRoute.size >= 2) {
            controller.lastFollowHash = followHash
            val bounds = buildBounds(mapState.followRoute)
            if (bounds != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                controller.hasInitialCamera = true
            }
        }

        val lat = mapState.userLat
        val lon = mapState.userLon
        if (!controller.hasInitialCamera) {
            when {
                lat != null && lon != null -> {
                    controller.hasInitialCamera = true
                    controller.lastCameraUpdateMs = SystemClock.elapsedRealtime()
                    controller.lastCameraLat = lat
                    controller.lastCameraLon = lon
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.5))
                }
                mapState.followRoute.size >= 2 -> {
                    val bounds = buildBounds(mapState.followRoute)
                    if (bounds != null) {
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                        controller.hasInitialCamera = true
                    }
                }
                else -> {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM))
                    controller.hasInitialCamera = true
                }
            }
        }

        if (autoCenter && lat != null && lon != null) {
            // Nehebej mapou při každém GPS bodu. Jen úvodní centrování + následně throttle.
            val now = SystemClock.elapsedRealtime()
            val movedEnough =
                controller.lastCameraLat == null ||
                    controller.lastCameraLon == null ||
                    distanceMetersApprox(lat, lon, controller.lastCameraLat!!, controller.lastCameraLon!!) > 8.0
            val throttleOk = (now - controller.lastCameraUpdateMs) > 2500

            if (movedEnough && throttleOk) {
                controller.hasInitialCamera = true
                controller.lastCameraUpdateMs = now
                controller.lastCameraLat = lat
                controller.lastCameraLon = lon
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.5))
            }
        }
    }
}

private fun distanceMetersApprox(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dx = (lon2 - lon1) * 111320.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
    val dy = (lat2 - lat1) * 110540.0
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun buildOsmRasterStyle(context: Context): Style.Builder {
    val localTilesRoot = "${context.filesDir.absolutePath.replace("\\", "/")}/offline_tiles"
    val localTemplate = "file://$localTilesRoot/{z}/{x}/{y}.png"
    val styleJson =
        """
        {
          "version": 8,
          "sources": {
            "osm_online": {
              "type": "raster",
              "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "attribution": "© OpenStreetMap contributors"
            },
            "osm_offline": {
              "type": "raster",
              "tiles": ["$localTemplate"],
              "tileSize": 256
            }
          },
          "layers": [
            {
              "id": "base_background",
              "type": "background",
              "paint": {
                "background-color": "#efe7db"
              }
            },
            {"id": "osm_online", "type": "raster", "source": "osm_online"},
            {"id": "osm_offline", "type": "raster", "source": "osm_offline"}
          ]
        }
        """.trimIndent()
    return Style.Builder().fromJson(styleJson)
}

private fun attachLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE_SEGMENTS, FeatureCollection.fromFeatures(emptyArray()), trackGeoJsonOptions()))
    style.addSource(GeoJsonSource(SOURCE_WAYPOINTS, FeatureCollection.fromFeatures(emptyArray())))
    style.addSource(GeoJsonSource(SOURCE_FOLLOW, FeatureCollection.fromFeatures(emptyArray()), trackGeoJsonOptions()))
    style.addSource(GeoJsonSource(SOURCE_USER, FeatureCollection.fromFeatures(emptyArray())))
    style.addSource(GeoJsonSource(SOURCE_USER_ARROW, FeatureCollection.fromFeatures(emptyArray())))
    style.addSource(GeoJsonSource(SOURCE_SNAP, FeatureCollection.fromFeatures(emptyArray())))

    val speedColor = speedColorExpression()

    style.addLayer(
        LineLayer("ride_line", SOURCE_SEGMENTS).withProperties(
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(speedColor),
        ),
    )

    style.addLayer(
        LineLayer("follow_line", SOURCE_FOLLOW).withProperties(
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.95f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(speedColor),
        ),
    )

    style.addLayer(
        CircleLayer("waypoints", SOURCE_WAYPOINTS).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(Expression.color(Color.argb(255, 255, 255, 255))),
            PropertyFactory.circleStrokeColor(Expression.color(Color.argb(255, 0, 0, 0))),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )

    style.addLayer(
        CircleLayer("user_point", SOURCE_USER).withProperties(
            PropertyFactory.circleRadius(4f),
            PropertyFactory.circleColor(Expression.color(Color.argb(255, 0, 188, 212))),
            PropertyFactory.circleStrokeColor(Expression.color(Color.argb(255, 255, 255, 255))),
            PropertyFactory.circleStrokeWidth(1.5f),
        ),
    )

    style.addLayer(
        FillLayer("user_arrow", SOURCE_USER_ARROW).withProperties(
            PropertyFactory.fillColor(Expression.color(Color.argb(255, 0, 188, 212))),
            PropertyFactory.fillOpacity(0.95f),
        ),
    )

    style.addLayer(
        LineLayer("snap_line", SOURCE_SNAP).withProperties(
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineColor(Expression.color(Color.argb(255, 156, 39, 176))),
            PropertyFactory.lineDasharray(arrayOf(1.0f, 2.0f)),
        ),
    )
}

private fun trackGeoJsonOptions(): GeoJsonOptions =
    GeoJsonOptions()
        .withBuffer(TRACK_SOURCE_BUFFER)
        .withTolerance(TRACK_SOURCE_TOLERANCE)

private fun speedColorExpression(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.get("speed_mps"),
        Expression.stop(0.0, Expression.color(Color.argb(255, 35, 107, 173))),   // very slow
        Expression.stop(0.7, Expression.color(Color.argb(255, 45, 158, 120))),   // walk start ~2.5 km/h
        Expression.stop(1.25, Expression.color(Color.argb(255, 92, 184, 92))),   // walk upper ~4.5 km/h
        Expression.stop(1.9, Expression.color(Color.argb(255, 177, 196, 74))),   // trot start ~6.8 km/h
        Expression.stop(2.58, Expression.color(Color.argb(255, 240, 183, 63))),  // trot upper ~9.3 km/h
        Expression.stop(3.28, Expression.color(Color.argb(255, 235, 126, 63))),  // canter start ~11.8 km/h
        Expression.stop(5.0, Expression.color(Color.argb(255, 214, 74, 62))),    // canter max ~18.0 km/h
    )

private fun render(style: Style, state: MapState) {
    style.getSourceAs<GeoJsonSource>(SOURCE_SEGMENTS)?.setGeoJson(FeatureCollection.fromFeatures(buildSegmentFeatures(state.segments)))

    val wpFeatures = state.waypoints.map { w -> Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)) }
    style.getSourceAs<GeoJsonSource>(SOURCE_WAYPOINTS)?.setGeoJson(FeatureCollection.fromFeatures(wpFeatures))

    val followFeatures = buildSegmentFeatures(state.followSegments, state.followActive)
    style.getSourceAs<GeoJsonSource>(SOURCE_FOLLOW)?.setGeoJson(FeatureCollection.fromFeatures(followFeatures))

    val userFeature =
        if (state.userLat != null && state.userLon != null) {
            Feature.fromGeometry(Point.fromLngLat(state.userLon, state.userLat))
        } else {
            null
        }
    style.getSourceAs<GeoJsonSource>(SOURCE_USER)?.setGeoJson(
        FeatureCollection.fromFeatures(listOfNotNull(userFeature)),
    )

    val arrowFeature =
        if (state.userLat != null && state.userLon != null && state.userHeadingDeg != null) {
            Feature.fromGeometry(buildArrowPolygon(state.userLat, state.userLon, state.userHeadingDeg))
        } else {
            null
        }
    style.getSourceAs<GeoJsonSource>(SOURCE_USER_ARROW)?.setGeoJson(
        FeatureCollection.fromFeatures(listOfNotNull(arrowFeature)),
    )

    val snapFeature =
        if (state.userLat != null && state.userLon != null && state.snapLat != null && state.snapLon != null) {
            Feature.fromGeometry(
                LineString.fromLngLats(
                    listOf(
                        Point.fromLngLat(state.userLon, state.userLat),
                        Point.fromLngLat(state.snapLon, state.snapLat),
                    ),
                ),
            )
        } else {
            null
        }
    style.getSourceAs<GeoJsonSource>(SOURCE_SNAP)?.setGeoJson(
        FeatureCollection.fromFeatures(listOfNotNull(snapFeature)),
    )
}

private fun buildBounds(route: List<Pair<Double, Double>>): LatLngBounds? {
    if (route.size < 2) return null
    val builder = LatLngBounds.Builder()
    route.forEach { (lat, lon) -> builder.include(LatLng(lat, lon)) }
    return builder.build()
}

private fun buildSegmentFeatures(segments: List<SpeedSegment>, active: Boolean = false): List<Feature> =
    segments.map { segment ->
        Feature.fromGeometry(
            LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(segment.startLon, segment.startLat),
                    Point.fromLngLat(segment.endLon, segment.endLat),
                ),
            ),
        ).apply {
            addNumberProperty("speed_mps", segment.speedMps)
            addNumberProperty("active", if (active) 1.0 else 0.0)
        }
    }

private fun buildArrowPolygon(lat: Double, lon: Double, headingDeg: Double): Polygon {
    val heading = Math.toRadians(headingDeg)
    val length = 12.0
    val halfWidth = 5.5
    val backOffset = 3.0

    val tip = offsetMeters(lat, lon, length * cos(heading), length * sin(heading))
    val left = offsetMeters(lat, lon, -backOffset * cos(heading) - halfWidth * sin(heading), -backOffset * sin(heading) + halfWidth * cos(heading))
    val right = offsetMeters(lat, lon, -backOffset * cos(heading) + halfWidth * sin(heading), -backOffset * sin(heading) - halfWidth * cos(heading))

    val ring =
        listOf(
            Point.fromLngLat(tip.second, tip.first),
            Point.fromLngLat(left.second, left.first),
            Point.fromLngLat(right.second, right.first),
            Point.fromLngLat(tip.second, tip.first),
        )
    return Polygon.fromLngLats(listOf(ring))
}

private fun offsetMeters(lat: Double, lon: Double, northM: Double, eastM: Double): Pair<Double, Double> {
    val dLat = northM / 110540.0
    val dLon = eastM / (111320.0 * cos(lat * PI / 180.0))
    return (lat + dLat) to (lon + dLon)
}
