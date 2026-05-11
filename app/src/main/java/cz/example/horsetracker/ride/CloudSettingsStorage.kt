package cz.example.horsetracker.ride

import android.content.Context

object CloudSettingsStorage {
    private const val PREFS = "horse_tracker_cloud_prefs"
    private const val KEY_ENDPOINT_URL = "endpoint_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"

    data class CloudSettings(
        val endpointUrl: String = "",
        val token: String = "",
        val enabled: Boolean = false,
    ) {
        val isConfigured: Boolean
            get() = enabled && endpointUrl.isNotBlank()
    }

    fun load(context: Context): CloudSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CloudSettings(
            endpointUrl = prefs.getString(KEY_ENDPOINT_URL, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            enabled = prefs.getBoolean(KEY_ENABLED, false),
        )
    }

    fun save(context: Context, settings: CloudSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT_URL, settings.endpointUrl.trim())
            .putString(KEY_TOKEN, settings.token.trim())
            .putBoolean(KEY_ENABLED, settings.enabled)
            .apply()
    }
}
