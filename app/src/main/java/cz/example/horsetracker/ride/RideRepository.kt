package cz.example.horsetracker.ride

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cz.example.horsetracker.geo.Geo
import cz.example.horsetracker.map.OfflineTilePrefetcher
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
    private var appContext: Context? = null

    sealed interface UiEvent {
        data class Message(val text: String) : UiEvent
        data class RideSaved(val filePath: String) : UiEvent
    }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        ioScope.launch {
            val horses = HorseStorage.listHorses(context)
            val selected = SelectionStorage.getSelectedHorseId(context)
            val stats = computeStats(context, horses)
            val rides = listRidesInternal(context, selected)
            val (warnM, backM) = FollowSettingsStorage.load(context)
            val baseState =
                _state.value.copy(
                    horses = horses,
                    selectedHorseId = selected,
                    horseStats = stats,
                    rides = rides,
                    offRouteWarnThresholdM = warnM,
                    backOnRouteThresholdM = backM,
                )
            val restored = restoreDraftIfAvailable(context, baseState)
            _state.value = restored
            if (restored.points.isNotEmpty()) {
                _events.tryEmit(UiEvent.Message("Byla obnovena nedokončená jízda po pádu nebo zavření aplikace."))
            }
        }
    }

    fun selectHorse(context: Context, horseId: String) {
        SelectionStorage.setSelectedHorseId(context, horseId)
        clearDisplayedRide()
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
        val prev = _state.value
        _state.value =
            prev.copy(
                isFollowing = isFollowing,
                offRouteMeters = if (isFollowing) prev.offRouteMeters else 0.0,
                mapState =
                    if (isFollowing) {
                        prev.mapState
                    } else {
                        prev.mapState.copy(snapLat = null, snapLon = null)
                    },
            )
    }

    fun toggleAutoCenter() {
        val prev = _state.value
        _state.value = prev.copy(isAutoCenter = !prev.isAutoCenter)
    }

    fun updateOffRouteWarnThreshold(deltaM: Double) {
        val prev = _state.value
        val next = (prev.offRouteWarnThresholdM + deltaM).coerceIn(10.0, 200.0)
        // vždy držíme back-on-route menší než warn threshold
        val back = prev.backOnRouteThresholdM.coerceAtMost(next - 1.0).coerceAtLeast(1.0)
        _state.value = prev.copy(offRouteWarnThresholdM = next, backOnRouteThresholdM = back)
        appContext?.let { FollowSettingsStorage.save(it, next, back) }
    }

    fun updateBackOnRouteThreshold(deltaM: Double) {
        val prev = _state.value
        val maxBack = (prev.offRouteWarnThresholdM - 1.0).coerceAtLeast(1.0)
        val next = (prev.backOnRouteThresholdM + deltaM).coerceIn(1.0, maxBack)
        _state.value = prev.copy(backOnRouteThresholdM = next)
        appContext?.let { FollowSettingsStorage.save(it, prev.offRouteWarnThresholdM, next) }
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
        persistDraftAsync(_state.value)
    }

    fun addWaypoint(waypoint: Waypoint) {
        val prev = _state.value
        val newWaypoints = prev.rideWaypoints + waypoint
        val next = prev.copy(rideWaypoints = newWaypoints)
        _state.value = prev.copy(
            rideWaypoints = newWaypoints,
            mapState = prev.mapState.copy(waypoints = next.waypoints),
        )
        persistDraftAsync(_state.value)
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
        val next =
            prev.copy(
                isRecording = false,
                points = emptyList(),
                rideWaypoints = emptyList(),
                lastSpeedMps = 0.0,
                lastAccuracyM = 0.0,
                lastHeadingDeg = null,
                currentDistanceM = 0.0,
                currentDurationMs = 0L,
                currentAvgSpeedMps = 0.0,
                offRouteMeters = 0.0,
                mapState =
                    prev.mapState.copy(
                        userHeadingDeg = null,
                        snapLat = null,
                        snapLon = null,
                        segments = emptyList(),
                    ),
            )
        _state.value = next.copy(mapState = next.mapState.copy(waypoints = next.waypoints))
        clearDraftAsync()
    }

    fun prepareForNewActiveRide(clearFollowRoute: Boolean = false) {
        val prev = _state.value
        val next =
            prev.copy(
                isFollowing = if (clearFollowRoute) false else prev.isFollowing,
                isReversed = if (clearFollowRoute) false else prev.isReversed,
                points = emptyList(),
                rideWaypoints = emptyList(),
                routeWaypoints = if (clearFollowRoute) emptyList() else prev.routeWaypoints,
                routeToFollow = if (clearFollowRoute) emptyList() else prev.routeToFollow,
                lastSpeedMps = 0.0,
                lastAccuracyM = 0.0,
                lastHeadingDeg = null,
                currentDistanceM = 0.0,
                currentDurationMs = 0L,
                currentAvgSpeedMps = 0.0,
                offRouteMeters = 0.0,
                mapState =
                    prev.mapState.copy(
                        userHeadingDeg = null,
                        snapLat = null,
                        snapLon = null,
                        segments = emptyList(),
                        followRoute = if (clearFollowRoute) emptyList() else effectiveFollowRoute(prev),
                    ),
            )
        _state.value = next.copy(mapState = next.mapState.copy(waypoints = next.waypoints))
        clearDraftAsync()
    }

    fun saveCurrentRide(context: Context) {
        val current = _state.value
        val horseId = current.selectedHorseId ?: return
        if (current.points.isEmpty()) return

        ioScope.launch {
            saveRideSnapshot(context, current, horseId)
        }
    }

    fun saveCurrentRideForHorseName(context: Context, horseName: String) {
        val trimmed = horseName.trim()
        if (trimmed.isEmpty()) {
            _events.tryEmit(UiEvent.Message("Zadej jméno koně."))
            return
        }
        val current = _state.value
        if (current.points.isEmpty()) {
            _events.tryEmit(UiEvent.Message("Není co ukládat, trasa je prázdná."))
            return
        }

        ioScope.launch {
            try {
                val horse = HorseStorage.addHorse(context, trimmed)
                SelectionStorage.setSelectedHorseId(context, horse.id)
                val horses = HorseStorage.listHorses(context)
                _state.value = _state.value.copy(horses = horses, selectedHorseId = horse.id)
                saveRideSnapshot(context, current, horse.id)
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Uložení selhalo: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun prefetchOfflineAroundCurrent(context: Context, radiusKm: Double = 4.0) {
        val snapshot = _state.value
        val lat = snapshot.mapState.userLat
        val lon = snapshot.mapState.userLon
        if (lat == null || lon == null) {
            _events.tryEmit(UiEvent.Message("Nejdřív je potřeba mít aktuální GPS pozici."))
            return
        }

        ioScope.launch {
            try {
                _events.tryEmit(UiEvent.Message("Stahuju offline mapu okolí (${radiusKm.toInt()} km)..."))
                val result = OfflineTilePrefetcher.prefetchAround(context, lat, lon, radiusKm = radiusKm)
                _events.tryEmit(
                    UiEvent.Message(
                        "Offline mapy hotovo: staženo ${result.downloaded}, v cache ${result.skipped}, chyby ${result.failed}.",
                    ),
                )
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Offline stahování selhalo: ${t.message ?: t::class.java.simpleName}"))
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
            val prev = _state.value
            val next =
                prev.copy(
                    isAutoCenter = false,
                    points = emptyList(),
                    rideWaypoints = emptyList(),
                    routeWaypoints = ride.waypoints,
                    routeToFollow = ride.points.map { it.lat to it.lon },
                    lastSpeedMps = 0.0,
                    lastAccuracyM = 0.0,
                    lastHeadingDeg = null,
                    currentDistanceM = 0.0,
                    currentDurationMs = 0L,
                    currentAvgSpeedMps = 0.0,
                    offRouteMeters = 0.0,
                    mapState =
                        prev.mapState.copy(
                            userHeadingDeg = null,
                            snapLat = null,
                            snapLon = null,
                            segments = emptyList(),
                        ),
                )
            _state.value =
                next.copy(
                    mapState =
                        next.mapState.copy(
                            waypoints = next.waypoints,
                            followRoute = effectiveFollowRoute(next),
                        ),
                )
        }
    }

    fun deleteRide(context: Context, metaFileName: String, horseIdFilter: String? = _state.value.selectedHorseId) {
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(context).firstOrNull { it.metaFileName == metaFileName } ?: return@launch
                val ridesDir = File(context.filesDir, "rides")
                File(ridesDir, meta.gpxFileName).delete()
                File(ridesDir, meta.metaFileName).delete()
                refreshStats(context)
                refreshRides(context, horseIdFilter)
                _events.tryEmit(UiEvent.Message("Jízda smazána."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Mazání selhalo: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun exportRideToUri(context: Context, metaFileName: String, destinationUri: Uri) {
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(context).firstOrNull { it.metaFileName == metaFileName }
                if (meta == null) {
                    _events.tryEmit(UiEvent.Message("Export selhal: jízda nebyla nalezena."))
                    return@launch
                }

                val sourceFile = File(File(context.filesDir, "rides"), meta.gpxFileName)
                if (!sourceFile.exists()) {
                    _events.tryEmit(UiEvent.Message("Export selhal: GPX soubor neexistuje."))
                    return@launch
                }

                val output =
                    context.contentResolver.openOutputStream(destinationUri)
                        ?: run {
                            _events.tryEmit(UiEvent.Message("Export selhal: nelze otevřít cílový soubor."))
                            return@launch
                        }

                sourceFile.inputStream().use { input ->
                    output.use { out -> input.copyTo(out) }
                }

                _events.tryEmit(UiEvent.Message("Export hotov: ${meta.gpxFileName}"))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Export selhal: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun emailRideGpx(context: Context, metaFileName: String, emailAddress: String) {
        try {
            val meta = RideMetaStorage.listMetas(context).firstOrNull { it.metaFileName == metaFileName }
            if (meta == null) {
                _events.tryEmit(UiEvent.Message("Odeslání selhalo: jízda nebyla nalezena."))
                return
            }

            val sourceFile = File(File(context.filesDir, "rides"), meta.gpxFileName)
            if (!sourceFile.exists()) {
                _events.tryEmit(UiEvent.Message("Odeslání selhalo: GPX soubor neexistuje."))
                return
            }

            val gpxUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceFile)
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
                    putExtra(Intent.EXTRA_SUBJECT, sourceFile.nameWithoutExtension)
                    putExtra(Intent.EXTRA_TEXT, "GPX trasa z Horse Trackeru.")
                    putExtra(Intent.EXTRA_STREAM, gpxUri)
                    clipData = ClipData.newUri(context.contentResolver, sourceFile.name, gpxUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    selector =
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                        }
                }

            val handlers = context.packageManager.queryIntentActivities(sendIntent, 0)
            if (handlers.isEmpty()) {
                _events.tryEmit(UiEvent.Message("V zařízení není dostupná e-mailová aplikace."))
                return
            }
            handlers.forEach { resolveInfo ->
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    gpxUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

            val chooser = Intent.createChooser(sendIntent, "Odeslat GPX mailem")

            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            _events.tryEmit(UiEvent.Message("V zařízení není dostupná e-mailová aplikace."))
        } catch (t: Throwable) {
            _events.tryEmit(UiEvent.Message("Odeslání GPX selhalo: ${t.message ?: t::class.java.simpleName}"))
        }
    }

    fun exportBackupToUri(context: Context, destinationUri: Uri) {
        if (_state.value.isRecording || _state.value.isFollowing) {
            _events.tryEmit(UiEvent.Message("Backup nelze exportovat během aktivního záznamu nebo follow režimu."))
            return
        }

        ioScope.launch {
            try {
                AppBackupStorage.export(context, destinationUri)
                _events.tryEmit(UiEvent.Message("Backup hotov."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Export backupu selhal: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun importBackupFromUri(context: Context, sourceUri: Uri) {
        if (_state.value.isRecording || _state.value.isFollowing) {
            _events.tryEmit(UiEvent.Message("Backup nelze importovat během aktivního záznamu nebo follow režimu."))
            return
        }

        ioScope.launch {
            try {
                AppBackupStorage.import(context, sourceUri)
                reloadFromStorage(context, AppState())
                _events.tryEmit(UiEvent.Message("Backup obnoven."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Import backupu selhal: ${t.message ?: t::class.java.simpleName}"))
            }
        }
    }

    fun importRideFromUri(context: Context, horseId: String, sourceUri: Uri) {
        ioScope.launch {
            val horse = HorseStorage.listHorses(context).firstOrNull { it.id == horseId }
            if (horse == null) {
                _events.tryEmit(UiEvent.Message("Import selhal: kůň nebyl nalezen."))
                return@launch
            }

            val tempFile = File.createTempFile("horse_tracker_import_", ".gpx", context.cacheDir)
            try {
                val input =
                    context.contentResolver.openInputStream(sourceUri)
                        ?: run {
                            _events.tryEmit(UiEvent.Message("Import selhal: nelze otevřít GPX soubor."))
                            return@launch
                        }
                input.use { source ->
                    tempFile.outputStream().use { target -> source.copyTo(target) }
                }

                val ride = GpxStorage.readGpx(tempFile)
                if (ride.points.isEmpty()) {
                    _events.tryEmit(UiEvent.Message("Import selhal: GPX neobsahuje trasové body."))
                    return@launch
                }

                val ridesDir = File(context.filesDir, "rides").apply { mkdirs() }
                val ts = ride.points.firstOrNull()?.timeEpochMs ?: System.currentTimeMillis()
                val baseName = buildRideBaseName(ride.points, horse.name, ts)
                val uniqueBase = ensureUniqueBaseName(ridesDir, baseName, ts)
                val gpxName = "$uniqueBase.gpx"
                val metaName = "$uniqueBase.meta.json"

                val gpxFile = File(ridesDir, gpxName)
                GpxStorage.writeGpx(gpxFile, ride.points, ride.waypoints)

                val meta = buildRideMeta(horse.id, gpxName, metaName, ride.points)
                RideMetaStorage.writeMeta(context, meta, metaName)
                refreshStats(context)
                refreshRides(context)

                _events.tryEmit(UiEvent.Message("GPX importován ke koni ${horse.name}."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Import GPX selhal: ${t.message ?: t::class.java.simpleName}"))
            } finally {
                tempFile.delete()
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

    private fun reloadFromStorage(context: Context, baseState: AppState = _state.value) {
        appContext = context.applicationContext
        ioScope.launch {
            val horses = HorseStorage.listHorses(context)
            val selected = SelectionStorage.getSelectedHorseId(context)
            val stats = computeStats(context, horses)
            val rides = listRidesInternal(context, selected)
            val (warnM, backM) = FollowSettingsStorage.load(context)
            _state.value =
                baseState.copy(
                    horses = horses,
                    selectedHorseId = selected,
                    horseStats = stats,
                    rides = rides,
                    offRouteWarnThresholdM = warnM,
                    backOnRouteThresholdM = backM,
                )
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

    private fun saveRideSnapshot(context: Context, snapshot: AppState, horseId: String) {
        try {
            val ridesDir = File(context.filesDir, "rides").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val horseName = snapshot.horses.firstOrNull { it.id == horseId }?.name ?: "UnknownHorse"
            val baseName = buildRideBaseName(snapshot.points, horseName, ts)
            val uniqueBase = ensureUniqueBaseName(ridesDir, baseName, ts)
            val gpxName = "$uniqueBase.gpx"
            val metaName = "$uniqueBase.meta.json"

            val gpxFile = File(ridesDir, gpxName)
            GpxStorage.writeGpx(gpxFile, snapshot.points, snapshot.rideWaypoints)

            val meta = buildRideMeta(horseId, gpxName, metaName, snapshot.points)
            RideMetaStorage.writeMeta(context, meta, metaName)
            DraftRideStorage.clear(context)
            refreshStats(context)
            refreshRides(context)

            _events.tryEmit(UiEvent.RideSaved(gpxFile.absolutePath))
        } catch (t: Throwable) {
            _events.tryEmit(UiEvent.Message("Uložení selhalo: ${t.message ?: t::class.java.simpleName}"))
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

    private fun restoreDraftIfAvailable(context: Context, baseState: AppState): AppState {
        val draft = DraftRideStorage.readDraft(context) ?: return baseState
        if (draft.points.isEmpty()) {
            DraftRideStorage.clear(context)
            return baseState
        }

        val selectedHorseId =
            draft.selectedHorseId?.takeIf { horseId -> baseState.horses.any { it.id == horseId } }
                ?: baseState.selectedHorseId

        val points = draft.points
        val last = points.last()
        val distance = calculateDistance(points)
        val durationMs = (last.timeEpochMs - points.first().timeEpochMs).coerceAtLeast(0L)
        val avgSpeed = if (durationMs > 0L) distance / (durationMs.toDouble() / 1000.0) else 0.0
        val next =
            baseState.copy(
                selectedHorseId = selectedHorseId,
                points = points,
                rideWaypoints = draft.waypoints,
                lastSpeedMps = last.speedMps,
                lastAccuracyM = last.accuracyM,
                lastHeadingDeg = last.headingDeg,
                currentDistanceM = distance,
                currentDurationMs = durationMs,
                currentAvgSpeedMps = avgSpeed,
                mapState =
                    baseState.mapState.copy(
                        userLat = last.lat,
                        userLon = last.lon,
                        userHeadingDeg = last.headingDeg,
                        snapLat = null,
                        snapLon = null,
                        segments = buildSegments(points),
                    ),
            )
        return next.copy(mapState = next.mapState.copy(waypoints = next.waypoints))
    }

    private fun calculateDistance(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            total += Geo.haversineMeters(previous.lat, previous.lon, current.lat, current.lon)
        }
        return total
    }

    private fun persistDraftAsync(snapshot: AppState) {
        val context = appContext ?: return
        ioScope.launch {
            if (snapshot.points.isEmpty() && snapshot.rideWaypoints.isEmpty()) {
                DraftRideStorage.clear(context)
            } else {
                DraftRideStorage.writeDraft(context, snapshot.selectedHorseId, snapshot.points, snapshot.rideWaypoints)
            }
        }
    }

    private fun clearDraftAsync() {
        val context = appContext ?: return
        ioScope.launch { DraftRideStorage.clear(context) }
    }

    private fun clearDisplayedRide() {
        val prev = _state.value
        val next =
            prev.copy(
                isRecording = false,
                isFollowing = false,
                isReversed = false,
                points = emptyList(),
                rideWaypoints = emptyList(),
                routeWaypoints = emptyList(),
                routeToFollow = emptyList(),
                lastSpeedMps = 0.0,
                lastAccuracyM = 0.0,
                lastHeadingDeg = null,
                currentDistanceM = 0.0,
                currentDurationMs = 0L,
                currentAvgSpeedMps = 0.0,
                offRouteMeters = 0.0,
                mapState =
                    prev.mapState.copy(
                        userHeadingDeg = null,
                        snapLat = null,
                        snapLon = null,
                        segments = emptyList(),
                        waypoints = emptyList(),
                        followRoute = emptyList(),
                    ),
            )
        _state.value = next
        clearDraftAsync()
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
