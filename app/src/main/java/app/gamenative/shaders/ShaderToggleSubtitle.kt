package app.gamenative.shaders

/**
 * Subtitle shown by the RetroArch shaders toggle row in the EFFECTS tab.
 * Extracted as a pure decision (pattern: ShaderDoubleClickLogic / GamepadStickLogic)
 * so the self-heal state can be unit-tested without Compose.
 */
enum class ShaderToggleSubtitle { Downloading, ActivePreset, SelectedNotDownloaded, PickPreset, Off }

/**
 * Decides the toggle-row subtitle from the live shader state.
 *
 * [Downloading] (spec 2026-08-12, M2) is the FIRST branch: an in-flight preset
 * download dominates every other state — the download lives in the hoisted
 * [app.gamenative.ui.component.ShaderSectionState], so it survives closing the
 * browser; the EFFECTS row must show progress instead of the stale selection.
 *
 * [SelectedNotDownloaded] is the self-heal state introduced by the closure-aware
 * resolution (2026-08-12): the preset's dependency closure is incomplete in the cache,
 * so the absolute path was cleared — nothing is loaded even though the selection is
 * still visible. The user must re-pick the preset in the browser, which downloads ONLY
 * the missing files.
 */
fun shaderToggleSubtitle(
    enabled: Boolean,
    name: String,
    path: String,
    installing: Boolean = false,
): ShaderToggleSubtitle =
    when {
        installing -> ShaderToggleSubtitle.Downloading
        enabled && name.isNotEmpty() && path.isNotEmpty() -> ShaderToggleSubtitle.ActivePreset
        enabled && name.isNotEmpty() -> ShaderToggleSubtitle.SelectedNotDownloaded
        enabled -> ShaderToggleSubtitle.PickPreset
        else -> ShaderToggleSubtitle.Off
    }
