package app.gamenative.shaders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heavy-preset label (spec 2026-08-12, M5). Thresholds calibrated against the real
 * catalog manifest: every known Adreno-killer (crt-royale, crt-guest-advanced,
 * cathode-retro, crt-yah, mame_hlsl, crt-super-xbr, Mega Bezel) is >= 10 passes AND
 * >= 12 deps; every typical single-effect preset is below at least one bound.
 */
class ShaderPresetCostTest {

    private fun preset(passes: Int, deps: Int) = ShaderPreset(
        path = "crt/test.slangp",
        family = "crt",
        passes = passes,
        deps = List(deps) { "file-$it.slang" },
    )

    // ── exact boundaries ──

    @Test
    fun `exact thresholds are heavy`() {
        assertTrue(ShaderPresetCost.isHeavyPreset(preset(passes = 10, deps = 12)))
    }

    @Test
    fun `one pass below the pass threshold is not heavy`() {
        assertFalse(ShaderPresetCost.isHeavyPreset(preset(passes = 9, deps = 12)))
    }

    @Test
    fun `one dep below the dep threshold is not heavy`() {
        assertFalse(ShaderPresetCost.isHeavyPreset(preset(passes = 10, deps = 11)))
    }

    // ── real-catalog-like fixtures ──

    @Test
    fun `crt-royale-like preset is heavy`() {
        assertTrue(ShaderPresetCost.isHeavyPreset(preset(passes = 12, deps = 38)))
    }

    @Test
    fun `fxaa-like preset is light`() {
        assertFalse(ShaderPresetCost.isHeavyPreset(preset(passes = 1, deps = 2)))
    }

    @Test
    fun `smaa-like preset is light`() {
        assertFalse(ShaderPresetCost.isHeavyPreset(preset(passes = 4, deps = 8)))
    }

    @Test
    fun `many deps alone do not make a preset heavy`() {
        // A bezel reference file can pull 19 deps with a single pass — cheap to render.
        assertFalse(ShaderPresetCost.isHeavyPreset(preset(passes = 1, deps = 19)))
    }

    @Test
    fun `many passes alone do not make a preset heavy`() {
        // Multi-pass with a tiny closure is unusual; both signals must agree.
        assertFalse(ShaderPresetCost.isHeavyPreset(preset(passes = 15, deps = 5)))
    }
}
