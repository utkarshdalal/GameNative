package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.api.prepareCommunityConfigForApply
import app.gamenative.api.sanitizeCommunityConfig
import com.winlator.container.ContainerData
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommunityConfigApplicationTest {
    private lateinit var context: Context

    private val config = Json.parseToJsonElement(
        """
        {
          "graphicsDriver": "wrapper",
          "graphicsDriverVersion": "Turnip Adreno Driver T26 (@Mr_Purple_666)",
          "graphicsDriverConfig": "version=Turnip Adreno Driver T26 (@Mr_Purple_666);presentMode=mailbox",
          "dxwrapper": "dxvk",
          "dxwrapperConfig": "version=2.4.1,async=1,vkd3dVersion=2.14.1",
          "startupSelection": 2,
          "box64Version": "0.4.2",
          "box64Preset": "COMPATIBILITY",
          "containerVariant": "bionic",
          "wineVersion": "proton-9.0-arm64ec",
          "emulator": "FEXCore",
          "fexcoreVersion": "2605",
          "fexcoreTSOMode": "Strict",
          "fexcoreX87Mode": "Slow",
          "fexcoreMultiBlock": "Enabled",
          "fexcorePreset": "INTERMEDIATE",
          "useLegacyDRM": true,
          "audioDriver": "alsa",
          "wincomponents": "direct3d=0,directsound=0",
          "videoMemorySize": "4096",
          "execArgs": "-dx11 -windowed",
          "screenSize": "640x480",
          "envVars": "GAME_FIX=1 WINEDLLOVERRIDES=xaudio2_7=n,b"
        }
        """.trimIndent(),
    ).jsonObject

    private val allowedKeys = setOf(
        "graphicsDriver",
        "graphicsDriverVersion",
        "graphicsDriverConfig",
        "dxwrapper",
        "dxwrapperConfig",
        "startupSelection",
        "box64Version",
        "box64Preset",
        "containerVariant",
        "wineVersion",
        "emulator",
        "fexcoreVersion",
        "fexcoreTSOMode",
        "fexcoreX87Mode",
        "fexcoreMultiBlock",
        "fexcorePreset",
        "useLegacyDRM",
        "audioDriver",
        "wincomponents",
        "videoMemorySize",
        "execArgs",
        "envVars",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)

        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val manifestFile = listOf(
            File(workingDir, "manifest.json"),
            File(workingDir.parentFile, "manifest.json"),
        ).firstOrNull { it.exists() }
        if (manifestFile != null) {
            PrefManager.componentManifestJson = manifestFile.readText()
            PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
        }
    }

    @Test
    fun allAllowedCommunityFieldsReachContainerUnchanged() = runBlocking {
        val sanitized = sanitizeCommunityConfig(config)
        assertEquals(allowedKeys, sanitized.keys)

        val result = BestConfigService.parseConfigResult(
            context = context,
            configJson = sanitized,
            matchType = "fallback_match",
            applyKnownConfig = true,
            storeMatch = false,
            matchedGpu = "Adreno (TM) 840",
            preserveConfigValues = true,
        )

        assertTrue(result.missingComponents.isEmpty())
        assertEquals(allowedKeys, result.config.keys)

        val updated = ContainerUtils.applyBestConfigMapToContainerData(
            containerData = ContainerData(startupSelection = 0),
            bestConfigMap = result.config,
        )

        assertEquals("wrapper", updated.graphicsDriver)
        assertEquals("Turnip Adreno Driver T26 (@Mr_Purple_666)", updated.graphicsDriverVersion)
        assertEquals(
            "version=Turnip Adreno Driver T26 (@Mr_Purple_666);presentMode=mailbox",
            updated.graphicsDriverConfig,
        )
        assertEquals("dxvk", updated.dxwrapper)
        assertEquals("version=2.4.1,async=1,vkd3dVersion=2.14.1", updated.dxwrapperConfig)
        assertEquals(2, updated.startupSelection.toInt())
        assertEquals("0.4.2", updated.box64Version)
        assertEquals("COMPATIBILITY", updated.box64Preset)
        assertEquals("bionic", updated.containerVariant)
        assertEquals("proton-9.0-arm64ec", updated.wineVersion)
        assertEquals("FEXCore", updated.emulator)
        assertEquals("2605", updated.fexcoreVersion)
        assertEquals("Strict", updated.fexcoreTSOMode)
        assertEquals("Slow", updated.fexcoreX87Mode)
        assertEquals("Enabled", updated.fexcoreMultiBlock)
        assertEquals("INTERMEDIATE", updated.fexcorePreset)
        assertTrue(updated.useLegacyDRM)
        assertEquals("alsa", updated.audioDriver)
        assertEquals("direct3d=0,directsound=0", updated.wincomponents)
        assertEquals("4096", updated.videoMemorySize)
        assertEquals("-dx11 -windowed", updated.execArgs)
        assertEquals("GAME_FIX=1 WINEDLLOVERRIDES=xaudio2_7=n,b", updated.envVars)
    }

    @Test
    fun optionalLaunchSettingsRequireExplicitSelection() {
        val excluded = prepareCommunityConfigForApply(
            config = config,
            applyLaunchArguments = false,
            applyEnvironmentVariables = false,
        )
        assertFalse(excluded.containsKey("execArgs"))
        assertFalse(excluded.containsKey("envVars"))

        val included = prepareCommunityConfigForApply(
            config = config,
            applyLaunchArguments = true,
            applyEnvironmentVariables = true,
        )
        assertEquals("-dx11 -windowed", included["execArgs"]?.toString()?.trim('"'))
        assertEquals(
            "GAME_FIX=1 WINEDLLOVERRIDES=xaudio2_7=n,b",
            included["envVars"]?.toString()?.trim('"'),
        )
    }

    @Test
    fun preserveModeIsOptInAndKnownConfigFilteringRemainsDefault() = runBlocking {
        val sanitized = sanitizeCommunityConfig(config)

        val knownConfigResult = BestConfigService.parseConfigResult(
            context = context,
            configJson = sanitized,
            matchType = "fallback_match",
            applyKnownConfig = true,
            matchedGpu = "",
        )
        assertFalse(knownConfigResult.config.containsKey("graphicsDriver"))
        assertFalse(knownConfigResult.config.containsKey("graphicsDriverVersion"))
        assertFalse(knownConfigResult.config.containsKey("graphicsDriverConfig"))
        assertFalse(knownConfigResult.config.containsKey("dxwrapper"))
        assertFalse(knownConfigResult.config.containsKey("dxwrapperConfig"))

        val communityResult = BestConfigService.parseConfigResult(
            context = context,
            configJson = sanitized,
            matchType = "fallback_match",
            applyKnownConfig = true,
            matchedGpu = "Adreno (TM) 840",
            preserveConfigValues = true,
        )
        assertEquals("wrapper", communityResult.config["graphicsDriver"])
        assertEquals(
            "Turnip Adreno Driver T26 (@Mr_Purple_666)",
            communityResult.config["graphicsDriverVersion"],
        )
        assertTrue(
            (communityResult.config["graphicsDriverConfig"] as String)
                .contains("Turnip Adreno Driver T26 (@Mr_Purple_666)"),
        )
        assertEquals("dxvk", communityResult.config["dxwrapper"])
        assertEquals(
            "version=2.4.1,async=1,vkd3dVersion=2.14.1",
            communityResult.config["dxwrapperConfig"],
        )
    }
}
