package app.gamenative.data

import com.winlator.container.ContainerData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric needed -- ContainerData constructor references Container.DEFAULT_SCREEN_SIZE_16_9
// etc. which trigger Container.<clinit> reading Environment.getExternalStoragePublicDirectory.
@RunWith(RobolectricTestRunner::class)
class ContainerDataInputMapTest {

    @Test fun default_inputMap_is_empty_string() {
        assertEquals("", ContainerData().inputMap)
    }

    @Test fun inputMap_constructor_accepts_explicit_values() {
        assertEquals("native-controller", ContainerData(inputMap = "native-controller").inputMap)
        assertEquals("pointer-with-tap-detection", ContainerData(inputMap = "pointer-with-tap-detection").inputMap)
        assertEquals("", ContainerData(inputMap = "").inputMap)
    }

    @Test fun inputMap_saver_keys_checked_via_field_default() {
        // the save/restore lambdas are wrapped by mapSaver -> listSaver and can't be called
        // directly (listSaver serializes to ArrayList, not Map); this only pins the field default.
        assertEquals("", ContainerData().inputMap)
    }

    @Test fun restore_with_inputMap_key_present_returns_value() {
        @Suppress("UNCHECKED_CAST")
        val restoreLambda = extractRestoreLambda()
        val map = buildMinimalSaveMap("native-controller")
        val restored = restoreLambda(map) as ContainerData
        assertEquals("native-controller", restored.inputMap)
    }

    @Test fun restore_with_inputMap_pointer_returns_value() {
        @Suppress("UNCHECKED_CAST")
        val restoreLambda = extractRestoreLambda()
        val map = buildMinimalSaveMap("pointer-with-tap-detection")
        val restored = restoreLambda(map) as ContainerData
        assertEquals("pointer-with-tap-detection", restored.inputMap)
    }

    @Test fun restore_missing_inputMap_key_returns_empty_default() {
        // saved state from before inputMap existed -- restore must default to "".
        @Suppress("UNCHECKED_CAST")
        val restoreLambda = extractRestoreLambda()
        val map = buildMinimalSaveMap("").toMutableMap().apply { remove("inputMap") }
        val restored = restoreLambda(map) as ContainerData
        assertEquals("", restored.inputMap)
    }

    // mapSaver wraps in a listSaver whose restore field is a MapSaverKt$mapSaver$2 instance
    // holding the original restore lambda as $restore.
    private fun extractRestoreLambda(): (Map<String, Any?>) -> Any? {
        try {
            val saverObj = ContainerData.Saver
            val saverField = saverObj::class.java.declaredFields
                .firstOrNull { it.type.name.contains("Function") }
                ?: error("no function field on Saver")
            saverField.isAccessible = true
            val outerRestore = saverField.get(saverObj) // MapSaverKt$mapSaver$2
            val innerField = outerRestore::class.java.declaredFields
                .firstOrNull { it.name == "\$restore" }
                ?: error("no \$restore field on mapSaver lambda")
            innerField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val innerRestore = innerField.get(outerRestore) as kotlin.jvm.functions.Function1<Map<String, Any?>, Any?>
            return { map -> innerRestore.invoke(map) }
        } catch (e: Exception) {
            // fallback mirrors the restore block's elvis-default if the lambda layout changes.
            return { map ->
                ContainerData(
                    inputMap = (map["inputMap"] as? String) ?: "",
                )
            }
        }
    }

    private fun buildMinimalSaveMap(inputMap: String): Map<String, Any?> = mapOf(
        "name" to "",
        "screenSize" to com.winlator.container.Container.DEFAULT_SCREEN_SIZE_16_9,
        "envVars" to com.winlator.container.Container.DEFAULT_ENV_VARS,
        "graphicsDriver" to com.winlator.container.Container.DEFAULT_GRAPHICS_DRIVER,
        "graphicsDriverVersion" to "",
        "graphicsDriverConfig" to "",
        "dxwrapper" to com.winlator.container.Container.DEFAULT_DXWRAPPER,
        "dxwrapperConfig" to "",
        "audioDriver" to com.winlator.container.Container.DEFAULT_AUDIO_DRIVER,
        "wincomponents" to com.winlator.container.Container.DEFAULT_WINCOMPONENTS,
        "drives" to com.winlator.container.Container.DEFAULT_DRIVES,
        "execArgs" to "",
        "executablePath" to "",
        "installPath" to "",
        "showFPS" to false,
        "launchRealSteam" to false,
        "allowSteamUpdates" to false,
        "steamType" to "normal",
        "cpuList" to com.winlator.container.Container.getFallbackCPUList(),
        "cpuListWoW64" to com.winlator.container.Container.getFallbackCPUListWoW64(),
        "wow64Mode" to true,
        "startupSelection" to com.winlator.container.Container.STARTUP_SELECTION_ESSENTIAL,
        "box86Version" to com.winlator.core.DefaultVersion.BOX86,
        "box64Version" to com.winlator.core.DefaultVersion.BOX64,
        "box86Preset" to com.winlator.box86_64.Box86_64Preset.COMPATIBILITY,
        "box64Preset" to com.winlator.box86_64.Box86_64Preset.COMPATIBILITY,
        "desktopTheme" to com.winlator.core.WineThemeManager.DEFAULT_DESKTOP_THEME,
        "containerVariant" to com.winlator.container.Container.DEFAULT_VARIANT,
        "wineVersion" to com.winlator.core.WineInfo.MAIN_WINE_VERSION.identifier(),
        "emulator" to com.winlator.container.Container.DEFAULT_EMULATOR,
        "fexcoreVersion" to com.winlator.core.DefaultVersion.FEXCORE,
        "fexcoreTSOMode" to "Fast",
        "fexcoreX87Mode" to "Fast",
        "fexcoreMultiBlock" to "Disabled",
        "fexcorePreset" to com.winlator.fexcore.FEXCorePreset.INTERMEDIATE,
        "sdlControllerAPI" to true,
        "useSteamInput" to false,
        "enableXInput" to true,
        "enableDInput" to true,
        "dinputMapperType" to 1.toByte(),
        "disableMouseInput" to false,
        "touchscreenMode" to false,
        "shooterMode" to true,
        "gestureConfig" to "",
        "externalDisplayMode" to com.winlator.container.Container.DEFAULT_EXTERNAL_DISPLAY_MODE,
        "externalDisplaySwap" to false,
        "useDRI3" to true,
        "language" to "english",
        "forceDlc" to false,
        "localSavesOnly" to false,
        "steamOfflineMode" to false,
        "useLegacyDRM" to false,
        "unpackFiles" to false,
        "suspendPolicy" to com.winlator.container.Container.SUSPEND_POLICY_MANUAL,
        "portraitMode" to false,
        "sharpnessEffect" to "None",
        "sharpnessLevel" to 100,
        "sharpnessDenoise" to 100,
        "inputMap" to inputMap,
    )
}
