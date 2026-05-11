package cz.example.horsetracker.ride

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CloudBackupSync {
    fun upload(context: Context, settings: CloudSettingsStorage.CloudSettings) {
        require(settings.endpointUrl.isNotBlank()) { "Cloud URL is empty" }

        val temp = File(context.cacheDir, "horse_tracker_cloud_backup.zip")
        try {
            temp.outputStream().use { output -> AppBackupStorage.export(context, output) }
            val connection = openConnection(settings, method = "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/zip")
                setFixedLengthStreamingMode(temp.length())
            }

            temp.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }
            connection.requireSuccess()
        } finally {
            temp.delete()
        }
    }

    fun restore(context: Context, settings: CloudSettingsStorage.CloudSettings) {
        require(settings.endpointUrl.isNotBlank()) { "Cloud URL is empty" }

        val connection = openConnection(settings, method = "GET").apply {
            setRequestProperty("Accept", "application/zip")
        }
        connection.requireSuccess()
        connection.inputStream.use { input -> AppBackupStorage.import(context, input) }
    }

    private fun openConnection(
        settings: CloudSettingsStorage.CloudSettings,
        method: String,
    ): HttpURLConnection {
        val connection = (URL(settings.endpointUrl).openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        if (settings.token.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${settings.token}")
        }
        return connection
    }

    private fun HttpURLConnection.requireSuccess() {
        val code = responseCode
        if (code in 200..299) return

        val body =
            runCatching {
                (errorStream ?: inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
        error("HTTP $code ${responseMessage.orEmpty()} ${body.take(200)}".trim())
    }
}
