package app.gamenative.shaders

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * One-time, best-effort removal of the old embedded-shader directories (spec §6, "limpeza
 * adicional"): `filesDir/retroarch` and `filesDir/retroarch_presets` were materialized by
 * the removed `ShaderImporter` (131 bundled presets, ~2.4 MB) and have no purpose anymore.
 * Runs once per app install (flag in SharedPreferences) at game-screen boot; failures are
 * logged and ignored — this is pure cleanup, never a blocker.
 */
object ShaderLegacyMigration {

    private const val PREFS = "shader_legacy_migration"
    private const val KEY_DONE = "done_v1"

    private val LEGACY_DIRS = listOf("retroarch", "retroarch_presets")

    fun cleanupOnce(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val filesDir = appContext.filesDir
        for (name in LEGACY_DIRS) {
            val dir = File(filesDir, name)
            if (!dir.exists()) continue
            runCatching {
                val bytes = dir.totalSize()
                dir.deleteRecursively()
                Timber.i("ShaderLegacyMigration: removed %s (%.1f MB)", name, bytes / 1_000_000f)
            }.onFailure { e ->
                Timber.w(e, "ShaderLegacyMigration: could not remove %s", name)
            }
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    private fun File.totalSize(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
