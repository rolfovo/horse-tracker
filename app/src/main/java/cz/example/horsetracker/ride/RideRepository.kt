package cz.example.horsetracker.ride

import android.content.Context
import cz.example.horsetracker.geo.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RideRepository {
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    sealed interface UiEvent {
        data class Message(val text: String) : UiEvent
        data class RideSaved(val filePath: String) : UiEvent
    }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        ioScope.launch {
            val horses = HorseStorage.listHorses(context)
            val selected = SelectionStorage.getSelectedHorseId(context)
            val stats = computeStats(context, horses)
            val rides = listRidesInternal(context, selected)
            _state.value =
                _state.value.copy(
                    horses = horses,
                    selectedHorseId = selected,
                    horseStats = stats,
                    rides = rides,
                )
        }
    }

    fun selectHorse(context: Context, horseId: String) {
        SelectionStorage.setSelectedHorseId(context, horseId)
        _state.value = _state.value.copy(selectedHorseId = horseId)
        refreshRides(context)
        refreshStats(context)
    }

    fun addHorse(context: Context, name: String) {
        ioScope.launch {
            val horse = HorseStorage.addHorse(context, name)
            val horses = HorseStorage.listHorses(context)
            val stats = computeStats(context, horses)
            _state.value = _state.value.copy(horses = horses, selectedHorseId = horse.id, horseStats = stats)
            SelectionStorage.setSelectedHorseId(context, horse.id)
            refreshRides(context)
        }
    }

    fun deleteHorse(context: Context, horseId: String) {
        ioScope.launch {
            try {
                val deleted = HorseStorage.deleteHorse(context, horseId)
                if (!deleted) {
                    _events.tryEmit(UiEvent.Message("Kůň nebyl nalezen."))
                    return@launch
                }

                val ridesDir = File(context.filesDir, "rides")
                RideMetaStorage.listMetas(context)
                    .filter { it.horseId == horseId }
                    .forEach { meta ->
                        File(ridesDir, meta.gpxFileName).delete()
                        File(ridesDir, meta.metaFileName).delete()
                    }

                val horses = HorseStorage.listHorses(context)
                val nextSelected = horses.firstOrNull()?.id
                SelectionStorage.setSelectedHorseId(context, nextSelected)

                val stats = computeStats(context, horses)
                val rides = listRidesInternal(context, nextSelected)
                _state.value =
                    _state.value.copy(
                        horses = horses,
                        selectedHorseId = nextSelected,
                        horseStats = stats,
                        rides = rides,
                    )

                _events.tryEmit(UiEvent.Message("Kůň smazán včetně jeho jízd."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Mazání koně selhalo: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun setRecording(isRecording: Boolean) {
        _state.value = _state.value.copy(isRecording = isRecording)
    }

    fun setFollowing(isFollowing: Boolean) {
        _state.value = _state.value.copy(isFollowing = isFollowing)
    }

    fun toggleAutoCenter() {
        val prev = _state.value
        _state.value = prev.copy(isAutoCenter = !prev.isAutoCenter)
    }

    fun onLocation(point: TrackPoint) {
        val prev = _state.value
        val newPoints = prev.points + point
        val newSegments = buildSegments(newPoints)

        val incrementalDistance =
            if (prev.points.isNotEmpty()) {
                val a = prev.points.last()
                Geo.haversineMeters(a.lat, a.lon, point.lat, point.lon)
            } else {
                0.0
            }
        val distance = prev.currentDistanceM + incrementalDistance
        val startTime = prev.points.firstOrNull()?.timeEpochMs ?: point.timeEpochMs
        val durationMs = (point.timeEpochMs - startTime).coerceAtLeast(0L)
        val avgSpeed = if (durationMs > 0) distance / (durationMs.toDouble() / 1000.0) else 0.0

        val route = effectiveFollowRoute(prev)
        val snapPoint =
            if (prev.isFollowing && prev.routeToFollow.size >= 2) {
                Geo.nearestPointOnPolyline(point.lat, point.lon, route)
            } else {
                null
            }
        val offRoute =
            if (prev.isFollowing && prev.routeToFollow.size >= 2) {
                Geo.distanceToPolylineMeters(point.lat, point.lon, route)
            } else {
                0.0
            }

        _state.value = prev.copy(
            points = newPoints,
            lastSpeedMps = point.speedMps,
            lastAccuracyM = point.accuracyM,
            lastHeadingDeg = point.headingDeg ?: prev.lastHeadingDeg,
            currentDistanceM = distance,
            currentDurationMs = durationMs,
            currentAvgSpeedMps = avgSpeed,
            offRouteMeters = offRoute,
            mapState = prev.mapState.copy(
                userLat = point.lat,
                userLon = point.lon,
                userHeadingDeg = point.headingDeg ?: prev.mapState.userHeadingDeg,
                snapLat = snapPoint?.first,
                snapLon = snapPoint?.second,
                segments = newSegments,
                waypoints = prev.waypoints,
                followRoute = route,
            ),
        )
    }

    fun addWaypoint(waypoint: Waypoint) {
        val prev = _state.value
        val newWaypoints = prev.waypoints + waypoint
        _state.value = prev.copy(
            waypoints = newWaypoints,
            mapState = prev.mapState.copy(waypoints = newWaypoints),
        )
    }

    fun setRouteToFollow(points: List<Pair<Double, Double>>) {
        val prev = _state.value
        val next = prev.copy(routeToFollow = points)
        _state.value = next.copy(mapState = next.mapState.copy(followRoute = effectiveFollowRoute(next)))
    }

    fun toggleFollow() {
        val prev = _state.value
        val nextFollowing = !prev.isFollowing
        _state.value =
            prev.copy(
                isFollowing = nextFollowing,
                offRouteMeters = if (nextFollowing) prev.offRouteMeters else 0.0,
                mapState =
                    if (nextFollowing) {
                        prev.mapState
                    } else {
                        prev.mapState.copy(snapLat = null, snapLon = null)
                    },
            )
    }

    fun toggleReverse() {
        val prev = _state.value
        val next = prev.copy(isReversed = !prev.isReversed)
        _state.value = next.copy(mapState = next.mapState.copy(followRoute = effectiveFollowRoute(next)))
    }

    fun resetRide() {
        val prev = _state.value
        _state.value = prev.copy(
            isRecording = false,
            points = emptyList(),
            waypoints = emptyList(),
            lastSpeedMps = 0.0,
            lastAccuracyM = 0.0,
            lastHeadingDeg = null,
            currentDistanceM = 0.0,
            currentDurationMs = 0L,
            currentAvgSpeedMps = 0.0,
            offRouteMeters = 0.0,
            mapState = MapState(followRoute = effectiveFollowRoute(prev), snapLat = null, snapLon = null),
        )
    }

    fun saveCurrentRide(context: Context) {
        val current = _state.value
        val horseId = current.selectedHorseId ?: return
        if (current.points.isEmpty()) return

        ioScope.launch {
            try {
                val ridesDir = File(context.filesDir, "rides").apply { mkdirs() }
                val ts = System.currentTimeMillis()
                val horseName = current.horses.firstOrNull { it.id == horseId }?.name ?: "UnknownHorse"
                val baseName = buildRideBaseName(current.points, horseName, ts)
                val uniqueBase = ensureUniqueBaseName(ridesDir, baseName, ts)
                val gpxName = "$uniqueBase.gpx"
                val metaName = "$uniqueBase.meta.json"

                val gpxFile = File(ridesDir, gpxName)
                GpxStorage.writeGpx(gpxFile, current.points, current.waypoints)

                val meta = buildRideMeta(horseId, gpxName, metaName, current.points)
                RideMetaStorage.writeMeta(context, meta, metaName)
                refreshStats(context)
                refreshRides(context)

                _events.tryEmit(UiEvent.RideSaved(gpxFile.absolutePath))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Uložení selhalo: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun loadMostRecentRide(context: Context) {
        val current = _state.value
        val horseId = current.selectedHorseId
        ioScope.launch {
            val meta =
                RideMetaStorage.listMetas(context)
                    .filter { horseId == null || it.horseId == horseId }
                    .maxByOrNull { it.endTimeMs }
                    ?: return@launch
            loadRide(context, meta.metaFileName)
        }
    }

    fun refreshRides(context: Context, horseId: String? = _state.value.selectedHorseId) {
        ioScope.launch {
            val rides = listRidesInternal(context, horseId)
            _state.value = _state.value.copy(rides = rides)
        }
    }

    fun loadRide(context: Context, metaFileName: String) {
        ioScope.launch {
            val meta = RideMetaStorage.listMetas(context).firstOrNull { it.metaFileName == metaFileName } ?: return@launch
            val ridesDir = File(context.filesDir, "rides")
            val gpxFile = File(ridesDir, meta.gpxFileName)
            if (!gpxFile.exists()) return@launch

            val ride = GpxStorage.readGpx(gpxFile)
            setRouteToFollow(ride.points.map { it.lat to it.lon })
            val prev = _state.value
            _state.value = prev.copy(
                isAutoCenter = false,
                waypoints = ride.waypoints,
                mapState = prev.mapState.copy(waypoints = ride.waypoints),
            )
        }
    }

    fun deleteRide(context: Context, metaFileName: String) {
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(context).firstOrNull { it.metaFileName == metaFileName } ?: return@launch
                val ridesDir = File(context.filesDir, "rides")
                File(ridesDir, meta.gpxFileName).delete()
                File(ridesDir, meta.metaFileName).delete()
                refreshStats(context)
                refreshRides(context)
                _events.tryEmit(UiEvent.Message("Jízda smazána."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Mazání selhalo: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    private fun buildSegments(points: List<TrackPoint>): List<SpeedSegment> {
        if (points.size < 2) return emptyList()
        val segments = ArrayList<SpeedSegment>(points.size - 1)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            segments.add(
                SpeedSegment(
                    startLat = a.lat,
                    startLon = a.lon,
                    endLat = b.lat,
                    endLon = b.lon,
                    speedMps = b.speedMps,
                ),
            )
        }
        return segments
    }

    private fun effectiveFollowRoute(state: AppState): List<Pair<Double, Double>> {
        val base = state.routeToFollow
        return if (state.isReversed) base.asReversed() else base
    }

    private fun refreshStats(context: Context) {
        ioScope.launch {
            val horses = HorseStorage.listHorses(context)
            val stats = computeStats(context, horses)
            _state.value = _state.value.copy(horses = horses, horseStats = stats)
        }
    }

    private fun computeStats(context: Context, horses: List<Horse>): Map<String, RideStats> {
        val metas = RideMetaStorage.listMetas(context)
        val byHorse = metas.groupBy { it.horseId }
        val out = HashMap<String, RideStats>()
        horses.forEach { h ->
            val ms = byHorse[h.id].orEmpty()
            val ridesCount = ms.size
            var totalDuration = 0L
            var totalDistance = 0.0
            var totalAvgSpeedWeighted = 0.0
            var totalTimeForAvg = 0.0
            var maxSpeed = 0.0
            ms.forEach { m ->
                val dur = (m.endTimeMs - m.startTimeMs).coerceAtLeast(0)
                totalDuration += dur
                totalDistance += m.distanceM
                if (dur > 0) {
                    totalAvgSpeedWeighted += m.avgSpeedMps * dur.toDouble()
                    totalTimeForAvg += dur.toDouble()
                }
                if (m.maxSpeedMps > maxSpeed) maxSpeed = m.maxSpeedMps
            }
            val avgSpeed = if (totalTimeForAvg > 0.0) totalAvgSpeedWeighted / totalTimeForAvg else 0.0
            out[h.id] =
                RideStats(
                    ridesCount = ridesCount,
                    totalDurationMs = totalDuration,
                    totalDistanceM = totalDistance,
                    avgSpeedMps = avgSpeed,
                    maxSpeedMps = maxSpeed,
                )
        }
        return out
    }

    private fun buildRideMeta(
        horseId: String,
        gpxName: String,
        metaName: String,
        points: List<TrackPoint>,
    ): RideMetaStorage.RideMeta {
        val start = points.firstOrNull()?.timeEpochMs ?: 0L
        val end = points.lastOrNull()?.timeEpochMs ?: start
        var distance = 0.0
        var maxSpeed = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            distance += Geo.haversineMeters(a.lat, a.lon, b.lat, b.lon)
            if (b.speedMps > maxSpeed) maxSpeed = b.speedMps
        }
        val durationMs = (end - start).coerceAtLeast(1L)
        val avgSpeed = distance / (durationMs.toDouble() / 1000.0)
        return RideMetaStorage.RideMeta(
            horseId = horseId,
            startTimeMs = start,
            endTimeMs = end,
            distanceM = distance,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = maxSpeed,
            pointsCount = points.size,
            gpxFileName = gpxName,
            metaFileName = metaName,
        )
    }

    private fun listRidesInternal(context: Context, selectedHorseId: String?): List<RideSummary> {
        val metas =
            RideMetaStorage.listMetas(context)
                .filter { selectedHorseId == null || it.horseId == selectedHorseId }
                .sortedByDescending { it.endTimeMs }
        return metas.map {
            RideSummary(
                horseId = it.horseId,
                startTimeMs = it.startTimeMs,
                endTimeMs = it.endTimeMs,
                distanceM = it.distanceM,
                avgSpeedMps = it.avgSpeedMps,
                maxSpeedMps = it.maxSpeedMps,
                gpxFileName = it.gpxFileName,
                metaFileName = it.metaFileName,
            )
        }
    }

    private fun buildRideBaseName(points: List<TrackPoint>, horseName: String, fallbackTs: Long): String {
        val first = points.firstOrNull()
        val ts = first?.timeEpochMs?.takeIf { it > 0 } ?: fallbackTs
        val date = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date(ts))
        val horse = sanitizeName(horseName)
        return "$date $horse"
    }

    private fun ensureUniqueBaseName(ridesDir: File, baseName: String, ts: Long): String {
        val gpx = File(ridesDir, "$baseName.gpx")
        val meta = File(ridesDir, "$baseName.meta.json")
        if (!gpx.exists() && !meta.exists()) return baseName

        val suffix = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date(ts))
        val withTime = "$baseName $suffix"
        val gpx2 = File(ridesDir, "$withTime.gpx")
        val meta2 = File(ridesDir, "$withTime.meta.json")
        if (!gpx2.exists() && !meta2.exists()) return withTime

        return "$withTime ${ts % 1000}"
    }

    private fun sanitizeName(name: String?): String {
        if (name.isNullOrBlank()) return "UnknownHorse"
        val ascii =
            Normalizer.normalize(name, Normalizer.Form.NFD)
                .replace("\\p{M}+".toRegex(), "")
        val cleaned = ascii.replace("[^A-Za-z0-9 ]+".toRegex(), " ").trim().replace("\\s+".toRegex(), " ")
        return if (cleaned.isBlank()) "UnknownHorse" else cleaned
    }
}
