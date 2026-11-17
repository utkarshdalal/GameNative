package app.gamenative.powercontrol

import app.gamenative.powercontrol.LudashiPerformanceMode.BALANCED
import app.gamenative.powercontrol.LudashiPerformanceMode.PERFORMANCE
import app.gamenative.powercontrol.LudashiPerformanceMode.POWER_SAVE
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.fexcore.FEXCorePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LudashiRuntimeProfileTest {

    @Test
    fun balancedProfileChangesRealContainerRuntimeSettings() {
        val original = ContainerData(
            name = "Profile test",
            screenSize = "1920x1080",
            graphicsDriver = "vortek",
            dxwrapperConfig = "version=1.10.3,async=1",
            cpuList = "0,1,2,3,4,5,6,7",
            videoMemorySize = "2048",
        )

        val updated = LudashiRuntimeProfile.apply(original, BALANCED)
        val dxvk = KeyValueSet(updated.dxwrapperConfig)

        assertEquals("960x540", updated.screenSize)
        assertEquals(Box86_64Preset.INTERMEDIATE, updated.box64Preset)
        assertEquals(FEXCorePreset.INTERMEDIATE, updated.fexcorePreset)
        assertEquals("30", dxvk.get("framerate"))
        assertEquals("1.10.3", dxvk.get("version"))
        assertEquals("1", dxvk.get("async"))
        assertEquals("vortek", updated.graphicsDriver)
        assertEquals("0,1,2,3,4,5,6,7", updated.cpuList)
        assertEquals("2048", updated.videoMemorySize)
    }

    @Test
    fun allModesMapToExpectedRuntimePresets() {
        val original = ContainerData(name = "Profile test")

        val powerSave = LudashiRuntimeProfile.apply(original, POWER_SAVE)
        val balanced = LudashiRuntimeProfile.apply(original, BALANCED)
        val performance = LudashiRuntimeProfile.apply(original, PERFORMANCE)

        assertEquals("854x480", powerSave.screenSize)
        assertEquals(Box86_64Preset.STABILITY, powerSave.box64Preset)
        assertEquals(FEXCorePreset.STABILITY, powerSave.fexcorePreset)

        assertEquals("960x540", balanced.screenSize)
        assertEquals(Box86_64Preset.INTERMEDIATE, balanced.box64Preset)
        assertEquals(FEXCorePreset.INTERMEDIATE, balanced.fexcorePreset)

        assertEquals("960x540", performance.screenSize)
        assertEquals(Box86_64Preset.PERFORMANCE, performance.box64Preset)
        assertEquals(FEXCorePreset.PERFORMANCE, performance.fexcorePreset)

        assertTrue(listOf(powerSave, balanced, performance).all {
            KeyValueSet(it.dxwrapperConfig).get("framerate") == "30"
        })
    }
}
