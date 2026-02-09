package cz.example.horsetracker.ride

import android.content.Context
import org.json.JSONObject
import java.io.File

object RideMetaStorage {
    private const val DIR = "rides"

    data class RideMeta(
        val horseId: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val distanceM: Double,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        val pointsCount: Int,
        val gpxFileName: String,
        val metaFileName: String,
    )

    fun writeMeta(context: Context, meta: RideMeta, metaFileName: String) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, metaFileName)
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

    fun listMetas(context: Context): List<RideMeta> {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension.lowercase() == "json" && it.name.endsWith(".meta.json") }
            ?.mapNotNull { readMeta(it) }
            ?: emptyList()
    }

    private fun readMeta(file: File): RideMeta? {
        return try {
            val json = JSONObject(file.readText())
            RideMeta(
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
    }
}
