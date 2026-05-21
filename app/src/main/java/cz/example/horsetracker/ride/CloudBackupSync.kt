package cz.example.horsetracker.ride

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CloudBackupSync {
    fun upload(context: Context, settings: CloudSettingsStorage.CloudSettings) {
        require(settings.endpointUrl.isNotBlank()) { "Cloud URL is empty" }

        val summary = AppBackupStorage.summary(context)
        check(!summary.isEmpty) { "Cloud upload blocked: local backup has no horses or rides." }

        val temp = File(context.cacheDir, "horse_tracker_cloud_backup.zip")
        var connection: HttpURLConnection? = null
        try {
            temp.outputStream().use { output -> AppBackupStorage.export(context, output) }
            val activeConnection = openConnection(settings, method = "PUT", followRedirects = false).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("X-Horse-Tracker-Horses", summary.horsesCount.toString())
                setRequestProperty("X-Horse-Tracker-Rides", summary.ridesCount.toString())
                setFixedLengthStreamingMode(temp.length())
            }
            connection = activeConnection

            temp.inputStream().use { input ->
                activeConnection.outputStream.use { output -> input.copyTo(output) }
            }
            activeConnection.requireSuccess()
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    fun restore(context: Context, settings: CloudSettingsStorage.CloudSettings) {
        require(settings.endpointUrl.isNotBlank()) { "Cloud URL is empty" }

        val connection =
            openConnection(settings, method = "GET", followRedirects = true).apply {
                setRequestProperty("Accept", "application/zip")
            }
        try {
            connection.requireSuccess()
            connection.inputStream.use { input -> AppBackupStorage.import(context, input) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        settings: CloudSettingsStorage.CloudSettings,
        method: String,
        followRedirects: Boolean,
    ): HttpURLConnection {
        val connection = (URL(settings.endpointUrl).openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = followRedirects
        if (settings.token.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${settings.token}")
        }
        return connection
    }

    private fun HttpURLConnection.requireSuccess() {
        val code = responseCode
        if (code in 200..299) {
            val type = contentType.orEmpty()
            if (type.contains("text/html", ignoreCase = true)) {
                error("HTTP $code returned HTML instead of the backup API response. Use the final Cloud API URL, usually with trailing slash or /index.php.")
            }
            return
        }

        if (code in 300..399) {
            val location = getHeaderField("Location").orEmpty()
            error("HTTP $code redirect to $location. Use the final Cloud API URL, usually with trailing slash or /index.php.")
        }

        val body =
            runCatching {
                (errorStream ?: inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
        error("HTTP $code ${responseMessage.orEmpty()} ${body.take(200)}".trim())
    }
}
