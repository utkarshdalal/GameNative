package app.gamenative.html5.profile

import kotlinx.serialization.Serializable

// title-specific override surface applied on top of a pack default. loaded from
// assets/html5/packs/<pack>-patches.json (optional file). intentionally narrow:
// engine/entryPoint are NOT overridable -- those are pack-defining.

// merge semantics (see ProfileRegistry.applyOverrides):
// - patches, shims: concat (pack first, override appended)
// - gamepadKeySynthesisMap, overlay, saves, input, workerShim, desktopUaSpoof: non-null replace (null = inherit)
@Serializable
data class PatchOverrides(
    val patches: List<Patch> = emptyList(),
    val shims: List<String> = emptyList(),
    val gamepadKeySynthesisMap: Map<String, String>? = null,
    val overlay: String? = null,
    val saves: SaveSpec? = null,
    val input: InputSpec? = null,
    // byAppId override surface. null = inherit pack default; explicit true/false wins.
    val workerShim: Boolean? = null,
    // desktop UA / platform spoof. null = inherit. used by titles whose engine branches on
    // platform/UA (e.g. c2 videoplus).
    val desktopUaSpoof: Boolean? = null,
    // bridge-authoritative fs mode. null = inherit pack default. opt-in per title for packs
    // whose default is false but a specific title's plugin set probes save slots heavily;
    // opt-out for pack:rmmv titles that legitimately call fs.* on assets (rare).
    val fsBridgeOnly: Boolean? = null,
)

// per-pack patches.json shape. byAppId keyed by full appId -- STEAM_<n> / CUSTOM_GAME_<n>.
@Serializable
data class PatchRegistry(
    val byAppId: Map<String, PatchOverrides> = emptyMap(),
)
