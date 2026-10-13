package app.gamenative.inputcontrols

import android.content.Context

/** Remembers the user's on-screen control layout choice per game. */
object ControlLayoutStore {
    private const val PREFS = "gamenative_control_layout_choices"

    fun save(context: Context, containerId: String, choice: ControlLayoutChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(containerId, choice.storageKey)
            .apply()
    }

    fun get(context: Context, containerId: String): ControlLayoutChoice? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(containerId, null)
        return ControlLayoutChoice.fromStorageKey(raw)
    }

    fun clear(context: Context, containerId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(containerId)
            .apply()
    }
}
