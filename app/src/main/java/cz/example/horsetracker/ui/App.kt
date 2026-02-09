package cz.example.horsetracker.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun App(onRequestLocationPermission: () -> Unit) {
    val context = LocalContext.current
    val state by RideRepository.state.collectAsState()
    val hasLocation by PermissionRepository.hasLocation.collectAsState()

    val selectedHorse =
        state.selectedHorseId?.let { id -> state.horses.firstOrNull { it.id == id } }

    var showHorsePicker by remember { mutableStateOf(false) }
    var showRides by remember { mutableStateOf(false) }
    var showAllRides by remember { mutableStateOf(false) }
    var showWaypointDialog by remember { mutableStateOf(false) }
    var waypointLabel by remember { mutableStateOf("") }

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
        )
        return
    }

    if (showRides) {
        RideListScreen(
            rides = state.rides,
            horses = state.horses,
            selectedHorse = selectedHorse,
            showAll = showAllRides,
            onBack = { showRides = false },
            onLoad = {
                RideRepository.loadRide(context, it.metaFileName)
                showRides = false
            },
            onDelete = { RideRepository.deleteRide(context, it.metaFileName) },
            onToggleAll = {
                showAllRides = !showAllRides
                RideRepository.refreshRides(context, horseId = if (showAllRides) null else selectedHorse.id)
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
        )

        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }

            val stats = state.horseStats[selectedHorse.id]
            if (stats != null) {
                Text(
                    "Jízdy: ${stats.ridesCount} | Čas: ${formatDuration(stats.totalDurationMs)} | " +
                        "Vzdál.: ${"%.1f".format(stats.totalDistanceM / 1000.0)} km | " +
                        "Avg: ${"%.1f".format(stats.avgSpeedMps * 3.6)} km/h | Max: ${"%.1f".format(stats.maxSpeedMps * 3.6)} km/h",
                    fontSize = 13.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(
                    onClick = {
                        val intent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_START_RECORDING
                        }
                        context.startForegroundService(intent)
                    },
                    enabled = hasLocation && !state.isRecording,
                ) { Text("Start") }

                SmallButton(
                    onClick = {
                        val intent = Intent(context, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP_RECORDING
                        }
                        context.startService(intent)
                    },
                    enabled = state.isRecording,
                ) { Text("Stop") }

                SmallButton(
                    onClick = {
                        waypointLabel = ""
                        showWaypointDialog = true
                    },
                    enabled = state.isRecording,
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
                ) { Text("Hlas") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(
                    onClick = { RideRepository.saveCurrentRide(context) },
                    enabled = state.points.isNotEmpty(),
                ) { Text("Uložit") }

                SmallButton(
                    onClick = {
                        showAllRides = false
                        RideRepository.refreshRides(context, horseId = selectedHorse.id)
                        showRides = true
                    },
                ) { Text("Jízdy") }

                SmallButton(
                    onClick = {
                        val intent = Intent(context, TrackingService::class.java).apply {
                            action =
                                if (state.isFollowing) {
                                    TrackingService.ACTION_STOP_FOLLOW
                                } else {
                                    TrackingService.ACTION_START_FOLLOW
                                }
                        }
                        context.startForegroundService(intent)
                    },
                    enabled = hasLocation && state.routeToFollow.isNotEmpty(),
                ) { Text(if (state.isFollowing) "Stop follow" else "Follow") }

                SmallButton(
                    onClick = { RideRepository.toggleReverse() },
                    enabled = state.routeToFollow.isNotEmpty(),
                ) { Text(if (state.isReversed) "Normal" else "Reverse") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Vzdál.: ${"%.2f".format(state.currentDistanceM / 1000.0)} km", fontSize = 13.sp)
                Text("Rychl.: ${"%.1f".format(state.lastSpeedMps * 3.6)} km/h", fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Prům.: ${"%.1f".format(state.currentAvgSpeedMps * 3.6)} km/h", fontSize = 13.sp)
                Text("Odchylka: ${"%.0f".format(state.offRouteMeters)} m", fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GPS acc: ${"%.0f".format(state.lastAccuracyM)} m", fontSize = 13.sp)
                SmallButton(onClick = { RideRepository.toggleAutoCenter() }, height = 30.dp) {
                    Text(if (state.isAutoCenter) "Auto-centr: ON" else "Auto-centr: OFF")
                }
            }
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
    onSelect: (Horse) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (Horse) -> Unit,
    onClose: (() -> Unit)?,
) {
    var newHorse by remember { mutableStateOf("") }
    var statsHorse by remember { mutableStateOf<Horse?>(null) }
    var deleteHorse by remember { mutableStateOf<Horse?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vyber koně", modifier = Modifier.weight(1f))
            if (onClose != null) SmallButton(onClick = onClose, height = 32.dp) { Text("Zpět") }
        }
        if (horses.isEmpty()) {
            Text("Zatím nemáš žádného koně. Přidej prvního:")
        } else {
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
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) { content() }
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
    selectedHorse: Horse,
    showAll: Boolean,
    onBack: () -> Unit,
    onLoad: (RideSummary) -> Unit,
    onDelete: (RideSummary) -> Unit,
    onToggleAll: () -> Unit,
) {
    var toDelete by remember { mutableStateOf<RideSummary?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (showAll) "Jízdy (všichni koně)" else "Jízdy (${selectedHorse.name})",
                modifier = Modifier.weight(1f),
            )
            SmallButton(onClick = onToggleAll, height = 32.dp) { Text(if (showAll) "Jen tohoto" else "Všichni") }
            SmallButton(onClick = onBack, height = 32.dp) { Text("Zpět") }
        }
        if (rides.isEmpty()) {
            Text("Žádné uložené jízdy.")
        } else {
            rides.take(50).forEach { r ->
                val horseName = horses.firstOrNull { it.id == r.horseId }?.name ?: r.horseId
                val line =
                    (if (showAll) "$horseName • " else "") +
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
