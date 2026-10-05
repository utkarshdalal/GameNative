package app.gamenative.utils

import app.gamenative.data.LaunchInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamLaunchOptionsTest {

    private fun option(type: String, arguments: String) = LaunchInfo(
        executable = "Game.exe",
        workingDir = "",
        description = "",
        type = type,
        configOS = EnumSet.of(OS.windows),
        configArch = OSArch.Unknown,
        arguments = arguments,
    )

    // Hyper Dash: SteamVR, Oculus and a flat spectator mode.
    private val hyperDash = listOf(
        option("vr", "-vrmode OpenVR"),
        option("othervr", "-vrmode Oculus"),
        option("option1", "-novr -vrmode None"),
    )

    @Test
    fun `Oculus-only options are never offered`() {
        assertEquals(listOf("-vrmode OpenVR"), SteamLaunchOptions.candidates(hyperDash, LaunchMode.VR).map { it.arguments })
        assertEquals(listOf("-novr -vrmode None"), SteamLaunchOptions.candidates(hyperDash, LaunchMode.FLAT).map { it.arguments })
    }

    @Test
    fun `OpenXR options count as VR`() {
        val escapeSimulator = listOf(option("none", ""), option("vr", "-vr"), option("openxr", "-openxr"))
        assertEquals(listOf("-vr", "-openxr"), SteamLaunchOptions.candidates(escapeSimulator, LaunchMode.VR).map { it.arguments })
        assertEquals(listOf(""), SteamLaunchOptions.candidates(escapeSimulator, LaunchMode.FLAT).map { it.arguments })
    }

    @Test
    fun `options are identified by executable and arguments`() {
        assertEquals("Game.exe|-vrmode OpenVR", SteamLaunchOptions.key(hyperDash[0]))
    }

    @Test
    fun `duplicate options are offered once`() {
        val behemoth = listOf(option("vr", "-steam -hmd=OpenXR"), option("vr", "-steam -hmd=OpenXR"), option("vr", ""))
        assertEquals(2, SteamLaunchOptions.candidates(behemoth, LaunchMode.VR).size)
    }
}
