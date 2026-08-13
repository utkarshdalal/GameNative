package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Self-heal subtitle decision (2026-08-12): the EFFECTS toggle row must distinguish a
 * preset that is actually loaded from one whose closure is incomplete (selection
 * visible, nothing loaded — the browser re-pick downloads only the missing files).
 */
class ShaderToggleSubtitleTest {

    @Test
    fun `enabled with name and path is active preset`() {
        assertEquals(
            ShaderToggleSubtitle.ActivePreset,
            shaderToggleSubtitle(enabled = true, name = "Technicolor", path = "/cache/film/technicolor.slangp"),
        )
    }

    @Test
    fun `enabled with name but empty path is selected but not downloaded`() {
        assertEquals(
            ShaderToggleSubtitle.SelectedNotDownloaded,
            shaderToggleSubtitle(enabled = true, name = "Technicolor", path = ""),
        )
    }

    @Test
    fun `enabled without name asks to pick a preset`() {
        assertEquals(
            ShaderToggleSubtitle.PickPreset,
            shaderToggleSubtitle(enabled = true, name = "", path = ""),
        )
    }

    @Test
    fun `disabled shows off regardless of stale name and path`() {
        assertEquals(
            ShaderToggleSubtitle.Off,
            shaderToggleSubtitle(enabled = false, name = "Technicolor", path = "/cache/film/technicolor.slangp"),
        )
    }

    // ── M2 (spec 2026-08-12): an in-flight download dominates every other state ──

    @Test
    fun `installing wins over active preset`() {
        assertEquals(
            ShaderToggleSubtitle.Downloading,
            shaderToggleSubtitle(
                enabled = true,
                name = "Technicolor",
                path = "/cache/film/technicolor.slangp",
                installing = true,
            ),
        )
    }

    @Test
    fun `installing wins over selected but not downloaded`() {
        assertEquals(
            ShaderToggleSubtitle.Downloading,
            shaderToggleSubtitle(
                enabled = true,
                name = "Technicolor",
                path = "",
                installing = true,
            ),
        )
    }

    @Test
    fun `installing with everything off still shows downloading`() {
        assertEquals(
            ShaderToggleSubtitle.Downloading,
            shaderToggleSubtitle(
                enabled = false,
                name = "",
                path = "",
                installing = true,
            ),
        )
    }

    @Test
    fun `not installing keeps the previous behavior`() {
        assertEquals(
            ShaderToggleSubtitle.ActivePreset,
            shaderToggleSubtitle(
                enabled = true,
                name = "Technicolor",
                path = "/cache/film/technicolor.slangp",
                installing = false,
            ),
        )
        assertEquals(
            ShaderToggleSubtitle.Off,
            shaderToggleSubtitle(
                enabled = false,
                name = "",
                path = "",
                installing = false,
            ),
        )
    }
}
