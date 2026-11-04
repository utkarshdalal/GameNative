package app.gamenative.powercontrol

import com.winlator.winhandler.ProcessInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which winhandler-reported process gets which container mask under gamescope: the same rules
 * the X11 window-map path applies in Win32AppWorkarounds.
 */
class ContainerAffinityTest {
    private val mask = 0xF0       // cores 4-7
    private val maskWoW64 = 0xC0  // cores 6-7

    private fun process(name: String, wow64: Boolean = false, affinity: Int = 0xFF) =
        ProcessInfo(1234, name, 0L, affinity, wow64)

    @Test
    fun sixtyFourBitProcessGetsTheMainList() {
        assertEquals(mask, ContainerAffinity.targetMask(process("Game.exe"), mask, maskWoW64, pinnedGame = null, powerControlOwnsGame = false))
    }

    @Test
    fun wow64ProcessGetsTheWoW64List() {
        assertEquals(maskWoW64, ContainerAffinity.targetMask(process("Game32.exe", wow64 = true), mask, maskWoW64, pinnedGame = null, powerControlOwnsGame = false))
    }

    @Test
    fun steamWineServicesAndBackgroundPinnedProcessesStayOut() {
        for (name in listOf("steam.exe", "Steam.exe", "winhandler.exe", "services.exe", "wineserver", "start.exe", "wineboot.exe", "winedevice.exe", "plugplay.exe", "svchost.exe", "rpcss.exe", "conhost.exe")) {
            assertNull(name, ContainerAffinity.targetMask(process(name), mask, maskWoW64, pinnedGame = null, powerControlOwnsGame = false))
        }
    }

    @Test
    fun pinnedGameStaysOutOnlyWhilePowerControlOwnsIt() {
        assertNull(ContainerAffinity.targetMask(process("Game.exe"), mask, maskWoW64, pinnedGame = "game.exe", powerControlOwnsGame = true))
        assertEquals(mask, ContainerAffinity.targetMask(process("Game.exe"), mask, maskWoW64, pinnedGame = "game.exe", powerControlOwnsGame = false))
        assertEquals(mask, ContainerAffinity.targetMask(process("Launcher.exe"), mask, maskWoW64, pinnedGame = "game.exe", powerControlOwnsGame = true))
    }

    @Test
    fun windowedHelpersStillGetTheList() {
        for (name in listOf("explorer.exe", "tabtip.exe", "UnityCrashHandler64.exe")) {
            assertEquals(name, mask, ContainerAffinity.targetMask(process(name), mask, maskWoW64, pinnedGame = null, powerControlOwnsGame = false))
        }
    }

    @Test
    fun emptyMaskMeansNoRequest() {
        assertNull(ContainerAffinity.targetMask(process("Game32.exe", wow64 = true), mask, 0, pinnedGame = null, powerControlOwnsGame = false))
    }

    @Test
    fun reportedMaskComparesOnTheLowSixteenBits() {
        assertTrue(ContainerAffinity.sameMask(0xF0, 0xF0))
        assertTrue(ContainerAffinity.sameMask(0xFFFF00F0.toInt(), 0xF0))
        assertFalse(ContainerAffinity.sameMask(0xFF, 0xF0))
    }
}
