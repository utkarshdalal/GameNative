package app.gamenative.online

import android.content.Context

/**
 * Remembers the user's Online/Offline install choice per game before/after container creation.
 */
object InstallProfileStore {
    private const val PREFS = "gamenative_online_install_profiles"

    fun save(context: Context, containerId: String, profile: InstallProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(containerId, profile.storageKey)
            .apply()
    }

    fun get(context: Context, containerId: String): InstallProfile? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(containerId, null)
        return InstallProfile.fromStorageKey(raw)
    }

    fun clear(context: Context, containerId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(containerId)
            .apply()
    }
}
