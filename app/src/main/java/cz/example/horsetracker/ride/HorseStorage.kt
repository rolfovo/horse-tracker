package cz.example.horsetracker.ride

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object HorseStorage {
    private const val DIR = "horses"
    private const val FILE = "horses.json"

    fun listHorses(context: Context): List<Horse> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("horses") ?: return emptyList()
            val out = ArrayList<Horse>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                val id = h.optString("id", "")
                val name = h.optString("name", "")
                if (id.isNotBlank() && name.isNotBlank()) out.add(Horse(id = id, name = name))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addHorse(context: Context, name: String): Horse {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Horse name is empty" }

        val horses = listHorses(context).toMutableList()
        val existing = horses.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing

        val horse = Horse(id = UUID.randomUUID().toString(), name = trimmed)
        horses.add(horse)
        write(context, horses)
        return horse
    }

    fun deleteHorse(context: Context, horseId: String): Boolean {
        val horses = listHorses(context)
        val filtered = horses.filterNot { it.id == horseId }
        if (filtered.size == horses.size) return false
        write(context, filtered)
        return true
    }

    private fun write(context: Context, horses: List<Horse>) {
        val root = JSONObject()
        val arr = JSONArray()
        horses.forEach { h -> arr.put(JSONObject().put("id", h.id).put("name", h.name)) }
        root.put("horses", arr)
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, FILE).writeText(root.toString(2))
    }

    private fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)
}
