package cz.example.horsetracker.map

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object OfflineTilePrefetcher {
    data class Result(val downloaded: Int, val skipped: Int, val failed: Int)

    fun prefetchAround(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double = 4.0,
        minZoom: Int = 13,
        maxZoom: Int = 17,
        maxTiles: Int = 3000,
    ): Result {
        val tileRoot = File(context.filesDir, "offline_tiles").apply { mkdirs() }
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * kotlin.math.cos(centerLat * PI / 180.0)).coerceAtLeast(0.2)

        val minLat = centerLat - latDelta
        val maxLat = centerLat + latDelta
        val minLon = centerLon - lonDelta
        val maxLon = centerLon + lonDelta

        var downloaded = 0
        var skipped = 0
        var failed = 0
        var total = 0

        for (z in minZoom..maxZoom) {
            val xMin = lonToTileX(minLon, z)
            val xMax = lonToTileX(maxLon, z)
            val yMin = latToTileY(maxLat, z)
            val yMax = latToTileY(minLat, z)

            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    if (total >= maxTiles) return Result(downloaded, skipped, failed)
                    total++
                    val outFile = File(File(File(tileRoot, z.toString()), x.toString()).apply { mkdirs() }, "$y.png")
                    if (outFile.exists() && outFile.length() > 0) {
                        skipped++
                        continue
                    }

                    val ok = downloadTile(z, x, y, outFile)
                    if (ok) downloaded++ else failed++
                }
            }
        }
        return Result(downloaded, skipped, failed)
    }

    private fun downloadTile(z: Int, x: Int, y: Int, outFile: File): Boolean {
        val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "HorseTracker/0.1")
        }
        return try {
            conn.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Throwable) {
            outFile.delete()
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        return min(n - 1, max(0, x))
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = lat * PI / 180.0
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return min(n - 1, max(0, y))
    }
}
