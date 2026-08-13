package app.gamenative.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.gamenative.shaders.ApplyPresetResult
import app.gamenative.shaders.PackCancelledException
import app.gamenative.shaders.PackMeteredException
import app.gamenative.shaders.PackNoSpaceException
import app.gamenative.shaders.ShaderCatalog
import app.gamenative.shaders.ShaderFavorites
import app.gamenative.shaders.ShaderPack
import app.gamenative.shaders.ShaderPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.gamenative.shaders.ShaderRecents
import app.gamenative.shaders.friendlyName
import app.gamenative.shaders.loadShaderConfig
import app.gamenative.shaders.resolveShaderConfig
import app.gamenative.shaders.shouldToggleOffActivePreset
import app.gamenative.shaders.persistShaderConfig
import com.winlator.container.Container
import com.winlator.renderer.RetroArchShaderConfig
import com.winlator.renderer.VulkanRenderer

/**
 * RetroArch shader state shared by the QuickMenu panel (toggle row) and the full-screen
 * shader browser. Hoisted into the QuickMenu so both surfaces see the same live state.
 *
 * No shader ships with the app — [catalog] is browsable immediately (manifest only) and
 * preset files come from the on-demand [pack] (see [ShaderPack]).
 */
class ShaderSectionState(
    private val renderer: VulkanRenderer,
    private val container: Container?,
    private val context: Context,
) {
    val catalog: ShaderCatalog? = ShaderCatalog.load(context)
    val pack: ShaderPack = ShaderPack(context, catalog?.data?.source?.commit ?: "")
    val recents: ShaderRecents = ShaderRecents(context)
    /** User-pinned presets (spec 2026-08-12, M3): independent from recents. */
    val favorites: ShaderFavorites = ShaderFavorites.fromContext(context)

    /** Browser navigation cache (user request 2026-08-11): survives browser close/reopen so
     *  the user returns to the same level where the shader was chosen. */
    val browser = ShaderBrowserState()

    // Download state lives HERE (not in the browser surface): closing the browser while a
    // preset download is in flight must NOT kill it — the files finish caching and the
    // requested preset auto-applies (one decision, not two).
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var installing by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var installFailed by mutableStateOf(false)
    var installNoSpace by mutableStateOf(false)
    var pendingPreset by mutableStateOf<ShaderPreset?>(null)
    var meteredConfirm by mutableStateOf(false)

    /**
     * Downloads ONLY [preset]'s dependency closure (user decision 2026-08-12: nothing is
     * downloaded by default — only the shader the user picks; shared files already cached
     * are reused). [allowMetered] is the user's explicit consent from the metered dialog;
     * on success the preset is applied automatically.
     */
    fun startInstall(preset: ShaderPreset, allowMetered: Boolean = false) {
        if (installing) return
        if (catalog == null) return
        pendingPreset = preset
        installing = true
        installFailed = false
        installNoSpace = false
        progress = 0f
        installScope.launch {
            val result = pack.downloadPreset(
                preset = preset,
                allowMetered = allowMetered,
                onProgress = { downloaded, total ->
                    if (total > 0) progress = downloaded.toFloat() / total
                },
            )
            installing = false
            when {
                result.isSuccess -> {
                    installFailed = false
                    installNoSpace = false
                    // Complete the user's original intent: apply the preset they asked for.
                    pendingPreset?.let { requested -> applyPreset(requested) }
                    pendingPreset = null
                }
                else -> when (result.exceptionOrNull()) {
                    is PackCancelledException -> {
                        // Clean stop via the cancel action — not an error state.
                        installFailed = false
                        installNoSpace = false
                    }
                    is PackNoSpaceException -> {
                        installFailed = true
                        installNoSpace = true
                    }
                    is PackMeteredException -> {
                        // No byte was transferred: ask before using mobile data.
                        installFailed = false
                        meteredConfirm = true
                    }
                    else -> {
                        installFailed = true
                        installNoSpace = false
                    }
                }
            }
        }
    }

    /** Aborts the in-flight preset download (partial files are dropped). */
    fun cancelInstall() {
        pack.cancel()
        installing = false
    }

    // §6 migration: the persisted config may point at the old `retroarch_presets` tree that
    // no longer exists. Resolve it against the installed pack: keep the selection visible,
    // clear unreachable absolute paths, and never touch the network here.
    private val initial = loadShaderConfig(context, container)
    private val resolved = resolveShaderConfig(initial, pack.packDir, catalog)
    var shaderEnabled by mutableStateOf(resolved.enabled)
    var shaderPresetPath by mutableStateOf(resolved.presetPath)
    var shaderPresetName by mutableStateOf(resolved.presetName)
    var shaderRelativePath by mutableStateOf(resolved.relativePath)

    init {
        // Case §6.2: absolute path re-resolved inside the pack — persist the new absolute
        // path so the next load is direct (and XServerScreen loads it at game start).
        if (resolved.presetPath != initial.presetPath) {
            persistShaderConfig(
                context,
                container,
                RetroArchShaderConfig(resolved.enabled, resolved.presetPath, resolved.presetName, "", resolved.relativePath),
            )
        }
    }

    fun persistShaderState(enabled: Boolean, path: String, name: String, relative: String) {
        // The per-game store persists on its own (spec 2026-08-12) — no container saveData.
        persistShaderConfig(context, container, RetroArchShaderConfig(enabled, path, name, "", relative))
    }

    fun disableShaders() {
        shaderEnabled = false
        renderer.setRetroArchShaderEnabled(false)
        persistShaderState(false, "", "", "")
    }

    fun toggleShaders() {
        if (shaderEnabled) {
            disableShaders()
        } else {
            shaderEnabled = true
            if (shaderRelativePath.isNotEmpty() && shaderPresetPath.isNotEmpty()) {
                renderer.loadRetroArchShaderPreset(shaderPresetPath)
                renderer.setRetroArchShaderEnabled(true)
                persistShaderState(true, shaderPresetPath, shaderPresetName, shaderRelativePath)
            } else {
                renderer.setRetroArchShaderEnabled(true)
                persistShaderState(true, "", "", "")
            }
        }
    }

    /**
     * Applies a preset from the local cache. Selecting the SAME preset that is already
     * active clears ONLY that preset (spec 2026-08-11): the frame renders unshaded while
     * the shader system stays enabled — the main toggle is the only on/off for the system.
     * Returns [ApplyPresetResult] so the browser can tell a LOAD from a toggle-off CLEAR
     * (the double-click gesture arms only on a real load — spec 2026-08-12, UX fix 3).
     */
    fun applyPreset(preset: ShaderPreset): ApplyPresetResult {
        val file = pack.presetFile(preset) ?: return ApplyPresetResult.Missing
        // Toggle-off only applies to a LOADED preset (see shouldToggleOffActivePreset): a
        // migrated selection (§6.3) keeps relativePath without an absolute path — picking
        // it must LOAD it, not clear it, or the shader "never works" after the download.
        if (shouldToggleOffActivePreset(shaderEnabled, shaderRelativePath, shaderPresetPath, preset.path)) {
            shaderPresetPath = ""
            shaderPresetName = ""
            shaderRelativePath = ""
            renderer.clearRetroArchShaderPreset()
            persistShaderState(true, "", "", "")
            return ApplyPresetResult.Cleared
        }
        shaderPresetPath = file.absolutePath
        shaderPresetName = friendlyName(preset.path)
        shaderRelativePath = preset.path
        shaderEnabled = true
        renderer.loadRetroArchShaderPreset(file.absolutePath)
        renderer.setRetroArchShaderEnabled(true)
        persistShaderState(true, file.absolutePath, shaderPresetName, preset.path)
        recents.add(preset.path)
        return ApplyPresetResult.Applied
    }

    /** True when [preset] is the currently applied one. */
    fun isActive(preset: ShaderPreset): Boolean = shaderEnabled && preset.path == shaderRelativePath
}

@Composable
fun rememberShaderSectionState(renderer: VulkanRenderer, container: Container?): ShaderSectionState {
    val context = LocalContext.current
    return remember(renderer, container) { ShaderSectionState(renderer, container, context) }
}
