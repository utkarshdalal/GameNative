package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamUtilsSteamInputTest {

    private fun enabled(headless: Boolean, switchOn: Boolean, ownManifest: Boolean, compatible: Boolean = true) =
        SteamUtils.steamInputEnabledFor(headless, switchOn, ownManifest) { compatible }

    @Test
    fun `the emulator follows the container switch even when the game ships a manifest`() {
        assertFalse(enabled(headless = false, switchOn = false, ownManifest = true))
        assertTrue(enabled(headless = false, switchOn = true, ownManifest = true))
    }

    @Test
    fun `the emulator follows the container switch for games without a manifest`() {
        assertFalse(enabled(headless = false, switchOn = false, ownManifest = false))
        assertTrue(enabled(headless = false, switchOn = true, ownManifest = false))
    }

    @Test
    fun `headless Steam drives a manifest game when the host has a layout for it`() {
        assertTrue(enabled(headless = true, switchOn = false, ownManifest = true, compatible = true))
        assertFalse(enabled(headless = true, switchOn = true, ownManifest = true, compatible = false))
    }

    @Test
    fun `headless Steam follows the switch for games without a manifest`() {
        assertFalse(enabled(headless = true, switchOn = false, ownManifest = false))
        assertTrue(enabled(headless = true, switchOn = true, ownManifest = false))
    }
}
