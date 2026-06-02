package app.gamenative.html5.profile

import kotlinx.serialization.Serializable

// per-title overrides from <pack>-patches.json. engine/entryPoint are deliberately NOT overridable.
// merge (ProfileRegistry.applyOverrides): lists concat after the pack's; other fields replace when non-null.
@Serializable
data class PatchOverrides(
    val patches: List<Patch> = emptyList(),
    val shims: List<String> = emptyList(),
    val gamepadKeySynthesisMap: Map<String, String>? = null,
    val overlay: String? = null,
    val saves: SaveSpec? = null,
    val input: InputSpec? = null,
    val workerShim: Boolean? = null,
    val desktopUaSpoof: Boolean? = null,
    // opt-out for rmmv titles that read assets via fs.*; opt-in for titles whose plugins probe save slots heavily.
    val fsBridgeOnly: Boolean? = null,
)

// byAppId keyed by full appId (STEAM_<n> / CUSTOM_GAME_<n>).
@Serializable
data class PatchRegistry(
    val byAppId: Map<String, PatchOverrides> = emptyMap(),
)
