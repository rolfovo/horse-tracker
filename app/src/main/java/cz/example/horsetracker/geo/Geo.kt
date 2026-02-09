package cz.example.horsetracker.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6371000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    fun distanceToPolylineMeters(userLat: Double, userLon: Double, route: List<Pair<Double, Double>>): Double {
        if (route.size < 2) return 0.0
        var best = Double.POSITIVE_INFINITY
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            best = min(best, distancePointToSegmentMeters(userLat, userLon, a.first, a.second, b.first, b.second))
        }
        return if (best.isFinite()) best else 0.0
    }

    fun nearestPointOnPolyline(
        userLat: Double,
        userLon: Double,
        route: List<Pair<Double, Double>>,
    ): Pair<Double, Double>? {
        if (route.size < 2) return null
        var bestDist = Double.POSITIVE_INFINITY
        var bestPoint: Pair<Double, Double>? = null

        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val proj = projectPointToSegment(userLat, userLon, a.first, a.second, b.first, b.second)
            if (proj.distanceM < bestDist) {
                bestDist = proj.distanceM
                bestPoint = proj.lat to proj.lon
            }
        }
        return bestPoint
    }

    private fun distancePointToSegmentMeters(
        pLat: Double,
        pLon: Double,
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double,
    ): Double {
        // Equirectangular projection kolem bodu P (OK pro krátké vzdálenosti).
        val lat0 = pLat * PI / 180.0
        fun toXY(lat: Double, lon: Double): Pair<Double, Double> {
            val x = (lon - pLon) * PI / 180.0 * cos(lat0) * R
            val y = (lat - pLat) * PI / 180.0 * R
            return x to y
        }

        val (ax, ay) = toXY(aLat, aLon)
        val (bx, by) = toXY(bLat, bLon)

        val vx = bx - ax
        val vy = by - ay
        val wx = -ax
        val wy = -ay
        val vv = vx * vx + vy * vy
        if (vv < 1e-6) return sqrt(ax * ax + ay * ay)

        val t = max(0.0, min(1.0, (wx * vx + wy * vy) / vv))
        val cx = ax + t * vx
        val cy = ay + t * vy
        return sqrt(cx * cx + cy * cy)
    }

    private data class ProjectionResult(val lat: Double, val lon: Double, val distanceM: Double)

    private fun projectPointToSegment(
        pLat: Double,
        pLon: Double,
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double,
    ): ProjectionResult {
        val lat0 = pLat * PI / 180.0
        fun toXY(lat: Double, lon: Double): Pair<Double, Double> {
            val x = (lon - pLon) * PI / 180.0 * cos(lat0) * R
            val y = (lat - pLat) * PI / 180.0 * R
            return x to y
        }

        val (ax, ay) = toXY(aLat, aLon)
        val (bx, by) = toXY(bLat, bLon)
        val vx = bx - ax
        val vy = by - ay
        val wx = -ax
        val wy = -ay
        val vv = vx * vx + vy * vy
        if (vv < 1e-6) {
            val d = sqrt(ax * ax + ay * ay)
            return ProjectionResult(aLat, aLon, d)
        }

        val t = max(0.0, min(1.0, (wx * vx + wy * vy) / vv))
        val cx = ax + t * vx
        val cy = ay + t * vy
        val d = sqrt(cx * cx + cy * cy)
        val lat = aLat + (bLat - aLat) * t
        val lon = aLon + (bLon - aLon) * t
        return ProjectionResult(lat, lon, d)
    }
}
