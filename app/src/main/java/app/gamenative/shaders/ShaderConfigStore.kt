package app.gamenative.shaders

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import java.io.File
import com.winlator.renderer.RetroArchShaderConfig

private const val SHADER_KEY_ENABLED = "retroArchShaderEnabled"
private const val SHADER_KEY_PRESET_PATH = "retroArchShaderPresetPath"
private const val SHADER_KEY_PRESET_NAME = "retroArchShaderPresetName"
private const val SHADER_KEY_RELATIVE_PATH = "retroArchShaderRelativePath"

private const val SHADER_MIGRATION_PREFS = "shader_per_game_migration"
private const val SHADER_MIGRATION_KEY_DONE = "done_v1"

/**
 * Loads the persisted per-game RetroArch shader config (spec 2026-08-12) from the
 * [PerGameShaderStore], keyed by container id (the game appId). No entry == shader off
 * for that game. Container extras are legacy — read only by the one-shot migration.
 */
fun loadShaderConfig(context: Context, container: Container?): RetroArchShaderConfig {
    if (container == null) return RetroArchShaderConfig(false, "", "", "", "")
    val entry = PerGameShaderStore.fromContext(context).loadForGame(container.id)
        ?: return RetroArchShaderConfig(false, "", "", "", "")
    return RetroArchShaderConfig(entry.enabled, entry.presetPath, entry.presetName, "", entry.relativePath)
}

/**
 * Persists the per-game RetroArch shader config (spec 2026-08-12) to the
 * [PerGameShaderStore]. The store persists on its own — callers must NOT call
 * [Container.saveData] for shader state anymore.
 */
fun persistShaderConfig(context: Context, container: Container?, config: RetroArchShaderConfig) {
    if (container == null) return
    PerGameShaderStore.fromContext(context).saveForGame(
        container.id,
        PerGameShaderConfig(config.enabled, config.presetPath, config.presetName, config.relativePath),
    )
}

/**
 * One-shot migration decision (spec 2026-08-12, pure function): old shader state lived
 * in the container extras and must move to the per-game store exactly once per app
 * install. The store is the source of truth — a pre-existing entry always wins over
 * stale extras (which are still cleaned up).
 */
enum class ShaderMigrationDecision { AlreadyDone, NothingToMigrate, Migrate, StoreAlreadyHasEntry }

fun decideShaderMigration(
    migrationDone: Boolean,
    hasContainerExtras: Boolean,
    storeHasEntry: Boolean,
): ShaderMigrationDecision = when {
    migrationDone -> ShaderMigrationDecision.AlreadyDone
    !hasContainerExtras -> ShaderMigrationDecision.NothingToMigrate
    storeHasEntry -> ShaderMigrationDecision.StoreAlreadyHasEntry
    else -> ShaderMigrationDecision.Migrate
}

/**
 * One-shot migration (spec 2026-08-12, "padrão da migração §6 do spec de hardening"):
 * runs once per app install (SharedPreferences flag) at game-screen boot. Every
 * container with legacy shader extras has its config copied to the per-game store
 * (preserving the owner game's state — and ONLY it), then the extras are removed.
 * Games without extras have no store entry and open with shaders off.
 *
 * [liveContainer] is the container instance already held by the caller (XServerScreen
 * boot): its in-memory extras predate the migration, so any later [Container.saveData]
 * would resurrect the legacy keys on disk. It is scrubbed here explicitly.
 */
fun migrateShaderConfigFromContainer(context: Context, liveContainer: Container? = null) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(SHADER_MIGRATION_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(SHADER_MIGRATION_KEY_DONE, false)) return
    val store = PerGameShaderStore.fromContext(appContext)
    val manager = ContainerManager(appContext)
    for (container in manager.containers) {
        val hasExtras = hasShaderExtras(container)
        when (decideShaderMigration(false, hasExtras, store.hasEntry(container.id))) {
            ShaderMigrationDecision.Migrate ->
                store.saveForGame(container.id, configFromExtras(container))
            ShaderMigrationDecision.StoreAlreadyHasEntry -> Unit // store wins; extras still removed below
            ShaderMigrationDecision.AlreadyDone, ShaderMigrationDecision.NothingToMigrate -> continue
        }
        if (hasExtras) {
            removeShaderExtras(container)
            container.saveData()
        }
    }
    // Scrub the caller's live instance so later saveData calls cannot resurrect extras.
    if (liveContainer != null && hasShaderExtras(liveContainer)) {
        removeShaderExtras(liveContainer)
        liveContainer.saveData()
    }
    prefs.edit().putBoolean(SHADER_MIGRATION_KEY_DONE, true).apply()
}

private fun hasShaderExtras(container: Container): Boolean =
    container.getExtra(SHADER_KEY_ENABLED).isNotEmpty() ||
        container.getExtra(SHADER_KEY_PRESET_PATH).isNotEmpty() ||
        container.getExtra(SHADER_KEY_PRESET_NAME).isNotEmpty() ||
        container.getExtra(SHADER_KEY_RELATIVE_PATH).isNotEmpty()

private fun configFromExtras(container: Container): PerGameShaderConfig = PerGameShaderConfig(
    enabled = container.getExtra(SHADER_KEY_ENABLED).toBooleanStrictOrNull() ?: false,
    presetPath = container.getExtra(SHADER_KEY_PRESET_PATH),
    presetName = container.getExtra(SHADER_KEY_PRESET_NAME),
    relativePath = container.getExtra(SHADER_KEY_RELATIVE_PATH),
)

private fun removeShaderExtras(container: Container) {
    container.putExtra(SHADER_KEY_ENABLED, null)
    container.putExtra(SHADER_KEY_PRESET_PATH, null)
    container.putExtra(SHADER_KEY_PRESET_NAME, null)
    container.putExtra(SHADER_KEY_RELATIVE_PATH, null)
}

/**
 * Result of resolving a persisted shader config against the current pack (spec §6).
 * [presetPath] is the absolute path that should be handed to the renderer — empty means
 * "load nothing" while the menu selection ([presetName]/[relativePath]) stays visible.
 */
data class ResolvedShaderConfig(
    val enabled: Boolean,
    val presetPath: String,
    val presetName: String,
    val relativePath: String,
)

/**
 * Migration rule (spec §6) for configs written by the old embedded-preset system. Old
 * configs persist an absolute path under `.../retroarch_presets/...` — a directory that no
 * longer exists — plus a repo-relative path. Resolution, in order:
 *
 *  1. `presetPath` exists on disk AND its full closure is cached → load normally.
 *  2. `presetPath` is gone and `relativePath` resolves inside the cache with a COMPLETE
 *     closure → re-resolve `packDir/relativePath`; the caller persists the new absolute
 *     path.
 *  3. Nothing resolves (closure incomplete / pack not installed) → keep `enabled` and the
 *     menu selection visible, but clear the absolute path: nothing is loaded and NOTHING
 *     is downloaded without user intent (the browser shows the preset in the cloud state;
 *     re-picking downloads ONLY the missing files).
 *  4. Old config without `relativePath` (the legacy dialog wrote only the absolute path) →
 *     same as (3): path cleared, user re-picks a preset.
 *
 * The closure check (2026-08-12) prevents loading a preset whose dependency files are not
 * all cached: librashader would fail the chain create and the shader would silently not
 * apply (e.g. technicolor without its LUT texture). Pure JVM function — unit-testable.
 */
fun resolveShaderConfig(
    config: RetroArchShaderConfig,
    packDir: File?,
    catalog: ShaderCatalog? = null,
): ResolvedShaderConfig {
    val enabled = config.enabled
    val relative = config.relativePath
    val name = config.presetName
    val path = config.presetPath
    return when {
        path.isNotEmpty() && File(path).isFile && closureComplete(path, packDir, catalog) ->
            ResolvedShaderConfig(enabled, path, name, relative)
        relative.isNotEmpty() && packDir != null && File(packDir, relative).isFile &&
            closureComplete(File(packDir, relative).absolutePath, packDir, catalog) ->
            ResolvedShaderConfig(enabled, File(packDir, relative).absolutePath, name, relative)
        else ->
            ResolvedShaderConfig(enabled, "", name, relative)
    }
}

/**
 * True when the preset at [absPath] has every file of its catalog closure in the cache.
 * Presets unknown to the catalog (or catalogs absent) fall back to file-existence alone —
 * the pre-closure behavior.
 */
fun closureComplete(absPath: String, packDir: File?, catalog: ShaderCatalog?): Boolean {
    if (catalog == null || packDir == null) return true
    val rel = absPath.removePrefix(packDir.absolutePath + File.separator)
    val preset = catalog.preset(rel) ?: return true
    return missingFiles(preset, packDir).isEmpty()
}


/**
 * Per-shader toggle-off decision (spec 2026-08-11): selecting the SAME preset clears ONLY
 * that preset — but only when it is actually LOADED. A migrated selection (§6.3) keeps
 * `relativePath` with an EMPTY absolute path (the pack was missing at boot): re-picking it
 * must LOAD the preset, never clear it — otherwise the shader "never works" after the pack
 * download completes. Pure JVM function — unit-testable.
 */
fun shouldToggleOffActivePreset(
    enabled: Boolean,
    activeRelativePath: String,
    activePresetPath: String,
    candidatePath: String,
): Boolean = enabled && candidatePath == activeRelativePath && activePresetPath.isNotEmpty()
