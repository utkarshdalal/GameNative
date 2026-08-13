package app.gamenative.shaders

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-game shader config (spec 2026-08-12): one entry per game (keyed by container id,
 * the appId), fully default = no entry (shader off). Nothing leaks across games.
 */
@Serializable
data class PerGameShaderConfig(
    val enabled: Boolean = false,
    val presetPath: String = "",
    val presetName: String = "",
    val relativePath: String = "",
) {
    /** A config indistinguishable from "no shader": saved as entry REMOVAL, not a row. */
    fun isDefault(): Boolean =
        !enabled && presetPath.isEmpty() && presetName.isEmpty() && relativePath.isEmpty()
}

/**
 * JSON-backed per-game shader store (spec 2026-08-12): replaces the container extras as
 * the source of truth for the RetroArch shader selection. Shaders are OFF by default for
 * every game; a selection is associated with the game where it was made; uninstalling a
 * game clears only that game's entry ([app.gamenative.utils.ContainerUtils.deleteContainer]).
 *
 * File format: a single JSON object `{ "appId": {enabled, presetPath, presetName,
 * relativePath}, ... }`. Writes are atomic (tmp + rename); malformed content degrades to
 * an empty store and recovers on the next save.
 */
class PerGameShaderStore(private val file: File) {

    /** The last saved config for [id], or null when the game has none (shader off). */
    fun loadForGame(id: String): PerGameShaderConfig? = entries()[id]

    fun hasEntry(id: String): Boolean = loadForGame(id) != null

    /** Persists [config] for [id]; a fully default config REMOVES the entry instead. */
    fun saveForGame(id: String, config: PerGameShaderConfig) {
        val current = entries().toMutableMap()
        if (config.isDefault()) {
            current.remove(id)
        } else {
            current[id] = config
        }
        write(current)
    }

    /** Removes the entry of [id] only; other games stay untouched. Absent id = no-op. */
    fun clearForGame(id: String) {
        val current = entries().toMutableMap()
        if (current.remove(id) == null) return
        write(current)
    }

    /**
     * App ids with a shader entry whose [PerGameShaderConfig.enabled] is true — the
     * library badge set (spec 2026-08-12, M4). Malformed content degrades to an empty
     * set (same policy as [loadForGame]).
     */
    fun enabledGameIds(): Set<String> =
        entries().filterValues { it.enabled }.keys

    private fun entries(): Map<String, PerGameShaderConfig> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PerGameShaderConfig>>(file.readText())
        }.getOrElse { emptyMap() }
    }

    private fun write(entries: Map<String, PerGameShaderConfig>) {
        // The file disappears with the last entry — no file == every game off.
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Store in the app-internal `shaders/` dir, shared by every game screen. */
        fun fromContext(context: Context): PerGameShaderStore =
            PerGameShaderStore(File(File(context.applicationContext.filesDir, "shaders"), "per_game.json"))
    }
}
