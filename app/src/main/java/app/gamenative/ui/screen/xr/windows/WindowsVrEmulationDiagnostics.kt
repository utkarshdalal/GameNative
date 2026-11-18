package app.gamenative.ui.screen.xr.windows

import com.winlator.container.Container
import org.json.JSONObject

object WindowsVrEmulationDiagnostics {
    private val keys = listOf(
        "graphicsDriver",
        "graphicsDriverVersion",
        "graphicsDriverConfig",
        "dxwrapper",
        "dxwrapperConfig",
        "displayRendererMode",
        "containerVariant",
        "wineVersion",
        "wow64Mode",
        "emulator",
        "fexcoreVersion",
        "fexcorePreset",
        "box64Version",
        "box64Preset",
        "box86Version",
        "box86Preset",
        "cpuList",
        "cpuListWoW64",
        "startupSelection",
        "steamType",
    )

    fun snapshot(container: Container): String {
        val configFile = container.configFile
        val persisted = runCatching { JSONObject(configFile.readText()) }.getOrNull()
        return buildString {
            appendLine("GameNativeVR emulation settings at immersive start:")
            appendLine(
                "  configFile=${configFile.absolutePath} exists=${configFile.isFile} " +
                    "bytes=${if (configFile.isFile) configFile.length() else 0} " +
                    "modified=${if (configFile.isFile) configFile.lastModified() else 0}",
            )
            appendLine("  saved: ${keys.joinToString(" ") { "$it=${persisted.value(it)}" }}")
            appendLine("  loaded: ${loaded(container)}")
        }.trimEnd()
    }

    fun effective(container: Container): String =
        "wine=${container.wineVersion} graphics=${container.graphicsDriver} " +
            "graphicsConfig=${container.graphicsDriverConfig.clean()} wrapper=${container.dxWrapper} " +
            "wrapperConfig=${container.dxWrapperConfig.clean()} variant=${container.containerVariant} " +
            "wow64=${container.isWoW64Mode} emulator=${container.emulator} " +
            "fex=${container.fexCoreVersion}/${container.fexCorePreset} " +
            "box64=${container.box64Version}/${container.box64Preset} " +
            "box86=${container.box86Version}/${container.box86Preset} " +
            "cpu=${container.cpuList} wow64Cpu=${container.cpuListWoW64} " +
            "startup=${container.startupSelection} steam=${container.steamType} args=${container.execArgs.clean()}"

    private fun loaded(container: Container): String =
        "graphicsDriver=${container.graphicsDriver} graphicsDriverVersion=${container.graphicsDriverVersion} " +
            "graphicsDriverConfig=${container.graphicsDriverConfig.clean()} dxwrapper=${container.dxWrapper} " +
            "dxwrapperConfig=${container.dxWrapperConfig.clean()} displayRendererMode=${container.displayRenderer} " +
            "containerVariant=${container.containerVariant} wineVersion=${container.wineVersion} " +
            "wow64Mode=${container.isWoW64Mode} emulator=${container.emulator} " +
            "fexcoreVersion=${container.fexCoreVersion} fexcorePreset=${container.fexCorePreset} " +
            "box64Version=${container.box64Version} box64Preset=${container.box64Preset} " +
            "box86Version=${container.box86Version} box86Preset=${container.box86Preset} " +
            "cpuList=${container.cpuList} cpuListWoW64=${container.cpuListWoW64} " +
            "startupSelection=${container.startupSelection} steamType=${container.steamType}"

    private fun String.clean(): String = replace('\n', ' ').replace('\r', ' ')

    private fun JSONObject?.value(key: String): String {
        if (this == null) return "<unreadable>"
        if (!has(key) || isNull(key)) return "<omitted/default>"
        return opt(key)?.toString()?.clean() ?: "<null>"
    }
}
