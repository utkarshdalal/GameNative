package app.gamenative.steamcontroller

import android.content.Context
import android.util.Log
import app.gamenative.utils.SteamControllerProfileImporter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What a saved config is sourced from. [VDF] = an imported Steam config (raw `.vdf` text, parsed at load);
 *  [AUTHORED] = built with the in-game editor (an [ScEditableConfig]). */
enum class ScConfigKind { VDF, AUTHORED }

/** One saved config in a game's registry: a stable [id] (used in file names), a user-facing [name], its [kind]. */
@Serializable
data class ScConfigEntry(val id: String, val name: String, val kind: ScConfigKind)

/** A game's saved-config registry: the ordered [configs] list + which one is [activeId] (loaded on boot). */
@Serializable
data class ScConfigRegistry(val activeId: String = "", val configs: List<ScConfigEntry> = emptyList()) {
    fun active(): ScConfigEntry? = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull()
    fun nextId(): String = generateSequence(1) { it + 1 }.first { n -> configs.none { it.id == "c$n" } }.let { "c$it" }
}

/**
 * Per-game Steam Controller config store. Each game (keyed by container/appId, or [DEFAULT_KEY] for the shared
 * default) owns a **registry of named configs** plus a selected *active* config that the live [TritonMapper] loads
 * on boot. A config is either an imported `.vdf` ([ScConfigKind.VDF], parsed by [SteamControllerProfileImporter])
 * or one authored in-game ([ScConfigKind.AUTHORED], an [ScEditableConfig]). Users switch the active config and
 * duplicate configs from the Controller settings; editing saves a config in place (see [saveEditableConfig]).
 *
 * Storage layout (under `filesDir/sc_configs/`):
 *   - `<key>.configs.json`   — the [ScConfigRegistry] manifest
 *   - `<key>__<id>.vdf`      — a VDF config's raw text
 *   - `<key>__<id>.sets.json`— an AUTHORED config's [ScEditableConfig]
 *   - `<key>.labels.json`    — custom menu-slot labels (per game, layered over whichever config is active)
 *
 * Legacy single-config files (`<key>.vdf` / `<key>.sets.json` / `<key>.json`) are migrated into a registry on
 * first access (see [registry]); the migration preserves the previous resolution (a `.vdf` stays active if present)
 * so an upgrade doesn't change behavior — the user opts into a different config via the selector.
 */
object ScConfigStore {
    private const val TAG = "ScConfigStore"
    private const val DIR = "sc_configs"

    /** Key for the shared config applied to any game without its own registry. */
    const val DEFAULT_KEY = "_default"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** File names are sanitized so an arbitrary container id/name can't escape the store dir. */
    private fun sanitize(key: String): String = key.ifBlank { DEFAULT_KEY }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun manifestFile(context: Context, key: String) = File(dir(context), "${sanitize(key)}.configs.json")
    private fun vdfPayload(context: Context, key: String, id: String) = File(dir(context), "${sanitize(key)}__$id.vdf")
    private fun setsPayload(context: Context, key: String, id: String) = File(dir(context), "${sanitize(key)}__$id.sets.json")
    /** A VDF config's editable **overlay** (lossless edits applied over the parsed `.vdf` base). */
    private fun overlayPayload(context: Context, key: String, id: String) = File(dir(context), "${sanitize(key)}__$id.overlay.json")
    private fun labelsFile(context: Context, key: String): File = File(dir(context), "${sanitize(key)}.labels.json")

    // Legacy single-config paths (pre-registry); read only during migration.
    private fun legacyVdf(context: Context, key: String) = File(dir(context), "${sanitize(key)}.vdf")
    private fun legacySets(context: Context, key: String) = File(dir(context), "${sanitize(key)}.sets.json")
    private fun legacyJson(context: Context, key: String) = File(dir(context), "${sanitize(key)}.json")

    // --- Registry --------------------------------------------------------------------------------------

    /** Load (or migrate-then-load) the saved-config registry for [key]. Never null; empty when nothing exists. */
    fun registry(context: Context, key: String): ScConfigRegistry {
        manifestFile(context, key).takeIf { it.isFile }?.let { f ->
            runCatching { json.decodeFromString(ScConfigRegistry.serializer(), f.readText()) }
                .onFailure { Log.w(TAG, "registry($key) parse failed: ${it.message}") }
                .getOrNull()?.let { return it }
        }
        return migrateLegacy(context, key)
    }

    // Write to a temp file then rename over the manifest, so a crash/kill mid-write can't leave a truncated
    // <key>.configs.json (live autosave/reload rewrites this often).
    private fun writeRegistry(context: Context, key: String, reg: ScConfigRegistry): Boolean =
        runCatching {
            val target = manifestFile(context, key)
            // Unique temp name so concurrent writes (autosave + a UI action) can't clobber each other's temp.
            val tmp = File.createTempFile("${target.name}-", ".tmp", target.parentFile)
            tmp.writeText(json.encodeToString(ScConfigRegistry.serializer(), reg))
            if (!tmp.renameTo(target)) { target.writeText(tmp.readText()); tmp.delete() }
            true
        }
            .onFailure { Log.w(TAG, "writeRegistry($key) failed: ${it.message}") }
            .getOrDefault(false)

    /** Build a registry from any legacy single-config files, moving them to namespaced payloads, and persist it.
     *  Active = the imported `.vdf` if present (preserves prior resolution), else the authored config. */
    private fun migrateLegacy(context: Context, key: String): ScConfigRegistry {
        val entries = ArrayList<ScConfigEntry>()
        var active = ""
        legacyVdf(context, key).takeIf { it.isFile }?.let { f ->
            val id = "vdf"
            runCatching { f.copyTo(vdfPayload(context, key, id), overwrite = true); f.delete() }
                .onFailure { Log.w(TAG, "migrate vdf($key) failed: ${it.message}") }
            entries += ScConfigEntry(id, "Imported", ScConfigKind.VDF)
            active = id
        }
        // Authored: prefer the multi-set `.sets.json`, else convert a legacy single `.json`.
        val sets = legacySets(context, key)
        val single = legacyJson(context, key)
        if (sets.isFile) {
            val id = "authored"
            val name = runCatching { json.decodeFromString(ScEditableConfig.serializer(), sets.readText()) }
                .getOrNull()?.let { it.sets.firstOrNull()?.name } ?: "Custom bindings"
            runCatching { sets.copyTo(setsPayload(context, key, id), overwrite = true); sets.delete() }
                .onFailure { Log.w(TAG, "migrate sets($key) failed: ${it.message}") }
            entries += ScConfigEntry(id, name.ifBlank { "Custom bindings" }, ScConfigKind.AUTHORED)
            if (active.isEmpty()) active = id
        } else if (single.isFile) {
            val id = "authored"
            val profile = runCatching { json.decodeFromString(ScEditableProfile.serializer(), single.readText()) }.getOrNull()
            if (profile != null) {
                val cfg = ScEditableConfig.fromSingle(profile)
                runCatching { setsPayload(context, key, id).writeText(json.encodeToString(ScEditableConfig.serializer(), cfg)) }
                    .onFailure { Log.w(TAG, "migrate json($key) failed: ${it.message}") }
                single.delete()
                entries += ScConfigEntry(id, profile.name.ifBlank { "Custom bindings" }, ScConfigKind.AUTHORED)
                if (active.isEmpty()) active = id
            }
        }
        val reg = ScConfigRegistry(active, entries)
        if (entries.isNotEmpty()) {
            writeRegistry(context, key, reg)
            Log.i(TAG, "migrated $key -> registry(active=$active, configs=${entries.map { it.id to it.kind }})")
        }
        return reg
    }

    /** The saved configs for [key], in order (empty when none exist). */
    fun listConfigs(context: Context, key: String): List<ScConfigEntry> = registry(context, key).configs

    /** The active config id for [key], or null when the game has no configs. */
    fun activeConfigId(context: Context, key: String): String? = registry(context, key).active()?.id

    /** Select [id] as the active (boot-loaded) config for [key]. Returns true if it exists and was set. */
    fun setActiveConfig(context: Context, key: String, id: String): Boolean {
        val reg = registry(context, key)
        if (reg.configs.none { it.id == id }) return false
        return writeRegistry(context, key, reg.copy(activeId = id))
    }

    /** Rename config [id]. Returns true on success. */
    fun renameConfig(context: Context, key: String, id: String, newName: String): Boolean {
        val reg = registry(context, key)
        if (reg.configs.none { it.id == id }) return false
        val updated = reg.copy(configs = reg.configs.map { if (it.id == id) it.copy(name = newName.ifBlank { it.name }) else it })
        return writeRegistry(context, key, updated)
    }

    /** Duplicate config [id] to a new config named [newName] (which becomes active). Returns the new id, or null. */
    fun duplicateConfig(context: Context, key: String, id: String, newName: String): String? {
        val reg = registry(context, key)
        val src = reg.configs.firstOrNull { it.id == id } ?: return null
        val newId = reg.nextId()
        val ok = when (src.kind) {
            ScConfigKind.VDF -> runCatching {
                vdfPayload(context, key, id).copyTo(vdfPayload(context, key, newId), overwrite = true)
                // Carry the edit overlay too, so a duplicate of an edited vdf keeps those edits.
                overlayPayload(context, key, id).takeIf { it.isFile }?.copyTo(overlayPayload(context, key, newId), overwrite = true)
                true
            }.getOrDefault(false)
            ScConfigKind.AUTHORED -> runCatching { setsPayload(context, key, id).copyTo(setsPayload(context, key, newId), overwrite = true); true }.getOrDefault(false)
        }
        if (!ok) { Log.w(TAG, "duplicateConfig($key,$id) copy failed"); return null }
        val updated = reg.copy(
            configs = reg.configs + ScConfigEntry(newId, newName.ifBlank { "${src.name} copy" }, src.kind),
            activeId = newId,
        )
        return if (writeRegistry(context, key, updated)) newId else null
    }

    /** Delete config [id] (and its payload). Active falls back to the first remaining config. Returns true if removed. */
    fun deleteConfig(context: Context, key: String, id: String): Boolean {
        val reg = registry(context, key)
        val entry = reg.configs.firstOrNull { it.id == id } ?: return false
        when (entry.kind) {
            ScConfigKind.VDF -> { vdfPayload(context, key, id).delete(); overlayPayload(context, key, id).delete() }
            ScConfigKind.AUTHORED -> setsPayload(context, key, id).delete()
        }
        val remaining = reg.configs.filter { it.id != id }
        val newActive = if (reg.activeId == id) (remaining.firstOrNull()?.id ?: "") else reg.activeId
        return writeRegistry(context, key, reg.copy(configs = remaining, activeId = newActive))
    }

    /** Import raw `.vdf` [text] for [key] as a new VDF config named [name], made active. Returns the new id, or null. */
    fun importVdfConfig(context: Context, key: String, text: String, name: String = "Imported"): String? {
        val reg = registry(context, key)
        val newId = reg.nextId()
        if (!runCatching { vdfPayload(context, key, newId).writeText(text); true }.getOrDefault(false)) {
            Log.w(TAG, "importVdfConfig($key) write failed"); return null
        }
        val updated = reg.copy(
            configs = reg.configs + ScConfigEntry(newId, name, ScConfigKind.VDF),
            activeId = newId,
        )
        return if (writeRegistry(context, key, updated)) newId else null
    }

    // --- Authored-config IO (editor) -------------------------------------------------------------------

    /**
     * Load the authored [ScEditableConfig] the editor should open for [key]: the active config if it is AUTHORED;
     * otherwise (active is a `.vdf`, or no config) the resolved config seeded into the editable model so the editor
     * still shows the current bindings. Null only when nothing resolves at all.
     */
    fun loadEditableConfig(context: Context, key: String): ScEditableConfig? {
        val active = registry(context, key).active()
        when (active?.kind) {
            ScConfigKind.AUTHORED -> setsPayload(context, key, active.id).takeIf { it.isFile }?.let { f ->
                runCatching { json.decodeFromString(ScEditableConfig.serializer(), f.readText()) }
                    .onFailure { Log.w(TAG, "loadEditableConfig($key) failed: ${it.message}") }
                    .getOrNull()?.let { return it }
            }
            // A .vdf-active config edits via an overlay: load the prior overlay if any, else seed from THIS game's
            // parsed vdf so the editor shows its action sets/bindings (advanced outputs seed as INHERIT = preserved).
            ScConfigKind.VDF -> {
                overlayPayload(context, key, active.id).takeIf { it.isFile }?.let { f ->
                    runCatching { json.decodeFromString(ScEditableConfig.serializer(), f.readText()) }
                        .onFailure { Log.w(TAG, "loadEditableConfig($key) overlay failed: ${it.message}") }
                        .getOrNull()?.let { return it }
                }
                vdfPayload(context, key, active.id).takeIf { it.isFile }?.let { parseVdf(it) }
                    ?.let { return ScEditableConfig.fromScConfig(it) }
            }
            null -> {}
        }
        // No own config: seed the editor from the resolved (shared-default) config.
        return resolveConfig(context, key)?.let { ScEditableConfig.fromScConfig(it) }
    }

    /**
     * Save the edited [cfg] for [key], in place:
     * - active is AUTHORED → overwrite its `.sets.json`.
     * - active is a `.vdf` → write an **overlay** (`.overlay.json`) beside the untouched base vdf. Resolution
     *   ([resolveActive]) parses the vdf as the base and applies the overlay on top, so menus/radials/layers/
     *   mode-shift the editor didn't touch are preserved exactly (lossless edit — no fork to a default-based copy).
     * - no active config → create a new AUTHORED config and make it active.
     * Returns true on success.
     */
    fun saveEditableConfig(context: Context, key: String, cfg: ScEditableConfig): Boolean {
        val reg = registry(context, key)
        val active = reg.active()
        val text = runCatching { json.encodeToString(ScEditableConfig.serializer(), cfg) }.getOrNull() ?: return false
        when (active?.kind) {
            ScConfigKind.AUTHORED -> return runCatching { setsPayload(context, key, active.id).writeText(text); true }
                .onFailure { Log.w(TAG, "saveEditableConfig($key) failed: ${it.message}") }
                .getOrDefault(false)
            ScConfigKind.VDF -> return runCatching { overlayPayload(context, key, active.id).writeText(text); true }
                .onFailure { Log.w(TAG, "saveEditableConfig($key) overlay failed: ${it.message}") }
                .getOrDefault(false)
            null -> {}
        }
        // No active config yet: create a fresh authored config from the edit.
        val newId = reg.nextId()
        if (!runCatching { setsPayload(context, key, newId).writeText(text); true }.getOrDefault(false)) return false
        return writeRegistry(context, key, reg.copy(
            configs = reg.configs + ScConfigEntry(newId, "Custom bindings", ScConfigKind.AUTHORED),
            activeId = newId,
        ))
    }

    // --- Compatibility shims (debug / tests / older callers) -------------------------------------------

    /** Path of the active VDF config's payload (or the conventional namespaced path) — debug logging only. */
    fun fileFor(context: Context, key: String): File {
        val active = registry(context, key).active()
        return if (active?.kind == ScConfigKind.VDF) vdfPayload(context, key, active.id) else vdfPayload(context, key, "vdf")
    }

    /** True if [key] has at least one imported `.vdf` config. */
    fun hasConfig(context: Context, key: String): Boolean = registry(context, key).configs.any { it.kind == ScConfigKind.VDF }

    /** True if [key] has at least one authored config. */
    fun hasEditable(context: Context, key: String): Boolean = registry(context, key).configs.any { it.kind == ScConfigKind.AUTHORED }

    /** Import raw `.vdf` [text] for [key] (debug/back-compat): replaces an existing VDF config if one is active. */
    fun saveVdf(context: Context, key: String, vdfText: String): Boolean {
        val active = registry(context, key).active()
        if (active?.kind == ScConfigKind.VDF) {
            return runCatching { vdfPayload(context, key, active.id).writeText(vdfText); true }.getOrDefault(false)
        }
        return importVdfConfig(context, key, vdfText) != null
    }

    /** Remove all imported `.vdf` configs for [key]. Returns true if any were removed. */
    fun removeConfig(context: Context, key: String): Boolean = removeByKind(context, key, ScConfigKind.VDF)

    /** Remove all authored configs for [key]. Returns true if any were removed. */
    fun removeEditable(context: Context, key: String): Boolean = removeByKind(context, key, ScConfigKind.AUTHORED)

    private fun removeByKind(context: Context, key: String, kind: ScConfigKind): Boolean {
        val reg = registry(context, key)
        val toRemove = reg.configs.filter { it.kind == kind }
        if (toRemove.isEmpty()) return false
        toRemove.forEach { e ->
            when (e.kind) {
                ScConfigKind.VDF -> { vdfPayload(context, key, e.id).delete(); overlayPayload(context, key, e.id).delete() }
                ScConfigKind.AUTHORED -> setsPayload(context, key, e.id).delete()
            }
        }
        val remaining = reg.configs.filter { it.kind != kind }
        val newActive = if (remaining.any { it.id == reg.activeId }) reg.activeId else (remaining.firstOrNull()?.id ?: "")
        return writeRegistry(context, key, reg.copy(configs = remaining, activeId = newActive))
    }

    // --- Custom menu-slot labels (JSON) ----------------------------------------------------------------

    /** True if custom menu labels (`<key>.labels.json`) exist for [key]. */
    fun hasLabels(context: Context, key: String): Boolean = labelsFile(context, key).isFile

    /** Load custom menu labels for [key], or null if absent/unparseable. */
    fun loadLabels(context: Context, key: String): ScMenuLabels? {
        val f = labelsFile(context, key).takeIf { it.isFile } ?: return null
        return runCatching { json.decodeFromString(ScMenuLabels.serializer(), f.readText()) }
            .onFailure { Log.w(TAG, "loadLabels($key) failed: ${it.message}") }
            .getOrNull()
    }

    /** Persist custom menu [labels] for [key]; deletes the file when there are no overrides. Returns success. */
    fun saveLabels(context: Context, key: String, labels: ScMenuLabels): Boolean =
        runCatching {
            if (labels.overrides.isEmpty()) labelsFile(context, key).let { if (it.isFile) it.delete() }
            else labelsFile(context, key).writeText(json.encodeToString(ScMenuLabels.serializer(), labels))
            true
        }.onFailure { Log.w(TAG, "saveLabels($key) failed: ${it.message}") }.getOrDefault(false)

    /** Delete custom menu labels for [key]. Returns true if a file was removed. */
    fun removeLabels(context: Context, key: String): Boolean =
        labelsFile(context, key).let { if (it.isFile) it.delete() else false }

    // --- Resolution ------------------------------------------------------------------------------------

    /**
     * Validate raw `.vdf` text without persisting: returns the parsed [ScConfig] (non-empty) or null. Lets the
     * import UI reject a bad file before saving it.
     */
    fun validate(vdfText: String): ScConfig? =
        runCatching { SteamControllerProfileImporter.importConfig(vdfText).takeIf { it.sets.isNotEmpty() } }
            .onFailure { Log.w(TAG, "validate failed: ${it.message}") }
            .getOrNull()

    /**
     * Resolve the live config for [key]: the [key]'s active config, else the shared [DEFAULT_KEY]'s active config,
     * with this game's custom menu-slot labels layered on top. Null when nothing applies, so the caller cleanly
     * falls back to [ScProfile.default].
     */
    fun forKey(context: Context, key: String): ScConfig? {
        val cfg = resolveConfig(context, key) ?: return null
        val labels = loadLabels(context, key)
        return if (labels != null) ScMenuLabelTool.apply(cfg, labels) else cfg
    }

    /** The resolved config WITHOUT custom labels applied — for the label editor to show binding-derived defaults. */
    fun rawConfig(context: Context, key: String): ScConfig? = resolveConfig(context, key)

    private fun resolveConfig(context: Context, key: String): ScConfig? {
        resolveActive(context, key)?.let { return it }
        if (key == DEFAULT_KEY) return null
        return resolveActive(context, DEFAULT_KEY)
    }

    /** Load + parse the active config for [key] into a runtime [ScConfig], or null if none/empty/unparseable. */
    private fun resolveActive(context: Context, key: String): ScConfig? {
        val entry = registry(context, key).active() ?: return null
        return when (entry.kind) {
            ScConfigKind.VDF -> {
                val base = vdfPayload(context, key, entry.id).takeIf { it.isFile }?.let { parseVdf(it) }
                // Apply the editable overlay (lossless edits) over the parsed vdf base, if one exists.
                base?.let { b ->
                    overlayPayload(context, key, entry.id).takeIf { it.isFile }?.let { f ->
                        runCatching { json.decodeFromString(ScEditableConfig.serializer(), f.readText()) }
                            .onFailure { Log.w(TAG, "resolve overlay ${entry.id} failed: ${it.message}") }
                            .getOrNull()?.let { ov -> applyOverlay(b, ov) }
                    } ?: b
                }
            }
            ScConfigKind.AUTHORED -> setsPayload(context, key, entry.id).takeIf { it.isFile }?.let { f ->
                runCatching { json.decodeFromString(ScEditableConfig.serializer(), f.readText()).toScConfig() }
                    .onFailure { Log.w(TAG, "resolve authored ${entry.id} failed: ${it.message}") }
                    .getOrNull()
            }
        }?.also { Log.i(TAG, "resolved $key -> '${entry.name}' (${entry.kind}, sets=${it.sets.keys} default=${it.defaultSetId})") }
    }

    /**
     * Apply an editable [overlay] over a parsed-vdf [base] (the lossless-edit path). For each set the base defines,
     * an overlay set with the same id resolves **against that base set's profile** — so the overlay overrides only
     * the sources it changed (representable buttons / analog modes / triggers / gyro / haptics) and inherits the
     * rest (including menus/radials and any [OutputKind.INHERIT] buttons). The base's [ScConfig.setSources] and
     * [ScConfig.shiftOverlays] (action layers + mode-shift) are preserved untouched, so editing a vdf can't destroy
     * them. Overlay sets the base lacks (new authored sets) resolve against [ScProfile.default].
     */
    private fun applyOverlay(base: ScConfig, overlay: ScEditableConfig): ScConfig {
        val merged = LinkedHashMap<String, ScProfile>()
        for ((setId, baseProfile) in base.sets) {
            val o = overlay.sets.firstOrNull { it.id == setId }
            merged[setId] = if (o == null) baseProfile else o.profile.toScProfile(baseProfile)
        }
        // Sets the overlay ADDS that the base lacks (e.g. a new authored action layer) resolve against default, and
        // an added layer contributes its derived source list so it merges correctly ([mergeProfiles]).
        val sources = HashMap(base.setSources)
        for (o in overlay.sets) if (!base.sets.containsKey(o.id)) {
            merged[o.id] = o.profile.toScProfile()
            if (o.isLayer) sources[o.id] = o.profile.definedSources()
        }
        val def = if (merged.containsKey(overlay.defaultSetId)) overlay.defaultSetId else base.defaultSetId
        return ScConfig(sets = merged, defaultSetId = def, setSources = sources, shiftOverlays = base.shiftOverlays)
    }

    private fun parseVdf(f: File): ScConfig? = runCatching {
        val cfg = SteamControllerProfileImporter.importConfig(f.readText())
        if (cfg.sets.isEmpty()) {
            Log.w(TAG, "${f.name} parsed to 0 sets — ignoring")
            null
        } else {
            cfg
        }
    }.onFailure { Log.w(TAG, "parse ${f.name} failed: ${it.message}") }.getOrNull()
}
