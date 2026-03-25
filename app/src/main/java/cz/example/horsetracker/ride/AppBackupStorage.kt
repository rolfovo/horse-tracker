package cz.example.horsetracker.ride

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
                ?: error("Nelze otevřít cílový soubor.")

        output.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                writeMetadata(zip, metadata)
                addFileIfExists(zip, File(context.filesDir, "horses/horses.json"), "horses/horses.json")
                addDirectoryFiles(zip, File(context.filesDir, "rides"), "rides")
            }
        }
    }

    fun import(context: Context, sourceUri: Uri) {
        val tempRoot = File(context.cacheDir, "backup_import_tmp").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            var metadata: BackupMetadata? = null
            val input =
                context.contentResolver.openInputStream(sourceUri)
                    ?: error("Nelze otevřít záložní soubor.")

            input.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        val safePath = sanitizeZipPath(entry.name) ?: error("Neplatná položka v záloze: ${entry.name}")
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
                            error("Neplatná cesta v záloze: $safePath")
                        }
                        outCanonical.parentFile?.mkdirs()
                        outCanonical.outputStream().use { output -> zip.copyTo(output) }
                        zip.closeEntry()
                    }
                }
            }

            val meta = metadata ?: error("Záloha neobsahuje metadata.")
            replaceDirectory(File(context.filesDir, "horses"), File(tempRoot, "horses"))
            replaceDirectory(File(context.filesDir, "rides"), File(tempRoot, "rides"))
            SelectionStorage.setSelectedHorseId(context, meta.selectedHorseId)
            FollowSettingsStorage.save(context, meta.warnThresholdM, meta.backOnRouteThresholdM)
        } finally {
            tempRoot.deleteRecursively()
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

    private fun replaceDirectory(destination: File, source: File) {
        destination.deleteRecursively()
        if (!source.exists()) return
        source.copyRecursively(destination, overwrite = true)
    }
}
