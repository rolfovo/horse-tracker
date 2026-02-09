package cz.example.horsetracker.ride

import android.content.Context

object SelectionStorage {
    private const val PREFS = "horse_tracker_prefs"
    private const val KEY_SELECTED_HORSE_ID = "selected_horse_id"

    fun getSelectedHorseId(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_SELECTED_HORSE_ID, null)
    }

    fun setSelectedHorseId(context: Context, horseId: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString(KEY_SELECTED_HORSE_ID, horseId).apply()
    }
}

