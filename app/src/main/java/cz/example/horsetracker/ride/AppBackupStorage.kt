package cz.example.horsetracker.ride

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

object AppBackupStorage {
    private const val BACKUP_VERSION = 1
    private const val METADATA_ENTRY = "backup.json"

    data class BackupMetadata(
        val selectedHorseId: String?,
        val warnThresholdM: Double,
        val backOnRouteThresholdM: Double,
    )

    fun export(context: Context, destinationUri: Uri) {
        val (warnM, backM) = FollowSettingsStorage.load(context)
        val metadata =
            BackupMetadata(
                selectedHorseId = SelectionStorage.getSelectedHorseId(context),
                warnThresholdM = warnM,
                backOnRouteThresholdM = backM,
            )

        val output =
            context.contentResolver.openOutputStream(destinationUri)
                ?: error("Cannot open destination file.")

        output.use { raw -> export(context, raw, metadata) }
    }

    fun import(context: Context, sourceUri: Uri) {
        val input =
            context.contentResolver.openInputStream(sourceUri)
                ?: error("Cannot open backup file.")
        input.use { raw -> import(context, raw) }
    }

    fun export(context: Context, output: OutputStream) {
        val (warnM, backM) = FollowSettingsStorage.load(context)
        val metadata =
            BackupMetadata(
                selectedHorseId = SelectionStorage.getSelectedHorseId(context),
                warnThresholdM = warnM,
                backOnRouteThresholdM = backM,
            )
        export(context, output, metadata)
    }

    fun import(context: Context, input: InputStream) {
        val tempRoot = File(context.cacheDir, "backup_import_tmp").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            var metadata: BackupMetadata? = null

            input.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        val safePath = sanitizeZipPath(entry.name) ?: error("Invalid backup entry: ${entry.name}")
                        if (safePath == METADATA_ENTRY) {
                            metadata = parseMetadata(zip.readBytes().toString(Charsets.UTF_8))
                            zip.closeEntry()
                            continue
                        }

                        if (!safePath.startsWith("horses/") && !safePath.startsWith("rides/")) {
                            zip.closeEntry()
                            continue
                        }

                        val outFile = File(tempRoot, safePath)
                        val rootCanonical = tempRoot.canonicalFile
                        val outCanonical = outFile.canonicalFile
                        if (!outCanonical.path.startsWith(rootCanonical.path)) {
                            error("Invalid backup path: $safePath")
                        }
                        outCanonical.parentFile?.mkdirs()
                        outCanonical.outputStream().use { output -> zip.copyTo(output) }
                        zip.closeEntry()
                    }
                }
            }

            val meta = metadata ?: error("Backup metadata missing.")
            val horseIdMap = mergeHorses(context, File(tempRoot, "horses/horses.json"))
            mergeRides(context, File(tempRoot, "rides"), horseIdMap)
            val selectedHorseId = meta.selectedHorseId?.let { horseIdMap[it] ?: it }
            if (selectedHorseId != null && HorseStorage.listHorses(context).any { it.id == selectedHorseId }) {
                SelectionStorage.setSelectedHorseId(context, selectedHorseId)
            }
            FollowSettingsStorage.save(context, meta.warnThresholdM, meta.backOnRouteThresholdM)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun export(context: Context, output: OutputStream, metadata: BackupMetadata) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            writeMetadata(zip, metadata)
            addFileIfExists(zip, File(context.filesDir, "horses/horses.json"), "horses/horses.json")
            addDirectoryFiles(zip, File(context.filesDir, "rides"), "rides")
        }
    }

    private fun writeMetadata(zip: ZipOutputStream, metadata: BackupMetadata) {
        val json =
            JSONObject()
                .put("version", BACKUP_VERSION)
                .put("selectedHorseId", metadata.selectedHorseId)
                .put("warnThresholdM", metadata.warnThresholdM)
                .put("backOnRouteThresholdM", metadata.backOnRouteThresholdM)
        zip.putNextEntry(ZipEntry(METADATA_ENTRY))
        zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFileIfExists(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists() || !file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addDirectoryFiles(zip: ZipOutputStream, dir: File, entryPrefix: String) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(dir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("$entryPrefix/$relative"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
    }

    private fun parseMetadata(jsonText: String): BackupMetadata {
        val json = JSONObject(jsonText)
        val warn = json.optDouble("warnThresholdM", 30.0).coerceIn(10.0, 200.0)
        val back = json.optDouble("backOnRouteThresholdM", 5.0).coerceIn(1.0, warn - 1.0)
        return BackupMetadata(
            selectedHorseId = json.optString("selectedHorseId").takeIf { it.isNotBlank() },
            warnThresholdM = warn,
            backOnRouteThresholdM = back,
        )
    }

    private fun sanitizeZipPath(path: String): String? {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        if (normalized.startsWith("../") || normalized.contains("/../") || normalized == "..") return null
        if (normalized.contains(":/")) return null
        return normalized
    }

    private fun mergeHorses(context: Context, importedFile: File): Map<String, String> {
        val imported = readHorses(importedFile)
        if (imported.isEmpty()) return emptyMap()

        val local = HorseStorage.listHorses(context).toMutableList()
        val byId = local.associateBy { it.id }.toMutableMap()
        val byName = local.associateBy { normalizeHorseName(it.name) }.toMutableMap()
        val idMap = LinkedHashMap<String, String>()
        var changed = false

        imported.forEach { horse ->
            val existingById = byId[horse.id]
            if (existingById != null) {
                idMap[horse.id] = existingById.id
                return@forEach
            }

            val existingByName = byName[normalizeHorseName(horse.name)]
            if (existingByName != null) {
                idMap[horse.id] = existingByName.id
                return@forEach
            }

            val mergedHorse = horse.copy(id = horse.id.takeIf { it !in byId } ?: UUID.randomUUID().toString())
            local.add(mergedHorse)
            byId[mergedHorse.id] = mergedHorse
            byName[normalizeHorseName(mergedHorse.name)] = mergedHorse
            idMap[horse.id] = mergedHorse.id
            changed = true
        }

        if (changed) writeHorses(context, local)
        return idMap
    }

    private fun mergeRides(context: Context, importedDir: File, horseIdMap: Map<String, String>) {
        if (!importedDir.exists() || !importedDir.isDirectory) return

        val destinationDir = File(context.filesDir, "rides").apply { mkdirs() }
        val knownMetas = RideMetaStorage.listMetas(context).toMutableList()

        importedDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".meta.json") }
            ?.forEach { metaFile ->
                val importedMeta = readRideMeta(metaFile) ?: return@forEach
                val mappedHorseId = horseIdMap[importedMeta.horseId] ?: importedMeta.horseId
                val mappedMeta = importedMeta.copy(horseId = mappedHorseId)

                if (knownMetas.any { isSameRide(it, mappedMeta) }) return@forEach

                val importedGpx = File(importedDir, importedMeta.gpxFileName)
                if (!importedGpx.exists() || !importedGpx.isFile) return@forEach

                val gpxName = uniqueFileName(destinationDir, importedMeta.gpxFileName)
                val metaName = uniqueFileName(destinationDir, importedMeta.metaFileName)
                importedGpx.copyTo(File(destinationDir, gpxName), overwrite = false)

                val savedMeta = mappedMeta.copy(gpxFileName = gpxName, metaFileName = metaName)
                writeRideMeta(File(destinationDir, metaName), savedMeta)
                knownMetas.add(savedMeta)
            }
    }

    private fun readHorses(file: File): List<Horse> {
        if (!file.exists() || !file.isFile) return emptyList()
        val json = JSONObject(file.readText())
        val arr = json.optJSONArray("horses") ?: return emptyList()
        val out = ArrayList<Horse>(arr.length())
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val id = h.optString("id", "")
            val name = h.optString("name", "")
            if (id.isNotBlank() && name.isNotBlank()) out.add(Horse(id = id, name = name))
        }
        return out
    }

    private fun writeHorses(context: Context, horses: List<Horse>) {
        val root = JSONObject()
        val arr = org.json.JSONArray()
        horses.forEach { horse ->
            arr.put(JSONObject().put("id", horse.id).put("name", horse.name))
        }
        root.put("horses", arr)
        val dir = File(context.filesDir, "horses").apply { mkdirs() }
        File(dir, "horses.json").writeText(root.toString(2))
    }

    private fun readRideMeta(file: File): RideMetaStorage.RideMeta? =
        try {
            val json = JSONObject(file.readText())
            RideMetaStorage.RideMeta(
                horseId = json.getString("horseId"),
                startTimeMs = json.getLong("startTimeMs"),
                endTimeMs = json.getLong("endTimeMs"),
                distanceM = json.getDouble("distanceM"),
                avgSpeedMps = json.getDouble("avgSpeedMps"),
                maxSpeedMps = json.getDouble("maxSpeedMps"),
                pointsCount = json.getInt("pointsCount"),
                gpxFileName = json.getString("gpxFileName"),
                metaFileName = file.name,
            )
        } catch (_: Throwable) {
            null
        }

    private fun writeRideMeta(file: File, meta: RideMetaStorage.RideMeta) {
        val json =
            JSONObject()
                .put("horseId", meta.horseId)
                .put("startTimeMs", meta.startTimeMs)
                .put("endTimeMs", meta.endTimeMs)
                .put("distanceM", meta.distanceM)
                .put("avgSpeedMps", meta.avgSpeedMps)
                .put("maxSpeedMps", meta.maxSpeedMps)
                .put("pointsCount", meta.pointsCount)
                .put("gpxFileName", meta.gpxFileName)
        file.writeText(json.toString(2))
    }

    private fun isSameRide(a: RideMetaStorage.RideMeta, b: RideMetaStorage.RideMeta): Boolean =
        a.horseId == b.horseId &&
            abs(a.startTimeMs - b.startTimeMs) <= 1000L &&
            abs(a.endTimeMs - b.endTimeMs) <= 1000L &&
            a.pointsCount == b.pointsCount

    private fun uniqueFileName(dir: File, preferredName: String): String {
        if (!File(dir, preferredName).exists()) return preferredName

        val suffix =
            when {
                preferredName.endsWith(".meta.json") -> ".meta.json"
                preferredName.contains('.') -> preferredName.substring(preferredName.lastIndexOf('.'))
                else -> ""
            }
        val base = if (suffix.isNotEmpty()) preferredName.removeSuffix(suffix) else preferredName
        var index = 2
        while (true) {
            val candidate = "$base sync$index$suffix"
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }

    private fun normalizeHorseName(name: String): String = name.trim().lowercase()
}
