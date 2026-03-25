package cz.example.horsetracker.ride

import android.content.Context

object FollowSettingsStorage {
    private const val PREFS = "horse_tracker_follow_prefs"
    private const val KEY_WARN_M = "warn_threshold_m"
    private const val KEY_BACK_M = "back_threshold_m"

    fun load(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val warn = prefs.getFloat(KEY_WARN_M, 30f).toDouble().coerceIn(10.0, 200.0)
        val back = prefs.getFloat(KEY_BACK_M, 5f).toDouble().coerceIn(1.0, warn - 1.0)
        return warn to back
    }

    fun save(context: Context, warnM: Double, backM: Double) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_WARN_M, warnM.toFloat())
            .putFloat(KEY_BACK_M, backM.toFloat())
            .apply()
    }
}

