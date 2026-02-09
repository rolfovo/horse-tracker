package cz.example.horsetracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import cz.example.horsetracker.MainActivity
import cz.example.horsetracker.R
import cz.example.horsetracker.geo.Geo
import cz.example.horsetracker.permissions.Permissions
import cz.example.horsetracker.ride.RideRepository
import cz.example.horsetracker.ride.TrackPoint
import cz.example.horsetracker.ride.Waypoint
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TrackingService : Service() {
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private var lastAcceptedLocation: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var isRecording = false
    private var isFollowing = false
    private val announcedWaypointAtMs = ConcurrentHashMap<String, Long>()

    private val listener =
        LocationListener { location ->
            lastLocation = location
            if (!shouldAcceptLocation(location)) return@LocationListener

            val smoothed = smoothLocation(location, lastAcceptedLocation)
            val prev = lastAcceptedLocation
            val speedMps =
                if (smoothed.hasSpeed() && smoothed.speed > 0f) {
                    smoothed.speed.toDouble()
                } else if (prev != null) {
                    val dtMs = (smoothed.time - prev.time).coerceAtLeast(0L)
                    if (dtMs > 0) (prev.distanceTo(smoothed).toDouble() / (dtMs.toDouble() / 1000.0)) else 0.0
                } else {
                    0.0
                }
            val headingDeg =
                if (smoothed.hasBearing() && speedMps > 0.3) {
                    smoothed.bearing.toDouble()
                } else if (prev != null) {
                    val d = prev.distanceTo(smoothed).toDouble()
                    if (d > 1.0) prev.bearingTo(smoothed).toDouble() else null
                } else {
                    null
                }

            lastAcceptedLocation = Location(smoothed)
            RideRepository.onLocation(
                TrackPoint(
                    lat = smoothed.latitude,
                    lon = smoothed.longitude,
                    timeEpochMs = smoothed.time,
                    speedMps = speedMps,
                    accuracyM = smoothed.accuracy.toDouble(),
                    headingDeg = headingDeg,
                ),
            )

            maybeAnnounceNearbyWaypoint(smoothed)
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureNotificationChannel()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_ADD_WAYPOINT -> addWaypoint(intent.getStringExtra(EXTRA_WAYPOINT_LABEL))
            ACTION_START_FOLLOW -> startFollow()
            ACTION_STOP_FOLLOW -> stopFollow()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (!Permissions.hasFineOrCoarseLocation(this)) {
            RideRepository.setRecording(false)
            stopSelf()
            return
        }
        isRecording = true
        RideRepository.setRecording(true)
        try {
            startForeground(NOTIF_ID, buildNotification(statusText()))
        } catch (_: SecurityException) {
            isRecording = false
            RideRepository.setRecording(false)
            stopSelf()
            return
        }
        acquireWakeLock()
        requestLocation()
    }

    private fun stopRecording() {
        isRecording = false
        RideRepository.setRecording(false)
        if (!isFollowing) {
            stopLocation()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            startForeground(NOTIF_ID, buildNotification(statusText()))
        }
    }

    private fun startFollow() {
        if (!Permissions.hasFineOrCoarseLocation(this)) {
            RideRepository.setFollowing(false)
            stopSelf()
            return
        }
        isFollowing = true
        RideRepository.setFollowing(true)
        try {
            startForeground(NOTIF_ID, buildNotification(statusText()))
        } catch (_: SecurityException) {
            isFollowing = false
            RideRepository.setFollowing(false)
            stopSelf()
            return
        }
        acquireWakeLock()
        requestLocation()
    }

    private fun stopFollow() {
        isFollowing = false
        RideRepository.setFollowing(false)
        if (!isRecording) {
            stopLocation()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            startForeground(NOTIF_ID, buildNotification(statusText()))
        }
    }

    private fun addWaypoint(label: String?) {
        val loc = lastLocation ?: return
        RideRepository.addWaypoint(
            Waypoint(
                lat = loc.latitude,
                lon = loc.longitude,
                timeEpochMs = System.currentTimeMillis(),
                label = label?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun requestLocation() {
        val lm = locationManager ?: return
        try {
            // Network provider často dělá "odbočky" / skoky. Primárně jedeme GPS.
            val gpsEnabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            if (gpsEnabled) {
                // Pro jízdu na koni typicky stačí 1s/1m. Uprav podle potřeby.
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
            } else {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 3f, listener)
            }
        } catch (_: SecurityException) {
            // Permission missing -> nic nedělej.
        }
    }

    private fun stopLocation() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Horse Tracker")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun statusText(): String =
        when {
            isRecording && isFollowing -> "Záznam + follow"
            isRecording -> "Záznam běží"
            isFollowing -> "Follow běží"
            else -> "Běží"
        }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch =
            NotificationChannel(
                CHANNEL_ID,
                "Tracking",
                NotificationManager.IMPORTANCE_LOW,
            )
        nm.createNotificationChannel(ch)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld == true) return
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HorseTracker:Tracking").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    override fun onDestroy() {
        stopLocation()
        releaseWakeLock()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun shouldAcceptLocation(location: Location): Boolean {
        if (!location.hasAccuracy()) return false
        val acc = location.accuracy
        if (acc <= 0f) return false

        // hrubý filtr proti šumu a velkým skokům
        if (acc > 50f) return false

        val prev = lastAcceptedLocation ?: return true
        val dtMs = location.time - prev.time
        if (dtMs < 500) return false

        val distM = prev.distanceTo(location).toDouble()
        if (distM < 0.8) return false

        val impliedSpeed = distM / (dtMs.toDouble() / 1000.0)

        // Skoky, které nedávají smysl pro záznam jízdy.
        val maxPlausibleSpeedMps = 25.0 // ~90 km/h, bezpečný strop pro odfiltrování GPS glitchů
        if (impliedSpeed > maxPlausibleSpeedMps && acc > 15f) return false
        if (distM > 120.0 && acc > 12f) return false

        return true
    }

    private fun smoothLocation(raw: Location, prev: Location?): Location {
        if (prev == null) return raw
        val distance = prev.distanceTo(raw).toDouble()
        // Jemne vyhlazeni pouze u malych odchylek, aby zustaly ostrejsi zatacky.
        if (distance > 30.0) return raw

        val alpha = 0.35
        val out = Location(raw)
        out.latitude = prev.latitude * (1.0 - alpha) + raw.latitude * alpha
        out.longitude = prev.longitude * (1.0 - alpha) + raw.longitude * alpha
        return out
    }

    private fun initTts() {
        tts =
            TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("cs", "CZ")
                }
            }
    }

    private fun maybeAnnounceNearbyWaypoint(location: Location) {
        if (!(isRecording || isFollowing)) return

        val state = RideRepository.state.value
        if (state.waypoints.isEmpty()) return

        val now = System.currentTimeMillis()
        val triggerDistanceM = 28.0
        val repeatCooldownMs = 10 * 60 * 1000L

        state.waypoints.forEach { wp ->
            val label = wp.label?.trim().orEmpty()
            if (label.isEmpty()) return@forEach

            val distance = Geo.haversineMeters(location.latitude, location.longitude, wp.lat, wp.lon)
            if (distance > triggerDistanceM) return@forEach

            val key = "${wp.timeEpochMs}_${wp.lat}_${wp.lon}_${label}"
            val last = announcedWaypointAtMs[key] ?: 0L
            if (now - last < repeatCooldownMs) return@forEach

            announcedWaypointAtMs[key] = now
            tts?.speak(label, TextToSpeech.QUEUE_ADD, null, key)
        }
    }

    companion object {
        const val ACTION_START_RECORDING = "cz.example.horsetracker.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "cz.example.horsetracker.action.STOP_RECORDING"
        const val ACTION_ADD_WAYPOINT = "cz.example.horsetracker.action.ADD_WAYPOINT"
        const val EXTRA_WAYPOINT_LABEL = "cz.example.horsetracker.extra.WAYPOINT_LABEL"
        const val ACTION_START_FOLLOW = "cz.example.horsetracker.action.START_FOLLOW"
        const val ACTION_STOP_FOLLOW = "cz.example.horsetracker.action.STOP_FOLLOW"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1001
    }
}
