package cz.example.horsetracker.ride

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import cz.example.horsetracker.geo.Geo
import cz.example.horsetracker.map.OfflineTilePrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private const val CLOUD_LOG_TAG = "HorseTrackerCloud"
    private val _state = MutableStateFlow(AppState(isLoadingData = true))
    val state = _state.asStateFlow()
    private var appContext: Context? = null

    sealed interface UiEvent {
        data class Message(val text: String) : UiEvent
        data class RideSaved(val filePath: String) : UiEvent
    }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cloudSyncLock = Any()
    private var cloudSyncRunning = false
    private var pendingCloudUpload: CloudUploadRequest? = null
    private var localDeletePendingCloudConfirmation = false
    private val draftIoLock = Any()
    private var draftGeneration = 0L

    private data class CloudUploadRequest(
        val settings: CloudSettingsStorage.CloudSettings,
        val minimumSummary: AppBackupStorage.BackupSummary,
        val allowAfterLocalDelete: Boolean,
    )

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        _state.value = _state.value.copy(isLoadingData = true)
        ioScope.launch {
            try {
                val horses = HorseStorage.listHorses(app)
                val selected = SelectionStorage.getSelectedHorseId(app)
                val stats = computeStats(app, horses)
                val rides = listRidesInternal(app, selected)
                val (warnM, backM) = FollowSettingsStorage.load(app)
                val cloud = CloudSettingsStorage.load(app)
                val baseState =
                    _state.value.copy(
                        isLoadingData = false,
                        horses = horses,
                        selectedHorseId = selected,
                        horseStats = stats,
                        rides = rides,
                        offRouteWarnThresholdM = warnM,
                        backOnRouteThresholdM = backM,
                        cloudEndpointUrl = cloud.endpointUrl,
                        cloudToken = cloud.token,
                        cloudSyncEnabled = cloud.enabled,
                    )
                val restored = restoreDraftIfAvailable(app, baseState)
                _state.value = restored
                if (restored.points.isNotEmpty()) {
                    _events.tryEmit(UiEvent.Message("Byla obnovena nedokončená jízda po pádu nebo zavření aplikace."))
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(isLoadingData = false)
                _events.tryEmit(UiEvent.Message("Načtení dat selhalo: ${errorText(t)}"))
            }
        }
    }

    fun selectHorse(context: Context, horseId: String) {
        val app = context.applicationContext
        SelectionStorage.setSelectedHorseId(app, horseId)
        clearDisplayedRide()
        _state.value = _state.value.copy(selectedHorseId = horseId)
        refreshRides(app)
        refreshStats(app)
        scheduleCloudUpload(app)
    }

    fun addHorse(context: Context, name: String) {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val horse = HorseStorage.addHorse(app, name)
                val horses = HorseStorage.listHorses(app)
                val stats = computeStats(app, horses)
                _state.value = _state.value.copy(horses = horses, selectedHorseId = horse.id, horseStats = stats)
                SelectionStorage.setSelectedHorseId(app, horse.id)
                refreshRides(app)
                scheduleCloudUpload(app)
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Přidání koně selhalo: ${errorText(t)}"))
            }
        }
    }

    fun deleteHorse(context: Context, horseId: String, deleteFromCloud: Boolean = false) {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val deleted = HorseStorage.deleteHorse(app, horseId)
                if (!deleted) {
                    _events.tryEmit(UiEvent.Message("Kůň nebyl nalezen."))
                    return@launch
                }

                val ridesDir = File(app.filesDir, "rides")
                RideMetaStorage.listMetas(app)
                    .filter { it.horseId == horseId }
                    .forEach { meta ->
                        File(ridesDir, meta.gpxFileName).delete()
                        File(ridesDir, meta.metaFileName).delete()
                    }

                val horses = HorseStorage.listHorses(app)
                val nextSelected = horses.firstOrNull()?.id
                SelectionStorage.setSelectedHorseId(app, nextSelected)

                val stats = computeStats(app, horses)
                val rides = listRidesInternal(app, nextSelected)
                _state.value =
                    _state.value.copy(
                        horses = horses,
                        selectedHorseId = nextSelected,
                        horseStats = stats,
                        rides = rides,
                    )

                if (deleteFromCloud) {
                    synchronized(cloudSyncLock) {
                        localDeletePendingCloudConfirmation = false
                    }
                    scheduleCloudUpload(app, allowAfterLocalDelete = true)
                    _events.tryEmit(UiEvent.Message("Kůň smazán včetně jeho jízd. Cloud bude aktualizován."))
                } else {
                    synchronized(cloudSyncLock) {
                        localDeletePendingCloudConfirmation = true
                    }
                    _state.value =
                        _state.value.copy(
                            cloudSyncStatus =
                                if (CloudSettingsStorage.load(app).isConfigured) {
                                    "Cloud: lokální smazání není automaticky nahrané. Tlačítko Uložit přepíše cloud."
                                } else {
                                    _state.value.cloudSyncStatus
                                },
                        )
                    _events.tryEmit(UiEvent.Message("Kůň smazán včetně jeho jízd. Cloud záloha zůstala beze změny."))
                }
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Mazání koně selhalo: ${errorText(t)}"))
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
                        prev.mapState.copy(followActive = true)
                    } else {
                        prev.mapState.copy(snapLat = null, snapLon = null, followActive = false)
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
        appContext?.let { scheduleCloudUpload(it) }
    }

    fun updateBackOnRouteThreshold(deltaM: Double) {
        val prev = _state.value
        val maxBack = (prev.offRouteWarnThresholdM - 1.0).coerceAtLeast(1.0)
        val next = (prev.backOnRouteThresholdM + deltaM).coerceIn(1.0, maxBack)
        _state.value = prev.copy(backOnRouteThresholdM = next)
        appContext?.let { FollowSettingsStorage.save(it, prev.offRouteWarnThresholdM, next) }
        appContext?.let { scheduleCloudUpload(it) }
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
        _state.value =
            next.copy(
                mapState =
                    next.mapState.copy(
                        followRoute = effectiveFollowRoute(next),
                        followSegments = effectiveFollowSegments(next),
                        followActive = next.isFollowing,
                    ),
            )
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
                        prev.mapState.copy(followActive = true)
                    } else {
                        prev.mapState.copy(snapLat = null, snapLon = null, followActive = false)
                    },
            )
    }

    fun setReverseMode(isReversed: Boolean) {
        val prev = _state.value
        val next = prev.copy(isReversed = isReversed)
        _state.value =
            next.copy(
                mapState =
                    next.mapState.copy(
                        followRoute = effectiveFollowRoute(next),
                        followSegments = effectiveFollowSegments(next),
                        followActive = next.isFollowing,
                    ),
            )
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
                routeFollowSegments = if (clearFollowRoute) emptyList() else prev.routeFollowSegments,
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
                        followSegments = if (clearFollowRoute) emptyList() else effectiveFollowSegments(prev),
                        followActive = if (clearFollowRoute) false else prev.isFollowing,
                    ),
            )
        _state.value = next.copy(mapState = next.mapState.copy(waypoints = next.waypoints))
        clearDraftAsync()
    }

    fun saveCurrentRide(context: Context) {
        val app = context.applicationContext
        val current = _state.value
        val horseId = current.selectedHorseId ?: return
        if (current.points.isEmpty()) return

        ioScope.launch {
            saveRideSnapshot(app, current, horseId)
        }
    }

    fun previewCurrentRideName(): String? {
        val snapshot = _state.value
        val horseId = snapshot.selectedHorseId ?: return null
        if (snapshot.points.isEmpty()) return null
        val horseName = snapshot.horses.firstOrNull { it.id == horseId }?.name ?: return null
        return buildRideBaseName(snapshot.points, horseName, System.currentTimeMillis())
    }

    fun saveCurrentRideForHorseName(context: Context, horseName: String) {
        val app = context.applicationContext
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
                val horse = HorseStorage.addHorse(app, trimmed)
                SelectionStorage.setSelectedHorseId(app, horse.id)
                val horses = HorseStorage.listHorses(app)
                _state.value = _state.value.copy(horses = horses, selectedHorseId = horse.id)
                saveRideSnapshot(app, current, horse.id)
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Uložení selhalo: ${errorText(t)}"))
            }
        }
    }

    fun prefetchOfflineAroundCurrent(context: Context, radiusKm: Double = 4.0) {
        val app = context.applicationContext
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
                val result = OfflineTilePrefetcher.prefetchAround(app, lat, lon, radiusKm = radiusKm)
                _events.tryEmit(
                    UiEvent.Message(
                        "Offline mapy hotovo: staženo ${result.downloaded}, v cache ${result.skipped}, chyby ${result.failed}.",
                    ),
                )
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Offline stahování selhalo: ${errorText(t)}"))
            }
        }
    }

    fun loadMostRecentRide(context: Context) {
        val app = context.applicationContext
        val current = _state.value
        val horseId = current.selectedHorseId
        ioScope.launch {
            val meta =
                RideMetaStorage.listMetas(app)
                    .filter { horseId == null || it.horseId == horseId }
                    .maxByOrNull { it.endTimeMs }
                    ?: return@launch
            loadRide(app, meta.metaFileName)
        }
    }

    fun refreshRides(context: Context, horseId: String? = _state.value.selectedHorseId) {
        val app = context.applicationContext
        ioScope.launch {
            val rides = listRidesInternal(app, horseId)
            _state.value = _state.value.copy(rides = rides)
        }
    }

    fun loadRide(context: Context, metaFileName: String) {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(app).firstOrNull { it.metaFileName == metaFileName } ?: return@launch
                val ridesDir = File(app.filesDir, "rides")
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
                        routeFollowSegments = buildSegments(ride.points),
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
                                followSegments = effectiveFollowSegments(next),
                                followActive = next.isFollowing,
                            ),
                    )
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Načtení jízdy selhalo: ${errorText(t)}"))
            }
        }
    }

    fun deleteRide(
        context: Context,
        metaFileName: String,
        horseIdFilter: String? = _state.value.selectedHorseId,
        deleteFromCloud: Boolean = false,
    ) {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(app).firstOrNull { it.metaFileName == metaFileName } ?: return@launch
                val ridesDir = File(app.filesDir, "rides")
                File(ridesDir, meta.gpxFileName).delete()
                File(ridesDir, meta.metaFileName).delete()
                refreshStats(app)
                refreshRides(app, horseIdFilter)
                if (deleteFromCloud) {
                    synchronized(cloudSyncLock) {
                        localDeletePendingCloudConfirmation = false
                    }
                    scheduleCloudUpload(app, allowAfterLocalDelete = true)
                    _events.tryEmit(UiEvent.Message("Jízda smazána. Cloud bude aktualizován."))
                } else {
                    synchronized(cloudSyncLock) {
                        localDeletePendingCloudConfirmation = true
                    }
                    _state.value =
                        _state.value.copy(
                            cloudSyncStatus =
                                if (CloudSettingsStorage.load(app).isConfigured) {
                                    "Cloud: lokální smazání není automaticky nahrané. Tlačítko Uložit přepíše cloud."
                                } else {
                                    _state.value.cloudSyncStatus
                                },
                        )
                    _events.tryEmit(UiEvent.Message("Jízda smazána. Cloud záloha zůstala beze změny."))
                }
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Mazání selhalo: ${errorText(t)}"))
            }
        }
    }

    fun exportRideToUri(context: Context, metaFileName: String, destinationUri: Uri) {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(app).firstOrNull { it.metaFileName == metaFileName }
                if (meta == null) {
                    _events.tryEmit(UiEvent.Message("Export selhal: jízda nebyla nalezena."))
                    return@launch
                }

                val sourceFile = File(File(app.filesDir, "rides"), meta.gpxFileName)
                if (!sourceFile.exists()) {
                    _events.tryEmit(UiEvent.Message("Export selhal: GPX soubor neexistuje."))
                    return@launch
                }

                val output =
                    app.contentResolver.openOutputStream(destinationUri)
                        ?: run {
                            _events.tryEmit(UiEvent.Message("Export selhal: nelze otevřít cílový soubor."))
                            return@launch
                        }

                sourceFile.inputStream().use { input ->
                    output.use { out -> input.copyTo(out) }
                }

                _events.tryEmit(UiEvent.Message("Export hotov: ${meta.gpxFileName}"))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Export selhal: ${errorText(t)}"))
            }
        }
    }

    fun emailRideGpx(context: Context, metaFileName: String, emailAddress: String) {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val meta = RideMetaStorage.listMetas(app).firstOrNull { it.metaFileName == metaFileName }
                if (meta == null) {
                    _events.tryEmit(UiEvent.Message("Odeslání selhalo: jízda nebyla nalezena."))
                    return@launch
                }

                val sourceFile = File(File(app.filesDir, "rides"), meta.gpxFileName)
                if (!sourceFile.exists()) {
                    _events.tryEmit(UiEvent.Message("Odeslání selhalo: GPX soubor neexistuje."))
                    return@launch
                }

                val gpxUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", sourceFile)
                val mailHandlers =
                    app.packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
                        0,
                    )
                if (mailHandlers.isEmpty()) {
                    _events.tryEmit(UiEvent.Message("V zařízení není dostupná e-mailová aplikace."))
                    return@launch
                }

                val targetIntents =
                    mailHandlers.map { resolveInfo ->
                        app.grantUriPermission(
                            resolveInfo.activityInfo.packageName,
                            gpxUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        Intent(Intent.ACTION_SEND).apply {
                            `package` = resolveInfo.activityInfo.packageName
                            type = "*/*"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
                            putExtra(Intent.EXTRA_SUBJECT, sourceFile.nameWithoutExtension)
                            putExtra(Intent.EXTRA_TEXT, "GPX trasa z Horse Trackeru.")
                            putExtra(Intent.EXTRA_STREAM, gpxUri)
                            clipData = ClipData.newUri(app.contentResolver, sourceFile.name, gpxUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }

                val chooser =
                    if (targetIntents.size == 1) {
                        targetIntents.first()
                    } else {
                        Intent.createChooser(targetIntents.first(), "Odeslat GPX mailem").apply {
                            putExtra(Intent.EXTRA_INITIAL_INTENTS, targetIntents.drop(1).toTypedArray())
                        }
                    }.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                withContext(Dispatchers.Main) {
                    app.startActivity(chooser)
                }
            } catch (_: ActivityNotFoundException) {
                _events.tryEmit(UiEvent.Message("V zařízení není dostupná e-mailová aplikace."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Odeslání GPX selhalo: ${errorText(t)}"))
            }
        }
    }

    fun exportBackupToUri(context: Context, destinationUri: Uri) {
        val app = context.applicationContext
        if (_state.value.isRecording || _state.value.isFollowing) {
            _events.tryEmit(UiEvent.Message("Backup nelze exportovat během aktivního záznamu nebo follow režimu."))
            return
        }

        ioScope.launch {
            try {
                AppBackupStorage.export(app, destinationUri)
                _events.tryEmit(UiEvent.Message("Backup hotov."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Export backupu selhal: ${errorText(t)}"))
            }
        }
    }

    fun importBackupFromUri(context: Context, sourceUri: Uri) {
        val app = context.applicationContext
        if (_state.value.isRecording || _state.value.isFollowing) {
            _events.tryEmit(UiEvent.Message("Backup nelze importovat během aktivního záznamu nebo follow režimu."))
            return
        }

        ioScope.launch {
            try {
                AppBackupStorage.import(app, sourceUri)
                reloadFromStorage(app, _state.value)
                synchronized(cloudSyncLock) {
                    localDeletePendingCloudConfirmation = false
                }
                scheduleCloudUpload(app, allowAfterLocalDelete = true)
                _events.tryEmit(UiEvent.Message("Backup obnoven."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Import backupu selhal: ${errorText(t)}"))
            }
        }
    }

    fun updateCloudSettings(context: Context, endpointUrl: String, token: String, enabled: Boolean) {
        val app = context.applicationContext
        val settings =
            CloudSettingsStorage.CloudSettings(
                endpointUrl = endpointUrl.trim(),
                token = token.trim(),
                enabled = enabled,
            )
        CloudSettingsStorage.save(app, settings)
        _state.value =
            _state.value.copy(
                cloudEndpointUrl = settings.endpointUrl,
                cloudToken = settings.token,
                cloudSyncEnabled = settings.enabled,
                cloudSyncStatus = if (settings.isConfigured) "Cloud sync zapnutý." else "Cloud sync vypnutý.",
            )
        if (settings.isConfigured) {
            scheduleCloudUpload(app, settings, allowAfterLocalDelete = true)
        } else {
            Log.i(CLOUD_LOG_TAG, "Cloud sync disabled or missing URL.")
        }
    }

    fun restoreFromCloud(context: Context) {
        val app = context.applicationContext
        if (_state.value.isRecording || _state.value.isFollowing) {
            _events.tryEmit(UiEvent.Message("Cloud obnovu nelze spustit během aktivního záznamu nebo follow režimu."))
            return
        }

        val settings = CloudSettingsStorage.load(app)
        if (!settings.isConfigured) {
            _events.tryEmit(UiEvent.Message("Cloud URL není nastavena nebo sync není zapnutý."))
            return
        }

        ioScope.launch {
            _state.value = _state.value.copy(cloudSyncStatus = "Cloud: obnovuji...")
            try {
                Log.i(CLOUD_LOG_TAG, "Restoring cloud backup from ${settings.endpointUrl}.")
                CloudBackupSync.restore(app, settings)
                synchronized(cloudSyncLock) {
                    localDeletePendingCloudConfirmation = false
                }
                reloadFromStorage(app, _state.value.copy(cloudSyncStatus = "Cloud: obnoveno."))
                scheduleCloudUpload(app, allowAfterLocalDelete = true)
                _events.tryEmit(UiEvent.Message("Cloud obnova hotova."))
            } catch (t: Throwable) {
                Log.e(CLOUD_LOG_TAG, "Cloud restore failed.", t)
                val message = "Cloud obnova selhala: ${errorText(t)}"
                _state.value = _state.value.copy(cloudSyncStatus = message)
                _events.tryEmit(UiEvent.Message(message))
            }
        }
    }

    fun importRideFromUri(context: Context, horseId: String, sourceUri: Uri) {
        val app = context.applicationContext
        ioScope.launch {
            val horse = HorseStorage.listHorses(app).firstOrNull { it.id == horseId }
            if (horse == null) {
                _events.tryEmit(UiEvent.Message("Import selhal: kůň nebyl nalezen."))
                return@launch
            }

            val tempFile = File.createTempFile("horse_tracker_import_", ".gpx", app.cacheDir)
            try {
                val input =
                    app.contentResolver.openInputStream(sourceUri)
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

                val ridesDir = File(app.filesDir, "rides").apply { mkdirs() }
                val ts = ride.points.firstOrNull()?.timeEpochMs ?: System.currentTimeMillis()
                val baseName = buildRideBaseName(ride.points, horse.name, ts)
                val uniqueBase = ensureUniqueBaseName(ridesDir, baseName, ts)
                val gpxName = "$uniqueBase.gpx"
                val metaName = "$uniqueBase.meta.json"

                val gpxFile = File(ridesDir, gpxName)
                GpxStorage.writeGpx(gpxFile, ride.points, ride.waypoints)

                val meta = buildRideMeta(horse.id, gpxName, metaName, ride.points)
                RideMetaStorage.writeMeta(app, meta, metaName)
                refreshStats(app)
                refreshRides(app)

                _events.tryEmit(UiEvent.Message("GPX importován ke koni ${horse.name}."))
            } catch (t: Throwable) {
                _events.tryEmit(UiEvent.Message("Import GPX selhal: ${errorText(t)}"))
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

    private fun effectiveFollowSegments(state: AppState): List<SpeedSegment> {
        val base = state.routeFollowSegments
        return if (!state.isReversed) {
            base
        } else {
            base.asReversed().map { segment ->
                segment.copy(
                    startLat = segment.endLat,
                    startLon = segment.endLon,
                    endLat = segment.startLat,
                    endLon = segment.startLon,
                )
            }
        }
    }

    private fun refreshStats(context: Context) {
        val app = context.applicationContext
        ioScope.launch {
            val horses = HorseStorage.listHorses(app)
            val stats = computeStats(app, horses)
            _state.value = _state.value.copy(horses = horses, horseStats = stats)
        }
    }

    private fun scheduleCloudUpload(
        context: Context,
        initialSettings: CloudSettingsStorage.CloudSettings? = null,
        allowAfterLocalDelete: Boolean = false,
    ) {
        val app = context.applicationContext
        val settings = initialSettings ?: CloudSettingsStorage.load(app)
        if (!settings.isConfigured) {
            Log.d(CLOUD_LOG_TAG, "Cloud upload skipped: sync disabled or URL missing.")
            return
        }
        if (!allowAfterLocalDelete && synchronized(cloudSyncLock) { localDeletePendingCloudConfirmation }) {
            _state.value =
                _state.value.copy(
                    cloudSyncStatus = "Cloud: lokální smazání čeká na ruční Uložit nebo Obnovit.",
                )
            Log.i(CLOUD_LOG_TAG, "Cloud upload skipped after local delete until explicit user action.")
            return
        }
        val request =
            CloudUploadRequest(
                settings = settings,
                minimumSummary = AppBackupStorage.summary(app),
                allowAfterLocalDelete = allowAfterLocalDelete,
            )

        synchronized(cloudSyncLock) {
            if (cloudSyncRunning) {
                Log.d(CLOUD_LOG_TAG, "Cloud upload already running, scheduling another pass.")
                pendingCloudUpload = request
                return
            }
            cloudSyncRunning = true
        }

        ioScope.launch {
            var currentRequest: CloudUploadRequest? = request
            while (true) {
                val uploadRequest =
                    currentRequest
                        ?: CloudUploadRequest(
                            settings = CloudSettingsStorage.load(app),
                            minimumSummary = AppBackupStorage.summary(app),
                            allowAfterLocalDelete = false,
                        )
                currentRequest = null
                val currentSettings = uploadRequest.settings
                if (!currentSettings.isConfigured) {
                    _state.value = _state.value.copy(cloudSyncStatus = "Cloud sync vypnutý.")
                    Log.i(CLOUD_LOG_TAG, "Cloud upload stopped: sync disabled or URL missing.")
                    synchronized(cloudSyncLock) {
                        cloudSyncRunning = false
                        pendingCloudUpload = null
                    }
                    return@launch
                }

                _state.value = _state.value.copy(cloudSyncStatus = "Cloud: ukládám...")
                Log.i(CLOUD_LOG_TAG, "Uploading cloud backup to ${currentSettings.endpointUrl}.")
                val result =
                    runCatching {
                        val currentSummary = AppBackupStorage.summary(app)
                        check(!currentSummary.isSmallerThan(uploadRequest.minimumSummary)) {
                            "Uložení do cloudu přeskočeno: lokální data se po spuštění ukládání zmenšila."
                        }
                        CloudBackupSync.upload(app, currentSettings)
                    }
                result
                    .onSuccess { Log.i(CLOUD_LOG_TAG, "Cloud upload finished successfully.") }
                    .onFailure { Log.e(CLOUD_LOG_TAG, "Cloud upload failed.", it) }
                if (result.isSuccess && uploadRequest.allowAfterLocalDelete) {
                    synchronized(cloudSyncLock) {
                        localDeletePendingCloudConfirmation = false
                    }
                }
                _state.value =
                    _state.value.copy(
                        cloudSyncStatus =
                            result.fold(
                                onSuccess = { "Cloud: uloženo ${formatClock(System.currentTimeMillis())}" },
                                onFailure = { "Cloud chyba: ${it.message ?: it::class.java.simpleName}" },
                            ),
                    )

                val nextRequest =
                    synchronized(cloudSyncLock) {
                        val pending = pendingCloudUpload
                        if (pending != null) {
                            pendingCloudUpload = null
                            pending
                        } else {
                            cloudSyncRunning = false
                            null
                        }
                    }
                currentRequest = nextRequest ?: return@launch
            }
        }
    }

    private fun AppBackupStorage.BackupSummary.isSmallerThan(other: AppBackupStorage.BackupSummary): Boolean =
        horsesCount < other.horsesCount || ridesCount < other.ridesCount

    private fun reloadFromStorage(context: Context, baseState: AppState = _state.value) {
        val app = context.applicationContext
        appContext = app
        _state.value = baseState.copy(isLoadingData = true)
        ioScope.launch {
            try {
                val horses = HorseStorage.listHorses(app)
                val selected = SelectionStorage.getSelectedHorseId(app)
                val stats = computeStats(app, horses)
                val rides = listRidesInternal(app, selected)
                val (warnM, backM) = FollowSettingsStorage.load(app)
                val cloud = CloudSettingsStorage.load(app)
                _state.value =
                    baseState.copy(
                        isLoadingData = false,
                        horses = horses,
                        selectedHorseId = selected,
                        horseStats = stats,
                        rides = rides,
                        offRouteWarnThresholdM = warnM,
                        backOnRouteThresholdM = backM,
                        cloudEndpointUrl = cloud.endpointUrl,
                        cloudToken = cloud.token,
                        cloudSyncEnabled = cloud.enabled,
                    )
            } catch (t: Throwable) {
                _state.value = baseState.copy(isLoadingData = false)
                _events.tryEmit(UiEvent.Message("Obnovení dat selhalo: ${errorText(t)}"))
            }
        }
    }

    private fun computeStats(context: Context, horses: List<Horse>): Map<String, RideStats> {
        val metas = RideMetaStorage.listMetas(context)
        val ridesDir = File(context.filesDir, "rides")
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
                val rideMax =
                    runCatching {
                        val gpxFile = File(ridesDir, m.gpxFileName)
                        if (gpxFile.exists()) computeReliableMaxSpeed(GpxStorage.readGpx(gpxFile).points) else m.maxSpeedMps
                    }.getOrDefault(m.maxSpeedMps)
                if (rideMax > maxSpeed) maxSpeed = rideMax
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
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            distance += Geo.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        }
        val durationMs = (end - start).coerceAtLeast(1L)
        val avgSpeed = distance / (durationMs.toDouble() / 1000.0)
        val maxSpeed = computeReliableMaxSpeed(points)
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

    private fun computeReliableMaxSpeed(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0

        val segmentSpeeds = ArrayList<Double>(points.size - 1)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val dtMs = (current.timeEpochMs - previous.timeEpochMs).coerceAtLeast(0L)
            if (dtMs < 500L || dtMs > 20_000L) continue

            val distance = Geo.haversineMeters(previous.lat, previous.lon, current.lat, current.lon)
            val segmentSpeed = distance / (dtMs.toDouble() / 1000.0)
            val maxAccuracy = maxOf(previous.accuracyM, current.accuracyM)
            if (maxAccuracy > 50.0) continue
            if (segmentSpeed in 0.0..8.0) {
                segmentSpeeds += segmentSpeed
            }
        }

        if (segmentSpeeds.isEmpty()) return 0.0
        if (segmentSpeeds.size < 3) return segmentSpeeds.maxOrNull() ?: 0.0

        var maxRolling = 0.0
        for (i in 0..segmentSpeeds.size - 3) {
            val rolling = (segmentSpeeds[i] + segmentSpeeds[i + 1] + segmentSpeeds[i + 2]) / 3.0
            if (rolling > maxRolling) maxRolling = rolling
        }
        return maxRolling
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
            clearDraftBlocking(context)
            refreshStats(context)
            refreshRides(context)

            _events.tryEmit(UiEvent.RideSaved(gpxFile.absolutePath))
            scheduleCloudUpload(context)
        } catch (t: Throwable) {
            _events.tryEmit(UiEvent.Message("Uložení selhalo: ${errorText(t)}"))
        }
    }

    private fun buildRideBaseName(points: List<TrackPoint>, horseName: String, fallbackTs: Long): String {
        val first = points.firstOrNull()
        val ts = first?.timeEpochMs?.takeIf { it > 0 } ?: fallbackTs
        val date = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date(ts))
        val horse = sanitizeName(horseName)
        return "$date $horse"
    }

    private fun formatClock(epochMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

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
            clearDraftBlocking(context)
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
        if (snapshot.points.isEmpty() && snapshot.rideWaypoints.isEmpty()) {
            clearDraftAsync()
            return
        }
        val generation = synchronized(draftIoLock) { draftGeneration }
        ioScope.launch {
            synchronized(draftIoLock) {
                if (generation != draftGeneration) return@synchronized
                DraftRideStorage.writeDraft(context, snapshot.selectedHorseId, snapshot.points, snapshot.rideWaypoints)
            }
        }
    }

    private fun clearDraftAsync() {
        val context = appContext ?: return
        ioScope.launch { clearDraftBlocking(context) }
    }

    private fun clearDraftBlocking(context: Context) {
        synchronized(draftIoLock) {
            draftGeneration++
            DraftRideStorage.clear(context)
        }
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
                        followSegments = emptyList(),
                        followActive = false,
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

    private fun errorText(t: Throwable): String = t.message ?: t::class.java.simpleName
}
