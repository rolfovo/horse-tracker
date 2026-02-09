package cz.example.horsetracker.ride

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RideStorage {
    data class Ride(val points: List<TrackPoint>, val waypoints: List<Waypoint>)

    fun writeRide(file: File, points: List<TrackPoint>, waypoints: List<Waypoint>) {
        val root = JSONObject()
        root.put("type", "FeatureCollection")
        val features = JSONArray()

        val track = JSONObject()
        track.put("type", "Feature")
        track.put("geometry", JSONObject().apply {
            put("type", "LineString")
            put("coordinates", JSONArray().apply {
                points.forEach { p -> put(JSONArray().put(p.lon).put(p.lat)) }
            })
        })
        track.put("properties", JSONObject().apply {
            put("points", JSONArray().apply {
                points.forEach { p ->
                    put(
                        JSONObject()
                            .put("t", p.timeEpochMs)
                            .put("s", p.speedMps)
                            .put("a", p.accuracyM),
                    )
                }
            })
        })
        features.put(track)

        waypoints.forEach { w ->
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            .put("coordinates", JSONArray().put(w.lon).put(w.lat)),
                    )
                    .put(
                        "properties",
                        JSONObject()
                            .put("t", w.timeEpochMs)
                            .put("label", w.label ?: JSONObject.NULL),
                    ),
            )
        }

        root.put("features", features)
        file.writeText(root.toString(2))
    }

    fun readRide(file: File): Ride {
        val json = JSONObject(file.readText())
        val features = json.getJSONArray("features")

        var points: List<TrackPoint> = emptyList()
        val waypoints = ArrayList<Waypoint>()

        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val geom = f.getJSONObject("geometry")
            val type = geom.getString("type")
            if (type == "LineString") {
                val coords = geom.getJSONArray("coordinates")
                val props = f.optJSONObject("properties")
                val pointsMeta = props?.optJSONArray("points")

                val tmp = ArrayList<TrackPoint>(coords.length())
                for (j in 0 until coords.length()) {
                    val c = coords.getJSONArray(j)
                    val lon = c.getDouble(0)
                    val lat = c.getDouble(1)
                    val meta = pointsMeta?.optJSONObject(j)
                    tmp.add(
                        TrackPoint(
                            lat = lat,
                            lon = lon,
                            timeEpochMs = meta?.optLong("t") ?: 0L,
                            speedMps = meta?.optDouble("s") ?: 0.0,
                            accuracyM = meta?.optDouble("a") ?: 0.0,
                        ),
                    )
                }
                points = tmp
            } else if (type == "Point") {
                val coords = geom.getJSONArray("coordinates")
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)
                val props = f.optJSONObject("properties")
                waypoints.add(
                    Waypoint(
                        lat = lat,
                        lon = lon,
                        timeEpochMs = props?.optLong("t") ?: 0L,
                        label = props?.optString("label", null),
                    ),
                )
            }
        }

        return Ride(points = points, waypoints = waypoints)
    }
}

