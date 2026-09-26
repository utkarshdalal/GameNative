package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModDllOverrideConfigImportTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        PrefManager.componentManifestJson = File("../manifest.json").readText()
        PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
    }

    private fun exportedConfig(setting: String?): JSONObject {
        val container = Container("CUSTOM_GAME_1").apply {
            setRootDir(temp.newFolder())
            containerVariant = Container.BIONIC
            wineVersion = "proton-9.0-x86_64"
            dxWrapper = "wined3d"
            dxWrapperConfig = "version=9.2"
            if (setting != null) putExtra(ModDllOverrides.SETTING, setting)
        }
        container.saveData()
        return JSONObject(container.containerJson)
    }

    private fun importConfig(json: JSONObject, current: ContainerData): ContainerData = runBlocking {
        val parsed = BestConfigService.parseConfigResult(
            context, Json.parseToJsonElement(json.toString()).jsonObject,
            "exact_gpu_match", true, forceApply = true,
        ).config
        assertTrue("Export must produce a valid imported config", parsed.isNotEmpty())
        ContainerUtils.applyBestConfigMapToContainerData(current, parsed)
    }

    @Test fun exportedOptOutSurvivesImport() {
        val json = exportedConfig("false")
        assertEquals("false", json.getJSONObject("extraData").getString(ModDllOverrides.SETTING))
        assertFalse(importConfig(json, ContainerData(autoModDllOverrides = true)).autoModDllOverrides)
    }

    @Test fun exportedOptInSurvivesImport() {
        assertTrue(importConfig(exportedConfig("true"), ContainerData(autoModDllOverrides = false)).autoModDllOverrides)
    }

    @Test fun olderAndMalformedExportsPreserveCurrentChoice() {
        for (setting in listOf(null, "invalid")) {
            val json = exportedConfig(setting)
            for (enabled in listOf(false, true)) {
                assertEquals(enabled, importConfig(json, ContainerData(autoModDllOverrides = enabled)).autoModDllOverrides)
            }
        }
    }
}
