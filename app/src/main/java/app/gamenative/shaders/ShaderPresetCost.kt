package app.gamenative.shaders

/**
 * Data-driven "heavy" label for the shader browser (spec 2026-08-12, M5): expensive
 * presets (multi-pass, CRT-royale and family) must be distinguishable BEFORE download —
 * on an Adreno the user picks "blind" and blames the app for the FPS drop.
 *
 * Threshold calibration (reviewed against the real manifest, 2026-08-12, 2 541
 * presets — the spec's initial "deps >= 6" example was NOT usable: it flags 78% of
 * the catalog, because the Mega Bezel family alone references 20-100+ files). The
 * signal that actually separates the known Adreno-killers from typical single-effect
 * presets is the COMBINATION of resolved pass count and closure size:
 *
 * - known heavy (crt-royale 12p/38d, crt-guest-advanced 12p/16d, cathode-retro 11p/17d,
 *   crt-yah 13p/46d, mame_hlsl 11p/14d, crt-super-xbr 11p/13d, crt-1tap-bloom 26p/15d,
 *   Mega Bezel variants 30-50p/80-100d) — ALL are >= 10 passes AND >= 12 deps;
 * - typical light (fxaa 1p/2d, smaa 4p/8d, anamorphic 1p/2d, crt-easymode 1p/2d,
 *   crt-lottes 1p/2d, crt-hyllian 5p/8d, hdr 1p/3d) — ALL are below at least one bound.
 *
 * With both bounds, 16% of non-bezel presets are flagged (the bezel family is
 * legitimately heavy — flagging it is honest, not noise).
 */
object ShaderPresetCost {

    /** Resolved shader passes (preset + #reference closure) at/above which a preset is expensive. */
    const val HEAVY_PASSES_THRESHOLD = 10

    /** Dependency-closure files at/above which the preset carries real payload (shaders, textures, includes). */
    const val HEAVY_DEPS_THRESHOLD = 12

    fun isHeavyPreset(preset: ShaderPreset): Boolean =
        preset.passes >= HEAVY_PASSES_THRESHOLD && preset.deps.size >= HEAVY_DEPS_THRESHOLD
}
