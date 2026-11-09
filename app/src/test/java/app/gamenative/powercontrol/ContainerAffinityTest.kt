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

    private val defaultMaskWoW64 = 0xF0  // upper half of 8 cores

    @Test
    fun windowProcessWoW64WithAnExplicitListGetsTheWoW64List() {
        assertEquals(maskWoW64, ContainerAffinity.windowProcessMask(process("Borderlands2.exe", wow64 = true), mask, maskWoW64, defaultMaskWoW64 = 0x0F))
    }

    @Test
    fun windowProcessWoW64WithTheDefaultListGetsTheMainList() {
        assertEquals(0xFF, ContainerAffinity.windowProcessMask(process("Borderlands2.exe", wow64 = true), 0xFF, defaultMaskWoW64, defaultMaskWoW64))
    }

    @Test
    fun windowProcessReported64BitGetsTheMainList() {
        assertEquals(mask, ContainerAffinity.windowProcessMask(process("Game.exe"), mask, maskWoW64, defaultMaskWoW64))
    }

    @Test
    fun windowProcessMissingFromTheListGetsTheMainList() {
        assertEquals(mask, ContainerAffinity.windowProcessMask(null, mask, maskWoW64, defaultMaskWoW64))
    }

    @Test
    fun windowProcessWithAnEmptyMaskGetsNoRequest() {
        assertNull(ContainerAffinity.windowProcessMask(process("Game.exe"), 0, maskWoW64, defaultMaskWoW64))
    }

    @Test
    fun onlyANonDefaultNonEmptyWoW64ListIsExplicit() {
        assertTrue(ContainerAffinity.isExplicitWoW64Mask(0xC0, 0xF0))
        assertFalse(ContainerAffinity.isExplicitWoW64Mask(0xF0, 0xF0))
        assertFalse(ContainerAffinity.isExplicitWoW64Mask(0, 0xF0))
    }

    @Test
    fun reportedMaskComparesOnTheLowSixteenBits() {
        assertTrue(ContainerAffinity.sameMask(0xF0, 0xF0))
        assertTrue(ContainerAffinity.sameMask(0xFFFF00F0.toInt(), 0xF0))
        assertFalse(ContainerAffinity.sameMask(0xFF, 0xF0))
    }
}
