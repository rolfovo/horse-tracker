package cz.example.horsetracker.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import cz.example.horsetracker.map.RideMap
import cz.example.horsetracker.permissions.PermissionRepository
import cz.example.horsetracker.ride.Horse
import cz.example.horsetracker.ride.RideSummary
import cz.example.horsetracker.ride.RideStats
import cz.example.horsetracker.ride.RideRepository
import cz.example.horsetracker.service.TrackingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(
    onRequestLocationPermission: () -> Unit,
    onRequestBackgroundLocationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val state by RideRepository.state.collectAsState()
    val hasLocation by PermissionRepository.hasLocation.collectAsState()
    val hasBackgroundLocation by PermissionRepository.hasBackgroundLocation.collectAsState()

    val selectedHorse =
        state.selectedHorseId?.let { id -> state.horses.firstOrNull { it.id == id } }

    val hasTrackingPermission = hasLocation && hasBackgroundLocation
    val canStartRecording = hasLocation && !state.isRecording && !state.isFollowing

    var showHorsePicker by rememberSaveable { mutableStateOf(true) }
    var showRides by remember { mutableStateOf(false) }
    var ridesFilterHorseId by remember { mutableStateOf<String?>(null) }
    var showWaypointDialog by remember { mutableStateOf(false) }
    var waypointLabel by remember { mutableStateOf("") }
    var showStopRecordingDialog by remember { mutableStateOf(false) }
    var showStopFollowDialog by remember { mutableStateOf(false) }
    var followHorseName by remember { mutableStateOf(selectedHorse?.name.orEmpty()) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var offlineRadiusKm by remember { mutableStateOf(5.0) }
    var pendingRideExport by remember { mutableStateOf<RideSummary?>(null) }
    var pendingHorseImport by remember { mutableStateOf<Horse?>(null) }
    var showImportBackupConfirm by remember { mutableStateOf(false) }

    val voiceNoteLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val text =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(context, "Hlasová poznámka nebyla rozpoznána.", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_ADD_WAYPOINT
                putExtra(TrackingService.EXTRA_WAYPOINT_LABEL, text)
            }
            context.startService(intent)
        }
    val exportRideLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri: Uri? ->
            val ride = pendingRideExport
            pendingRideExport = null
            if (uri == null || ride == null) return@rememberLauncherForActivityResult
            RideRepository.exportRideToUri(context, ride.metaFileName, uri)
        }
    val exportBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            RideRepository.exportBackupToUri(context, uri)
        }
    val importBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            RideRepository.importBackupFromUri(context, uri)
        }
    val importRideLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val horse = pendingHorseImport
            pendingHorseImport = null
            if (uri == null || horse == null) return@rememberLauncherForActivityResult
            RideRepository.importRideFromUri(context, horse.id, uri)
        }

    LaunchedEffect(Unit) {
        RideRepository.events.collect { e ->
            when (e) {
                is RideRepository.UiEvent.RideSaved -> {
                    val name = java.io.File(e.filePath).name
                    Toast.makeText(context, "Trasa $name uložena do ${e.filePath}", Toast.LENGTH_LONG).show()
                }
                is RideRepository.UiEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showHorsePicker || selectedHorse == null) {
        HorseSelectScreen(
            horses = state.horses,
            horseStats = state.horseStats,
            isLoadingData = state.isLoadingData,
            onSelect = {
                RideRepository.selectHorse(context, it.id)
                showHorsePicker = false
            },
            onAdd = {
                RideRepository.addHorse(context, it)
                showHorsePicker = false
            },
            onDelete = { RideRepository.deleteHorse(context, it.id) },
            onClose = if (selectedHorse != null) ({ showHorsePicker = false }) else null,
            onImportGpx = { horse ->
                pendingHorseImport = horse
                try {
                    importRideLauncher.launch(arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "application/xml"))
                } catch (_: ActivityNotFoundException) {
                    pendingHorseImport = null
                    Toast.makeText(context, "V zařízení není dostupný správce dokumentů.", Toast.LENGTH_SHORT).show()
                }
            },
            onExportBackup = {
                try {
                    exportBackupLauncher.launch("horse_tracker_backup.zip")
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "V zařízení není dostupný správce dokumentů.", Toast.LENGTH_SHORT).show()
                }
            },
            onImportBackup = { showImportBackupConfirm = true },
            backupActionsEnabled = !state.isRecording && !state.isFollowing,
            hasBackupData = state.horses.isNotEmpty() || state.rides.isNotEmpty(),
        )
        if (showImportBackupConfirm) {
            AlertDialog(
                onDismissRequest = { showImportBackupConfirm = false },
                title = { Text("Import backupu") },
                text = { Text("Import přepíše stávající koně, uložené jízdy a nastavení. Pokračovat?") },
                confirmButton = {
                    SmallButton(
                        onClick = {
                            showImportBackupConfirm = false
                            try {
                                importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "V zařízení není dostupný správce dokumentů.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text("Importovat") }
                },
                dismissButton = {
                    SmallButton(onClick = { showImportBackupConfirm = false }) { Text("Zrušit") }
                },
            )
        }
        return
    }

    if (showRides) {
        RideListScreen(
            rides = state.rides,
            horses = state.horses,
            filterHorseId = ridesFilterHorseId,
            onBack = { showRides = false },
            onLoad = {
                RideRepository.loadRide(context, it.metaFileName)
                showRides = false
            },
            onDelete = {
                RideRepository.deleteRide(
                    context = context,
                    metaFileName = it.metaFileName,
                    horseIdFilter = ridesFilterHorseId,
                )
            },
            onExport = { ride ->
                pendingRideExport = ride
                try {
                    exportRideLauncher.launch(ride.gpxFileName)
                } catch (_: ActivityNotFoundException) {
                    pendingRideExport = null
                    Toast.makeText(context, "V zařízení není dostupný správce dokumentů.", Toast.LENGTH_SHORT).show()
                }
            },
            onEmail = { ride ->
                RideRepository.emailRideGpx(
                    context = context,
                    metaFileName = ride.metaFileName,
                    emailAddress = "rolfovo@gmail.com",
                )
            },
            onSelectHorseFilter = { horseId ->
                ridesFilterHorseId = horseId
                RideRepository.refreshRides(context, horseId = horseId)
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorseBar(
            selected = selectedHorse,
            onChange = { showHorsePicker = true },
        )

        RideMap(
            modifier = Modifier.weight(1f),
            mapState = state.mapState,
            autoCenter = state.isAutoCenter,
            onWaypointTap = { waypoint ->
                val label = waypoint.label?.trim().orEmpty()
                if (label.isNotEmpty()) {
                    val intent = Intent(context, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_SPEAK_WAYPOINT
                        putExtra(TrackingService.EXTRA_WAYPOINT_LABEL, label)
                        putExtra(TrackingService.EXTRA_SPEAK_REVERSED, state.isFollowing && state.isReversed)
                    }
                    context.startService(intent)
                }
            },
        )

        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!hasLocation) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(onClick = onRequestLocationPermission) { Text("Povolit polohu") }
                    SmallButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                    ) { Text("Otevřít nastavení") }
                }
                Text("Bez povolené polohy Android nedovolí spustit location foreground service (targetSdk 34).")
            } else if (!hasBackgroundLocation) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(onClick = onRequestBackgroundLocationPermission) {
                        Text("Poloha na pozadí")
                    }
                    SmallButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                    ) { Text("Otevřít nastavení") }
                }
                Text("Pro záznam při zhasnutém displeji nebo po odchodu z aplikace je potřeba povolit i polohu na pozadí.")
            }

            val stats = state.horseStats[selectedHorse.id]
            if (stats != null) {
                Text(
                    "Jízdy: ${stats.ridesCount} | Čas: ${formatDuration(stats.totalDurationMs)} | " +
                        "Vzdál.: ${"%.1f".format(stats.totalDistanceM / 1000.0)} km | " +
                        "Avg: ${"%.1f".format(stats.avgSpeedMps * 3.6)} km/h | Max: ${"%.1f".format(stats.maxSpeedMps * 3.6)} km/h",
                    fontSize = 12.sp,
                    color = Color(0xFF4F5C67),
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallButton(
                    onClick = {
                        val intent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_START_RECORDING
                        }
                        context.startForegroundService(intent)
                    },
                    enabled = canStartRecording,
                    tone = ButtonTone.Primary,
                ) { Text("Start") }

                SmallButton(
                    onClick = {
                        if (state.points.isNotEmpty()) {
                            showStopRecordingDialog = true
                        } else {
                            val intent = Intent(context, TrackingService::class.java).apply {
                                action = TrackingService.ACTION_STOP_RECORDING
                            }
                            context.startService(intent)
                        }
                    },
                    enabled = state.isRecording,
                    tone = ButtonTone.Danger,
                ) { Text("Stop") }

                SmallButton(
                    onClick = {
                        waypointLabel = ""
                        showWaypointDialog = true
                    },
                    enabled = state.isRecording,
                    tone = ButtonTone.Neutral,
                ) { Text("Bod") }

                SmallButton(
                    onClick = {
                        val intent =
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Nadiktuj poznámku k bodu")
                            }
                        try {
                            voiceNoteLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "V zařízení není dostupné rozpoznávání řeči.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = state.isRecording,
                    tone = ButtonTone.Neutral,
                ) { Text("Hlas") }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallButton(
                    onClick = {
                        ridesFilterHorseId = selectedHorse.id
                        RideRepository.refreshRides(context, horseId = ridesFilterHorseId)
                        showRides = true
                    },
                    tone = ButtonTone.Neutral,
                ) { Text("Jízdy") }

                SmallButton(
                    onClick = {
                        if (state.isFollowing) {
                            followHorseName = selectedHorse.name
                            showStopFollowDialog = true
                        } else {
                            val intent = Intent(context, TrackingService::class.java).apply {
                                action = TrackingService.ACTION_START_FOLLOW
                            }
                            context.startForegroundService(intent)
                        }
                    },
                    enabled = hasTrackingPermission && state.routeToFollow.isNotEmpty(),
                    tone = if (state.isFollowing) ButtonTone.Danger else ButtonTone.Accent,
                ) { Text(if (state.isFollowing) "Stop follow" else "Follow") }

                SmallButton(
                    onClick = { RideRepository.setReverseMode(false) },
                    enabled = state.routeToFollow.isNotEmpty(),
                    tone = if (!state.isReversed) ButtonTone.Selected else ButtonTone.Neutral,
                ) { Text(if (state.isReversed) "Normal" else "Normal ✓") }
                SmallButton(
                    onClick = { RideRepository.setReverseMode(true) },
                    enabled = state.routeToFollow.isNotEmpty(),
                    tone = if (state.isReversed) ButtonTone.Selected else ButtonTone.Neutral,
                ) { Text(if (state.isReversed) "Reverse ✓" else "Reverse") }
            }

            CompactRidePanel(
                isRecording = state.isRecording,
                isFollowing = state.isFollowing,
                isReversed = state.isReversed,
                durationText = formatDuration(state.currentDurationMs),
                distanceKm = state.currentDistanceM / 1000.0,
                speedKmh = state.lastSpeedMps * 3.6,
                avgSpeedKmh = state.currentAvgSpeedMps * 3.6,
                autoCenter = state.isAutoCenter,
                offRouteWarnThresholdM = state.offRouteWarnThresholdM.toInt(),
                backOnRouteThresholdM = state.backOnRouteThresholdM.toInt(),
                hasLocation = hasLocation,
                onToggleAutoCenter = { RideRepository.toggleAutoCenter() },
                onShowOfflineDialog = { showOfflineDialog = true },
                onStopAll = {
                    val stopIntent = Intent(context, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_STOP_ALL
                    }
                    context.startService(stopIntent)
                    (context as? Activity)?.finishAffinity()
                },
                onDecreaseOffRoute = { RideRepository.updateOffRouteWarnThreshold(-5.0) },
                onIncreaseOffRoute = { RideRepository.updateOffRouteWarnThreshold(5.0) },
                onDecreaseBackOnRoute = { RideRepository.updateBackOnRouteThreshold(-1.0) },
                onIncreaseBackOnRoute = { RideRepository.updateBackOnRouteThreshold(1.0) },
            )
        }
    }

    if (showWaypointDialog) {
        AlertDialog(
            onDismissRequest = { showWaypointDialog = false },
            title = { Text("Název bodu") },
            text = {
                OutlinedTextField(
                    value = waypointLabel,
                    onValueChange = { waypointLabel = it },
                    singleLine = true,
                    label = { Text("Např. Brod, Křížení, Pauza") },
                )
            },
            confirmButton = {
                SmallButton(
                    onClick = {
                        val intent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_ADD_WAYPOINT
                            putExtra(TrackingService.EXTRA_WAYPOINT_LABEL, waypointLabel.trim())
                        }
                        context.startService(intent)
                        showWaypointDialog = false
                    },
                ) { Text("Uložit") }
            },
            dismissButton = {
                SmallButton(onClick = { showWaypointDialog = false }) { Text("Zrušit") }
            },
        )
    }

    if (showStopRecordingDialog) {
        AlertDialog(
            onDismissRequest = { showStopRecordingDialog = false },
            title = { Text("Ukončit záznam") },
            text = { Text("Chceš právě zaznamenanou trasu uložit?") },
            confirmButton = {
                SmallButton(
                    onClick = {
                        val stopIntent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP_RECORDING
                        }
                        context.startService(stopIntent)
                        RideRepository.saveCurrentRide(context)
                        showStopRecordingDialog = false
                    },
                ) { Text("Ukončit a uložit") }
            },
            dismissButton = {
                SmallButton(
                    onClick = {
                        val stopIntent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP_RECORDING
                        }
                        context.startService(stopIntent)
                        showStopRecordingDialog = false
                    },
                ) { Text("Jen ukončit") }
            },
        )
    }

    if (showStopFollowDialog) {
        AlertDialog(
            onDismissRequest = { showStopFollowDialog = false },
            title = { Text("Ukončit follow") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Uložit následovanou trasu?")
                    OutlinedTextField(
                        value = followHorseName,
                        onValueChange = { followHorseName = it },
                        singleLine = true,
                        label = { Text("Jméno koně") },
                    )
                    if (state.horses.isNotEmpty()) {
                        Text("Existující koně:", fontSize = 12.sp, color = Color(0xFF5F6B76))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.horses.forEach { horse ->
                                SmallButton(
                                    onClick = { followHorseName = horse.name },
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 34.dp,
                                ) { Text(horse.name) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                SmallButton(
                    onClick = {
                        val stopIntent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP_FOLLOW
                        }
                        context.startService(stopIntent)
                        RideRepository.saveCurrentRideForHorseName(context, followHorseName)
                        showStopFollowDialog = false
                    },
                ) { Text("Ukončit a uložit") }
            },
            dismissButton = {
                SmallButton(
                    onClick = {
                        val stopIntent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP_FOLLOW
                        }
                        context.startService(stopIntent)
                        showStopFollowDialog = false
                    },
                ) { Text("Ukončit bez uložení") }
            },
        )
    }

    if (showOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineDialog = false },
            title = { Text("Stáhnout offline mapu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vyber rozsah kolem aktuální polohy:")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(
                            onClick = { offlineRadiusKm = 2.0 },
                            height = 32.dp,
                        ) { Text(if (offlineRadiusKm == 2.0) "✓ 2 km" else "2 km") }
                        SmallButton(
                            onClick = { offlineRadiusKm = 5.0 },
                            height = 32.dp,
                        ) { Text(if (offlineRadiusKm == 5.0) "✓ 5 km" else "5 km") }
                        SmallButton(
                            onClick = { offlineRadiusKm = 10.0 },
                            height = 32.dp,
                        ) { Text(if (offlineRadiusKm == 10.0) "✓ 10 km" else "10 km") }
                    }
                }
            },
            confirmButton = {
                SmallButton(
                    onClick = {
                        RideRepository.prefetchOfflineAroundCurrent(context, offlineRadiusKm)
                        showOfflineDialog = false
                    },
                ) { Text("Stáhnout") }
            },
            dismissButton = {
                SmallButton(onClick = { showOfflineDialog = false }) { Text("Zrušit") }
            },
        )
    }

}

@Composable
private fun HorseBar(selected: Horse, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Kůň: ${selected.name}", modifier = Modifier.weight(1f))
        SmallButton(onClick = onChange, height = 32.dp) { Text("Změnit") }
    }
}

@Composable
private fun HorseSelectScreen(
    horses: List<Horse>,
    horseStats: Map<String, RideStats>,
    isLoadingData: Boolean,
    onSelect: (Horse) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (Horse) -> Unit,
    onClose: (() -> Unit)?,
    onImportGpx: (Horse) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    backupActionsEnabled: Boolean,
    hasBackupData: Boolean,
) {
    var newHorse by remember { mutableStateOf("") }
    var statsHorse by remember { mutableStateOf<Horse?>(null) }
    var deleteHorse by remember { mutableStateOf<Horse?>(null) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vyber koně", modifier = Modifier.weight(1f))
            if (onClose != null) SmallButton(onClick = onClose, height = 32.dp) { Text("Zpět") }
        }
        if (isLoadingData && horses.isEmpty()) {
            Text("Načítám uložené koně a jízdy…")
        } else if (horses.isEmpty()) {
            Text("Zatím nemáš žádného koně. Přidej prvního:")
        } else {
            if (isLoadingData) {
                Text("Obnovuji data…", fontSize = 12.sp, color = Color(0xFF5F6B76))
            }
            horses.forEach { h ->
                val st = horseStats[h.id]
                val ridesCount = st?.ridesCount ?: 0
                val line = "${h.name} • ${ridesCount} jízd"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HorseItem(
                        text = line,
                        onClick = { onSelect(h) },
                        onLongClick = { statsHorse = h },
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton(onClick = { onImportGpx(h) }, height = 36.dp) { Text("Import GPX") }
                    SmallButton(onClick = { deleteHorse = h }, height = 36.dp) { Text("Smazat") }
                }
            }
        }

        OutlinedTextField(
            value = newHorse,
            onValueChange = { newHorse = it },
            label = { Text("Nový kůň") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        SmallButton(
            onClick = {
                val name = newHorse.trim()
                if (name.isNotEmpty()) {
                    onAdd(name)
                    newHorse = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Přidat a vybrat") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SmallButton(
                onClick = onExportBackup,
                modifier = Modifier.weight(1f),
                enabled = backupActionsEnabled && hasBackupData,
            ) { Text("Export backup") }
            SmallButton(
                onClick = onImportBackup,
                modifier = Modifier.weight(1f),
                enabled = backupActionsEnabled,
            ) { Text("Import backup") }
        }
    }

    val h = statsHorse
    if (h != null) {
        val st = horseStats[h.id]
        AlertDialog(
            onDismissRequest = { statsHorse = null },
            title = { Text(h.name) },
            text = {
                if (st == null) {
                    Text("Zatím bez jízd.")
                } else {
                    Text(
                        "Počet jízd: ${st.ridesCount}\n" +
                            "Celkový čas: ${formatDuration(st.totalDurationMs)}\n" +
                            "Celková vzdálenost: ${"%.1f".format(st.totalDistanceM / 1000.0)} km\n" +
                            "Průměrná rychlost: ${"%.1f".format(st.avgSpeedMps * 3.6)} km/h\n" +
                            "Max rychlost: ${"%.1f".format(st.maxSpeedMps * 3.6)} km/h",
                    )
                }
            },
            confirmButton = {
                SmallButton(onClick = { statsHorse = null }, height = 36.dp) { Text("OK") }
            },
        )
    }

    val del = deleteHorse
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteHorse = null },
            title = { Text("Smazat koně?") },
            text = { Text("Opravdu smazat ${del.name}? Smažou se i všechny jeho jízdy.") },
            confirmButton = {
                SmallButton(
                    onClick = {
                        onDelete(del)
                        deleteHorse = null
                    },
                    height = 36.dp,
                ) { Text("Smazat") }
            },
            dismissButton = {
                SmallButton(onClick = { deleteHorse = null }, height = 36.dp) { Text("Zrušit") }
            },
        )
    }
}

@Composable
private fun SmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 32.dp,
    tone: ButtonTone = ButtonTone.Accent,
    content: @Composable () -> Unit,
) {
    val colors =
        when (tone) {
            ButtonTone.Primary ->
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2C7A69),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFBDD4CE),
                    disabledContentColor = Color.White,
                )
            ButtonTone.Accent ->
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6755C7),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFD6D0F0),
                    disabledContentColor = Color.White,
                )
            ButtonTone.Danger ->
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC75B55),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFE9C7C4),
                    disabledContentColor = Color.White,
                )
            ButtonTone.Neutral ->
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE8EDF4),
                    contentColor = Color(0xFF2A3945),
                    disabledContainerColor = Color(0xFFF0F2F5),
                    disabledContentColor = Color(0xFF93A0AC),
                )
            ButtonTone.Selected ->
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF325D9C),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFC4D3E8),
                    disabledContentColor = Color.White,
                )
        }
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) { content() }
}

private enum class ButtonTone {
    Primary,
    Accent,
    Danger,
    Neutral,
    Selected,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactRidePanel(
    isRecording: Boolean,
    isFollowing: Boolean,
    isReversed: Boolean,
    durationText: String,
    distanceKm: Double,
    speedKmh: Double,
    avgSpeedKmh: Double,
    autoCenter: Boolean,
    offRouteWarnThresholdM: Int,
    backOnRouteThresholdM: Int,
    hasLocation: Boolean,
    onToggleAutoCenter: () -> Unit,
    onShowOfflineDialog: () -> Unit,
    onStopAll: () -> Unit,
    onDecreaseOffRoute: () -> Unit,
    onIncreaseOffRoute: () -> Unit,
    onDecreaseBackOnRoute: () -> Unit,
    onIncreaseBackOnRoute: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF6F7FB), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isRecording) {
                StatusChip(label = "Záznam běží", background = Color(0xFFE1F3E8), foreground = Color(0xFF1E6A47))
            }
            if (isFollowing) {
                StatusChip(label = "Follow aktivní", background = Color(0xFFFFE6E2), foreground = Color(0xFFB25145))
            }
            if (isFollowing && isReversed) {
                StatusChip(label = "Reverse", background = Color(0xFFE6EBFF), foreground = Color(0xFF4158B4))
            }
            if (!isRecording && !isFollowing) {
                StatusChip(label = "Připraveno", background = Color(0xFFEAF0F6), foreground = Color(0xFF516271))
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatChip(title = "Čas", value = durationText, background = Color.White)
            StatChip(title = "Vzdál.", value = "${"%.2f".format(distanceKm)} km", background = Color(0xFFEDF6FF))
            StatChip(title = "Rychl.", value = "${"%.1f".format(speedKmh)} km/h", background = Color(0xFFE7F7EF))
            StatChip(title = "Prům.", value = "${"%.1f".format(avgSpeedKmh)} km/h", background = Color(0xFFFFF2DE))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallButton(
                onClick = onToggleAutoCenter,
                height = 28.dp,
                tone = if (autoCenter) ButtonTone.Selected else ButtonTone.Neutral,
            ) {
                Text(if (autoCenter) "Auto-centr ON" else "Auto-centr OFF")
            }
            SmallButton(
                onClick = onShowOfflineDialog,
                enabled = hasLocation,
                height = 28.dp,
                tone = ButtonTone.Neutral,
            ) { Text("Offline okolí") }
            SmallButton(onClick = onStopAll, height = 28.dp, tone = ButtonTone.Danger) { Text("Konec") }
            ThresholdAdjuster(
                label = "Mimo",
                value = "${offRouteWarnThresholdM} m",
                onDecrease = onDecreaseOffRoute,
                onIncrease = onIncreaseOffRoute,
            )
            ThresholdAdjuster(
                label = "Zpět",
                value = "${backOnRouteThresholdM} m",
                onDecrease = onDecreaseBackOnRoute,
                onIncrease = onIncreaseBackOnRoute,
            )
        }
    }
}

@Composable
private fun StatChip(title: String, value: String, background: Color) {
    Column(
        modifier =
            Modifier
                .background(background, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(title, fontSize = 10.sp, color = Color(0xFF65727D))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D2A34))
    }
}

@Composable
private fun StatusChip(label: String, background: Color, foreground: Color) {
    Box(
        modifier =
            Modifier
                .background(background, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = foreground)
    }
}

@Composable
private fun ThresholdAdjuster(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 10.sp, color = Color(0xFF65727D))
        SmallButton(onClick = onDecrease, height = 24.dp, tone = ButtonTone.Neutral) { Text("-") }
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1D2A34))
        SmallButton(onClick = onIncrease, height = 24.dp, tone = ButtonTone.Neutral) { Text("+") }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HorseItem(text: String, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(color = Color(0xFFDCE8F4), shape = RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text, color = Color(0xFF1C2A36), fontSize = 13.sp)
    }
}

@Composable
private fun RideListScreen(
    rides: List<RideSummary>,
    horses: List<Horse>,
    filterHorseId: String?,
    onBack: () -> Unit,
    onLoad: (RideSummary) -> Unit,
    onDelete: (RideSummary) -> Unit,
    onExport: (RideSummary) -> Unit,
    onEmail: (RideSummary) -> Unit,
    onSelectHorseFilter: (String?) -> Unit,
) {
    var toDelete by remember { mutableStateOf<RideSummary?>(null) }
    var emailRide by remember { mutableStateOf<RideSummary?>(null) }
    var showFilterDialog by remember { mutableStateOf(false) }
    val filterHorseName = horses.firstOrNull { it.id == filterHorseId }?.name
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (filterHorseId == null) "Jízdy (všichni koně)" else "Jízdy (${filterHorseName ?: "kůň"})",
                modifier = Modifier.weight(1f),
            )
            SmallButton(onClick = { showFilterDialog = true }, height = 32.dp) {
                Text(if (filterHorseId == null) "Všichni" else "Jen: ${filterHorseName ?: "kůň"}")
            }
            SmallButton(onClick = onBack, height = 32.dp) { Text("Zpět") }
        }
        if (rides.isEmpty()) {
            Text("Žádné uložené jízdy.")
        } else {
            rides.take(50).forEach { r ->
                val horseName = horses.firstOrNull { it.id == r.horseId }?.name ?: r.horseId
                val line =
                    (if (filterHorseId == null) "$horseName • " else "") +
                        "${formatDateTime(r.endTimeMs)} • ${"%.2f".format(r.distanceM / 1000.0)} km"
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(color = Color(0xFFDCE8F4), shape = RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line, color = Color(0xFF1C2A36), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton(onClick = { onLoad(r) }, height = 32.dp) { Text("Nahrát") }
                            SmallButton(onClick = { onExport(r) }, height = 32.dp) { Text("Export") }
                            SmallButton(onClick = { emailRide = r }, height = 32.dp) { Text("E-mail") }
                            SmallButton(onClick = { toDelete = r }, height = 32.dp) { Text("Smazat") }
                        }
                    }
                }
            }
        }
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Smazat jízdu?") },
            text = { Text("Opravdu smazat ${del.gpxFileName}?") },
            confirmButton = {
                SmallButton(
                    onClick = {
                        onDelete(del)
                        toDelete = null
                    },
                    height = 36.dp,
                ) { Text("Smazat") }
            },
            dismissButton = {
                SmallButton(onClick = { toDelete = null }, height = 36.dp) { Text("Zrušit") }
            },
        )
    }

    val rideForEmail = emailRide
    if (rideForEmail != null) {
        AlertDialog(
            onDismissRequest = { emailRide = null },
            title = { Text("Odeslat GPX mailem?") },
            text = {
                Text(
                    "Poslat GPX této jízdy na rolfovo@gmail.com?",
                )
            },
            confirmButton = {
                SmallButton(
                    onClick = {
                        onEmail(rideForEmail)
                        emailRide = null
                    },
                    height = 36.dp,
                ) { Text("Odeslat") }
            },
            dismissButton = {
                SmallButton(onClick = { emailRide = null }, height = 36.dp) { Text("Zrušit") }
            },
        )
    }

    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Filtr koně") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(
                        onClick = {
                            onSelectHorseFilter(null)
                            showFilterDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Všichni") }
                    horses.forEach { horse ->
                        SmallButton(
                            onClick = {
                                onSelectHorseFilter(horse.id)
                                showFilterDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(horse.name) }
                    }
                }
            },
            confirmButton = {
                SmallButton(onClick = { showFilterDialog = false }) { Text("Zavřít") }
            },
        )
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return "%d:%02d:%02d".format(h, m, sec)
}

private fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMs))
}
