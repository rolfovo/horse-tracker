package cz.example.horsetracker.ride

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter

object GpxStorage {
    private val timeFmt = DateTimeFormatter.ISO_INSTANT

    data class Ride(val points: List<TrackPoint>, val waypoints: List<Waypoint>)

    fun writeGpx(file: File, points: List<TrackPoint>, waypoints: List<Waypoint>) {
        val w = StringWriter()
        w.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        w.append(
            """<gpx version="1.1" creator="HorseTracker" xmlns="http://www.topografix.com/GPX/1/1">""",
        ).append('\n')

        waypoints.forEach { wp ->
            w.append("""  <wpt lat="${wp.lat}" lon="${wp.lon}">""").append('\n')
            w.append("    <time>").append(formatTime(wp.timeEpochMs)).append("</time>\n")
            wp.label?.let { label ->
                w.append("    <name>").append(escapeXml(label)).append("</name>\n")
            }
            w.append("  </wpt>\n")
        }

        w.append("  <trk>\n")
        w.append("    <name>Ride</name>\n")
        w.append("    <trkseg>\n")
        points.forEach { p ->
            w.append("""      <trkpt lat="${p.lat}" lon="${p.lon}">""").append('\n')
            if (p.timeEpochMs > 0) w.append("        <time>").append(formatTime(p.timeEpochMs)).append("</time>\n")
            w.append("        <extensions>\n")
            w.append("          <speed_mps>").append(p.speedMps.toString()).append("</speed_mps>\n")
            w.append("          <accuracy_m>").append(p.accuracyM.toString()).append("</accuracy_m>\n")
            w.append("        </extensions>\n")
            w.append("      </trkpt>\n")
        }
        w.append("    </trkseg>\n")
        w.append("  </trk>\n")
        w.append("</gpx>\n")

        file.writeText(w.toString())
    }

    fun readGpx(file: File): Ride {
        val points = ArrayList<TrackPoint>()
        val waypoints = ArrayList<Waypoint>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(file.reader())

        var event = parser.eventType
        var inWpt = false
        var wptLat = 0.0
        var wptLon = 0.0
        var wptTime = 0L
        var wptName: String? = null

        var inTrkpt = false
        var trkLat = 0.0
        var trkLon = 0.0
        var trkTime = 0L
        var speed = 0.0
        var acc = 0.0

        var currentText: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentText = null
                    when (parser.name) {
                        "wpt" -> {
                            inWpt = true
                            wptLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            wptLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            wptTime = 0L
                            wptName = null
                        }

                        "trkpt" -> {
                            inTrkpt = true
                            trkLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            trkLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            trkTime = 0L
                            speed = 0.0
                            acc = 0.0
                        }
                    }
                }

                XmlPullParser.TEXT -> currentText = parser.text

                XmlPullParser.END_TAG -> {
                    val text = currentText?.trim()
                    when (parser.name) {
                        "time" -> {
                            if (text != null) {
                                if (inWpt) wptTime = parseTime(text)
                                if (inTrkpt) trkTime = parseTime(text)
                            }
                        }

                        "name" -> if (inWpt) wptName = text
                        "speed_mps" -> if (inTrkpt) speed = text?.toDoubleOrNull() ?: 0.0
                        "accuracy_m" -> if (inTrkpt) acc = text?.toDoubleOrNull() ?: 0.0

                        "wpt" -> {
                            inWpt = false
                            waypoints.add(Waypoint(lat = wptLat, lon = wptLon, timeEpochMs = wptTime, label = wptName))
                        }

                        "trkpt" -> {
                            inTrkpt = false
                            points.add(
                                TrackPoint(
                                    lat = trkLat,
                                    lon = trkLon,
                                    timeEpochMs = trkTime,
                                    speedMps = speed,
                                    accuracyM = acc,
                                ),
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Ride(points = points, waypoints = waypoints)
    }

    private fun formatTime(epochMs: Long): String =
        timeFmt.format(Instant.ofEpochMilli(epochMs))

    private fun parseTime(s: String): Long =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
