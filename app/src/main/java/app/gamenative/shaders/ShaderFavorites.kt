package app.gamenative.shaders

import android.content.Context

/**
 * Persistence boundary for [ShaderFavorites] — JVM-testable (in-memory fake in the
 * unit test), mirroring the pattern of [ShaderRecents] with the storage abstracted.
 */
interface ShaderFavoritesStore {
    fun read(): List<String>
    fun write(paths: List<String>)
}

/**
 * SharedPreferences-backed [ShaderFavoritesStore]: private file `shader_favorites`,
 * pipe-joined keys — same layout as [ShaderRecents] (spec 2026-08-12, M3).
 */
class SharedPrefsShaderFavoritesStore(context: Context) : ShaderFavoritesStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("shader_favorites", Context.MODE_PRIVATE)

    override fun read(): List<String> =
        prefs.getString(KEY, "")?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()

    override fun write(paths: List<String>) {
        prefs.edit().putString(KEY, paths.joinToString("|")).apply()
    }

    private companion object {
        const val KEY = "keys"
    }
}

/**
 * Favorite presets (max [MAX], newest first) — the stable "candidates" list of a long
 * shader experiment session (spec 2026-08-12, M3). Independent from [ShaderRecents]:
 * recents are the last 5 APPLIED presets; favorites are user-pinned, via Y on a focused
 * row or touch long-press, and surface as their own section ABOVE recents in the
 * browser Home.
 */
class ShaderFavorites(private val store: ShaderFavoritesStore) {

    fun list(): List<String> = store.read()

    fun add(path: String) {
        if (path.isBlank()) return
        val updated = (listOf(path) + list().filter { it != path }).take(MAX)
        store.write(updated)
    }

    fun remove(path: String) {
        if (path.isBlank()) return
        store.write(list().filter { it != path })
    }

    fun isFavorite(path: String): Boolean = path.isNotBlank() && path in store.read()

    /** @return the NEW state: true when the preset ended up favorited, false when removed. */
    fun toggle(path: String): Boolean =
        if (isFavorite(path)) {
            remove(path)
            false
        } else {
            add(path)
            true
        }

    companion object {
        const val MAX = 20

        fun fromContext(context: Context): ShaderFavorites =
            ShaderFavorites(SharedPrefsShaderFavoritesStore(context))
    }
}
