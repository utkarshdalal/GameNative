package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamUtilsSteamInputTest {

    private fun enabled(realClient: Boolean, switchOn: Boolean, ownManifest: Boolean, compatible: Boolean = true) =
        SteamUtils.steamInputEnabledFor(realClient, switchOn, ownManifest) { compatible }

    @Test
    fun `the emulator follows the container switch even when the game ships a manifest`() {
        assertFalse(enabled(realClient = false, switchOn = false, ownManifest = true))
        assertTrue(enabled(realClient = false, switchOn = true, ownManifest = true))
    }

    @Test
    fun `the emulator follows the container switch for games without a manifest`() {
        assertFalse(enabled(realClient = false, switchOn = false, ownManifest = false))
        assertTrue(enabled(realClient = false, switchOn = true, ownManifest = false))
    }

    @Test
    fun `the real Steam client drives a manifest game when it has a layout for it`() {
        assertTrue(enabled(realClient = true, switchOn = false, ownManifest = true, compatible = true))
        assertFalse(enabled(realClient = true, switchOn = true, ownManifest = true, compatible = false))
    }

    @Test
    fun `the real Steam client follows the switch for games without a manifest`() {
        assertFalse(enabled(realClient = true, switchOn = false, ownManifest = false))
        assertTrue(enabled(realClient = true, switchOn = true, ownManifest = false))
    }
}
