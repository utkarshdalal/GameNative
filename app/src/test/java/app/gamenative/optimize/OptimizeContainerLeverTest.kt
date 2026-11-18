package app.gamenative.optimize

import android.app.Application
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OptimizeContainerLeverTest {

    private fun edit(live: ContainerData, key: String, value: String?): ContainerData {
        val edit = OptimizeApplier.editFor(key, value) ?: return live
        return ContainerUtils.applyBestConfigMapToContainerData(live, SupportSuggestionApplier.updatesFor(live, listOf(edit)))
    }

    @Test
    fun envVarThatDidNotExistIsRemovedOnRevert() {
        val live = ContainerData(envVars = "A=1 B=two\\ words")
        val prior = OptimizeApplier.configValue(live, "envVars.GN_NEW")
        assertNull(prior)
        val applied = edit(live, "envVars.GN_NEW", "5")
        assertEquals("5", OptimizeApplier.configValue(applied, "envVars.GN_NEW"))
        val reverted = edit(applied, "envVars.GN_NEW", prior)
        assertFalse(EnvVars(reverted.envVars).has("GN_NEW"))
        assertEquals(live.envVars, reverted.envVars)
    }

    @Test
    fun emptyEnvVarComesBackEmpty() {
        val live = ContainerData(envVars = "A=1 GN_EMPTY=")
        val prior = OptimizeApplier.configValue(live, "envVars.GN_EMPTY")
        assertEquals("", prior)
        val reverted = edit(edit(live, "envVars.GN_EMPTY", "1"), "envVars.GN_EMPTY", prior)
        assertTrue(EnvVars(reverted.envVars).has("GN_EMPTY"))
        assertEquals("", EnvVars(reverted.envVars).get("GN_EMPTY"))
    }

    @Test
    fun dxvkOptionsGoThroughDxvkConfig() {
        assertTrue(OptimizeApplier.isDxvkOption("dxwrapperConfig.dxvk.numCompilerThreads"))
        assertFalse(OptimizeApplier.isDxvkOption("dxwrapperConfig.maxDeviceMemory"))
        val live = ContainerData(envVars = "A=1")
        val config = OptimizeApplier.dxvkConfigPut("", "dxvk.numCompilerThreads", "2")
        val applied = edit(live, OptimizeApplier.DXVK_CONFIG_KEY, config)
        assertEquals("2", OptimizeApplier.configValue(applied, "dxwrapperConfig.dxvk.numCompilerThreads"))
        assertEquals("dxvk.numCompilerThreads=2", EnvVars(applied.envVars).get("DXVK_CONFIG"))
        assertFalse(EnvVars(edit(applied, OptimizeApplier.DXVK_CONFIG_KEY, null).envVars).has("DXVK_CONFIG"))
    }

    @Test
    fun dxvkConfigKeepsOtherEntriesAndSections() {
        val existing = "dxgi.syncInterval = 0;[game.exe];d3d11.cachedDynamicResources = a"
        val set = OptimizeApplier.dxvkConfigPut(existing, "dxvk.numCompilerThreads", "2")
        assertEquals("dxgi.syncInterval = 0;dxvk.numCompilerThreads=2;[game.exe];d3d11.cachedDynamicResources = a", set)
        assertEquals("1", OptimizeApplier.dxvkConfigGet(OptimizeApplier.dxvkConfigPut(set, "dxgi.syncInterval", "1"), "dxgi.syncInterval"))
        assertNull(OptimizeApplier.dxvkConfigGet(set, "d3d11.cachedDynamicResources"))
        assertEquals(existing, OptimizeApplier.dxvkConfigPut(set, "dxvk.numCompilerThreads", null))
        assertEquals("dxgi.customDeviceDesc=Some GPU", OptimizeApplier.dxvkConfigPut("", "dxgi.customDeviceDesc", "Some GPU"))
        assertEquals("Some GPU", OptimizeApplier.dxvkConfigGet("dxgi.customDeviceDesc = \"Some GPU\"", "dxgi.customDeviceDesc"))
    }

    @Test
    fun presentModeIsTheGraphicsDriverKey() {
        assertEquals(OptimizeApplier.PRESENT_MODE_KEY, OptimizeApplier.canonicalContainerKey("presentMode"))
        assertEquals("envVars.A", OptimizeApplier.canonicalContainerKey("envVars.A"))
        assertTrue(OptimizeApplier.isAllowedContainerKey(OptimizeApplier.PRESENT_MODE_KEY))
        assertEquals(setOf("fifo", "mailbox", "immediate"), OptimizeApplier.PRESENT_MODES)
        val live = ContainerData(graphicsDriverConfig = "version=26.0.0,presentMode=mailbox,syncFrame=0")
        val prior = OptimizeApplier.configValue(live, OptimizeApplier.PRESENT_MODE_KEY)
        assertEquals("mailbox", prior)
        val applied = edit(live, OptimizeApplier.PRESENT_MODE_KEY, "fifo")
        assertEquals("fifo", OptimizeApplier.configValue(applied, OptimizeApplier.PRESENT_MODE_KEY))
        assertEquals("0", OptimizeApplier.configValue(applied, "graphicsDriverConfig.syncFrame"))
        assertEquals("mailbox", OptimizeApplier.configValue(edit(applied, OptimizeApplier.PRESENT_MODE_KEY, prior), OptimizeApplier.PRESENT_MODE_KEY))
    }

    @Test
    fun aDxvkConfigEnvLeverMergesIntoTheExistingValue() {
        val existing = "dxgi.syncInterval = 0;[game.exe];d3d11.cachedDynamicResources = a"
        val options = OptimizeApplier.dxvkOptions(
            listOf(OptimizeApplier.DXVK_CONFIG_KEY to "dxgi.maxDeviceMemory = 4096;dxgi.syncInterval = 1", "envVars.OTHER" to "1"),
        )
        assertEquals(listOf("dxgi.maxDeviceMemory" to "4096", "dxgi.syncInterval" to "1"), options)
        val merged = OptimizeApplier.dxvkMerged(existing, options)
        assertEquals("dxgi.syncInterval=1;dxgi.maxDeviceMemory=4096;[game.exe];d3d11.cachedDynamicResources = a", merged)
        assertEquals("dxgi.maxDeviceMemory=4096;dxgi.syncInterval=1", OptimizeApplier.dxvkMerged(null, options))
        val both = OptimizeApplier.dxvkOptions(
            listOf("dxwrapperConfig.dxvk.numCompilerThreads" to "3", OptimizeApplier.DXVK_CONFIG_KEY to "dxgi.maxDeviceMemory = \"2048\""),
        )
        assertEquals(listOf("dxvk.numCompilerThreads" to "3", "dxgi.maxDeviceMemory" to "2048"), both)
    }

    @Test
    fun theMergedDxvkConfigIsStoredWithoutQuotesAroundIt() {
        val live = ContainerData(envVars = "A=1 DXVK_CONFIG=dxgi.syncInterval\\ =\\ 0")
        assertEquals("dxgi.syncInterval = 0", OptimizeApplier.configValue(live, OptimizeApplier.DXVK_CONFIG_KEY))
        val merged = OptimizeApplier.dxvkMerged(
            OptimizeApplier.configValue(live, OptimizeApplier.DXVK_CONFIG_KEY),
            listOf("dxgi.maxDeviceMemory" to "4096"),
        )
        val applied = edit(live, OptimizeApplier.DXVK_CONFIG_KEY, merged)
        val env = EnvVars(applied.envVars)
        assertEquals("dxgi.syncInterval = 0;dxgi.maxDeviceMemory=4096", env.get("DXVK_CONFIG"))
        assertTrue(env.toStringArray().contains("DXVK_CONFIG=dxgi.syncInterval = 0;dxgi.maxDeviceMemory=4096"))
        assertEquals("1", env.get("A"))
    }

    @Test
    fun aDxvkOptionOpIsWrittenUnquotedWithoutSpaces() {
        val options = OptimizeApplier.dxvkOptions(
            listOf("dxwrapperConfig.dxvk.numCompilerThreads" to "2", "dxwrapperConfig.d3d11.samplerAnisotropy" to "4"),
        )
        val merged = OptimizeApplier.dxvkMerged("", options)
        assertEquals("dxvk.numCompilerThreads=2;d3d11.samplerAnisotropy=4", merged)
        assertFalse(merged.contains(" = "))
        assertFalse(merged.contains('"'))
        val applied = edit(ContainerData(envVars = "A=1"), OptimizeApplier.DXVK_CONFIG_KEY, merged)
        assertEquals("dxvk.numCompilerThreads=2;d3d11.samplerAnisotropy=4", EnvVars(applied.envVars).get("DXVK_CONFIG"))
        assertEquals("4", OptimizeApplier.configValue(applied, "dxwrapperConfig.d3d11.samplerAnisotropy"))
    }

    @Test
    fun theGamescopeUpscaleFilterAndOutputSizeAreContainerKeys() {
        assertEquals("gamescopeScalingFilter", OptimizeApplier.SCALING_FILTER_KEY)
        assertEquals("gamescopeOutputResolution", OptimizeApplier.OUTPUT_RESOLUTION_KEY)
        assertTrue(OptimizeApplier.isAllowedContainerKey(OptimizeApplier.SCALING_FILTER_KEY))
        assertTrue(OptimizeApplier.isAllowedContainerKey(OptimizeApplier.OUTPUT_RESOLUTION_KEY))
        assertEquals(OptimizeApplier.SCALING_FILTER_KEY, OptimizeApplier.canonicalContainerKey("extras.gamescopeScalingFilter"))
        assertEquals(OptimizeApplier.OUTPUT_RESOLUTION_KEY, OptimizeApplier.canonicalContainerKey("extras.gamescopeOutputResolution"))
        assertEquals("extras.other", OptimizeApplier.canonicalContainerKey("extras.other"))
        assertEquals("fsr", OptimizeApplier.extraValue(OptimizeApplier.SCALING_FILTER_KEY, "FSR"))
        assertEquals("linear", OptimizeApplier.extraValue(OptimizeApplier.SCALING_FILTER_KEY, "linear"))
        assertNull(OptimizeApplier.extraValue(OptimizeApplier.SCALING_FILTER_KEY, "bicubic"))
        assertEquals("1280x720", OptimizeApplier.extraValue(OptimizeApplier.OUTPUT_RESOLUTION_KEY, "1280x720"))
        assertEquals("device", OptimizeApplier.extraValue(OptimizeApplier.OUTPUT_RESOLUTION_KEY, "Device"))
        assertEquals("off", OptimizeApplier.extraValue(OptimizeApplier.OUTPUT_RESOLUTION_KEY, "off"))
        assertNull(OptimizeApplier.extraValue(OptimizeApplier.OUTPUT_RESOLUTION_KEY, "4k"))
    }

    @Test
    fun aPotatoContainerLeverTakesResolutionUpscalingAndDxvkKeysTogether() {
        val set = listOf(
            "screenSize" to "854x480",
            "gamescopeOutputResolution" to "device",
            "gamescopeScalingFilter" to "FSR",
            "dxwrapperConfig.dxvk.numCompilerThreads" to "2",
        ).map { (k, v) -> OptimizeApplier.canonicalContainerKey(k) to v }
        set.forEach { assertTrue(it.first, OptimizeApplier.isAllowedContainerKey(it.first)) }
        assertEquals("fsr", OptimizeApplier.extraValue(OptimizeApplier.SCALING_FILTER_KEY, "FSR"))
        assertEquals("device", OptimizeApplier.extraValue(OptimizeApplier.OUTPUT_RESOLUTION_KEY, "device"))
        assertEquals(listOf("dxvk.numCompilerThreads" to "2"), OptimizeApplier.dxvkOptions(set))
    }
}
