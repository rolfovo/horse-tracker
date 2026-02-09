package cz.example.horsetracker.ride

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timeEpochMs: Long,
    val speedMps: Double,
    val accuracyM: Double,
    val headingDeg: Double? = null,
)

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val timeEpochMs: Long,
    val label: String? = null,
)

data class Horse(
    val id: String,
    val name: String,
)

data class RideStats(
    val ridesCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val totalDistanceM: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
)

data class RideSummary(
    val horseId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceM: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val gpxFileName: String,
    val metaFileName: String,
)

data class MapState(
    val userLat: Double? = null,
    val userLon: Double? = null,
    val userHeadingDeg: Double? = null,
    val snapLat: Double? = null,
    val snapLon: Double? = null,
    val segments: List<SpeedSegment> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val followRoute: List<Pair<Double, Double>> = emptyList(),
)

data class SpeedSegment(
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val speedMps: Double,
)

data class AppState(
    val isRecording: Boolean = false,
    val isFollowing: Boolean = false,
    val isReversed: Boolean = false,
    val isAutoCenter: Boolean = true,
    val selectedHorseId: String? = null,
    val horses: List<Horse> = emptyList(),
    val horseStats: Map<String, RideStats> = emptyMap(),
    val rides: List<RideSummary> = emptyList(),
    val points: List<TrackPoint> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val routeToFollow: List<Pair<Double, Double>> = emptyList(),
    val lastSpeedMps: Double = 0.0,
    val lastAccuracyM: Double = 0.0,
    val lastHeadingDeg: Double? = null,
    val currentDistanceM: Double = 0.0,
    val currentDurationMs: Long = 0L,
    val currentAvgSpeedMps: Double = 0.0,
    val offRouteMeters: Double = 0.0,
    val mapState: MapState = MapState(),
)
