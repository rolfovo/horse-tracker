package cz.example.horsetracker.ride

import android.content.Context

object FollowSettingsStorage {
    private const val FIXED_WARN_M = 20.0
    private const val FIXED_BACK_M = 5.0
    private const val PREFS = "horse_tracker_follow_prefs"
    private const val KEY_WARN_M = "warn_threshold_m"
    private const val KEY_BACK_M = "back_threshold_m"

    fun load(@Suppress("UNUSED_PARAMETER") context: Context): Pair<Double, Double> {
        return FIXED_WARN_M to FIXED_BACK_M
    }

    fun save(
        context: Context,
        @Suppress("UNUSED_PARAMETER") warnM: Double,
        @Suppress("UNUSED_PARAMETER") backM: Double,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_WARN_M, FIXED_WARN_M.toFloat())
            .putFloat(KEY_BACK_M, FIXED_BACK_M.toFloat())
            .apply()
    }
}
