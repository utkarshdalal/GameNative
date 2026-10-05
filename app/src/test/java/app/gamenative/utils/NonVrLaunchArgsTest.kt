package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NonVrLaunchArgsTest {
    @Test
    fun findsCommonNoVrFlags() {
        assertEquals(listOf("-nonvrmode"), NonVrLaunchArgs.find("-nonvrmode"))
        assertEquals(listOf("-novr", "--no-vr", "-NoHMD", "-nosteamvr"), NonVrLaunchArgs.find("-novr -dx11 --no-vr -NoHMD -nosteamvr"))
        assertEquals(listOf("-vrmode None"), NonVrLaunchArgs.find("-vrmode None -screen-fullscreen 1"))
    }

    @Test
    fun ignoresVrAndUnrelatedFlags() {
        assertEquals(emptyList<String>(), NonVrLaunchArgs.find("vrmode -vrmode openvr -dx11 -novsync -nohome"))
        assertEquals(emptyList<String>(), NonVrLaunchArgs.find(""))
    }

    @Test
    fun stripKeepsTheOtherArguments() {
        assertEquals("-dx11 -skipintro", NonVrLaunchArgs.strip("-dx11 -novr -skipintro -vrmode none"))
        assertEquals("", NonVrLaunchArgs.strip("-nonvrmode"))
    }
}
