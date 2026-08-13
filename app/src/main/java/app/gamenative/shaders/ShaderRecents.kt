package app.gamenative.shaders

import android.content.Context

/** Last-applied presets (max [MAX]), persisted in a private SharedPreferences file. */
class ShaderRecents(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("shader_recents", Context.MODE_PRIVATE)

    fun list(): List<String> =
        prefs.getString(KEY, "")?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()

    fun add(path: String) {
        if (path.isBlank()) return
        val updated = (listOf(path) + list().filter { it != path }).take(MAX)
        prefs.edit().putString(KEY, updated.joinToString("|")).apply()
    }

    private companion object {
        const val KEY = "keys"
        const val MAX = 5
    }
}
