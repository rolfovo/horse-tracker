package cz.example.horsetracker.ride

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DraftRideStorage {
    data class DraftRide(
        val selectedHorseId: String?,
        val points: List<TrackPoint>,
        val waypoints: List<Waypoint>,
    )

    fun writeDraft(context: android.content.Context, selectedHorseId: String?, points: List<TrackPoint>, waypoints: List<Waypoint>) {
        val root =
            JSONObject()
                .put("selectedHorseId", selectedHorseId ?: JSONObject.NULL)
                .put(
                    "points",
                    JSONArray().apply {
                        points.forEach { point ->
                            put(
                                JSONObject()
                                    .put("lat", point.lat)
                                    .put("lon", point.lon)
                                    .put("timeEpochMs", point.timeEpochMs)
                                    .put("speedMps", point.speedMps)
                                    .put("accuracyM", point.accuracyM)
                                    .put("headingDeg", point.headingDeg ?: JSONObject.NULL),
                            )
                        }
                    },
                ).put(
                    "waypoints",
                    JSONArray().apply {
                        waypoints.forEach { waypoint ->
                            put(
                                JSONObject()
                                    .put("lat", waypoint.lat)
                                    .put("lon", waypoint.lon)
                                    .put("timeEpochMs", waypoint.timeEpochMs)
                                    .put("label", waypoint.label ?: JSONObject.NULL),
                            )
                        }
                    },
                )

        file(context).parentFile?.mkdirs()
        file(context).writeText(root.toString())
    }

    fun readDraft(context: android.content.Context): DraftRide? {
        val file = file(context)
        if (!file.exists()) return null

        val json = JSONObject(file.readText())
        val pointsJson = json.optJSONArray("points") ?: JSONArray()
        val waypointsJson = json.optJSONArray("waypoints") ?: JSONArray()

        val points =
            buildList(pointsJson.length()) {
                for (i in 0 until pointsJson.length()) {
                    val item = pointsJson.getJSONObject(i)
                    add(
                        TrackPoint(
                            lat = item.getDouble("lat"),
                            lon = item.getDouble("lon"),
                            timeEpochMs = item.optLong("timeEpochMs"),
                            speedMps = item.optDouble("speedMps"),
                            accuracyM = item.optDouble("accuracyM"),
                            headingDeg = item.optDouble("headingDeg").takeIf { !item.isNull("headingDeg") },
                        ),
                    )
                }
            }

        val waypoints =
            buildList(waypointsJson.length()) {
                for (i in 0 until waypointsJson.length()) {
                    val item = waypointsJson.getJSONObject(i)
                    add(
                        Waypoint(
                            lat = item.getDouble("lat"),
                            lon = item.getDouble("lon"),
                            timeEpochMs = item.optLong("timeEpochMs"),
                            label = item.optString("label").takeIf { !item.isNull("label") },
                        ),
                    )
                }
            }

        if (points.isEmpty() && waypoints.isEmpty()) return null

        return DraftRide(
            selectedHorseId = json.optString("selectedHorseId").takeIf { !json.isNull("selectedHorseId") },
            points = points,
            waypoints = waypoints,
        )
    }

    fun clear(context: android.content.Context) {
        file(context).delete()
    }

    private fun file(context: android.content.Context): File = File(File(context.filesDir, "rides"), "active_ride_draft.json")
}
