package app.gamenative.utils

import app.gamenative.data.LaunchInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import com.winlator.container.Container
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamUtilsVrLaunchArgumentsTest {

    private fun launch(type: String, arguments: String, executable: String = "Quantum Void.exe") = LaunchInfo(
        executable = executable,
        workingDir = "",
        description = "",
        type = type,
        configOS = EnumSet.of(OS.windows),
        configArch = OSArch.Unknown,
        arguments = arguments,
    )

    private fun container(executablePath: String = "Quantum Void.exe") =
        Container("STEAM_2710650").apply { this.executablePath = executablePath }

    @Test
    fun `VR launch passes the VR option arguments`() {
        assertEquals("vrmode", SteamUtils.vrLaunchArguments(container(), launch("vr", "vrmode"), vrLaunch = true))
    }

    @Test
    fun `flat launch never passes them`() {
        assertEquals("", SteamUtils.vrLaunchArguments(container(), launch("vr", "vrmode"), vrLaunch = false))
    }

    @Test
    fun `non VR option arguments are left to the container`() {
        assertEquals("", SteamUtils.vrLaunchArguments(container(), launch("default", "-windowed"), vrLaunch = true))
    }

    @Test
    fun `a different container executable wins`() {
        assertEquals(
            "",
            SteamUtils.vrLaunchArguments(container("Tools\\Editor.exe"), launch("vr", "vrmode"), vrLaunch = true),
        )
    }

    @Test
    fun `executable comparison ignores slashes and case`() {
        assertEquals(
            "-vr",
            SteamUtils.vrLaunchArguments(
                container("game\\bin\\win64\\HLVR.exe"),
                launch("vr", "-vr", executable = "game/bin/win64/hlvr.exe"),
                vrLaunch = true,
            ),
        )
    }
}
