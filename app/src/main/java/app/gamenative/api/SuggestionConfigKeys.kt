package app.gamenative.api

import com.winlator.container.ContainerData

object SuggestionConfigKeys {
    enum class ConfigValueType { STRING, BOOLEAN, INT }

    val BEST_CONFIG_KEYS: Map<String, ConfigValueType> = linkedMapOf(
        "executablePath" to ConfigValueType.STRING,
        "graphicsDriver" to ConfigValueType.STRING,
        "graphicsDriverVersion" to ConfigValueType.STRING,
        "graphicsDriverConfig" to ConfigValueType.STRING,
        "dxwrapper" to ConfigValueType.STRING,
        "dxwrapperConfig" to ConfigValueType.STRING,
        "execArgs" to ConfigValueType.STRING,
        "startupSelection" to ConfigValueType.INT,
        "box64Version" to ConfigValueType.STRING,
        "box64Preset" to ConfigValueType.STRING,
        "containerVariant" to ConfigValueType.STRING,
        "wineVersion" to ConfigValueType.STRING,
        "emulator" to ConfigValueType.STRING,
        "fexcoreVersion" to ConfigValueType.STRING,
        "fexcoreTSOMode" to ConfigValueType.STRING,
        "fexcoreX87Mode" to ConfigValueType.STRING,
        "fexcoreMultiBlock" to ConfigValueType.STRING,
        "fexcorePreset" to ConfigValueType.STRING,
        "useLegacyDRM" to ConfigValueType.BOOLEAN,
        "steamOfflineMode" to ConfigValueType.BOOLEAN,
        "loadMods" to ConfigValueType.BOOLEAN,
        "epicOfflineMode" to ConfigValueType.BOOLEAN,
        "unpackFiles" to ConfigValueType.BOOLEAN,
        "suspendPolicy" to ConfigValueType.STRING,
        "envVars" to ConfigValueType.STRING,
        "cpuList" to ConfigValueType.STRING,
        "cpuListWoW64" to ConfigValueType.STRING,
        "audioDriver" to ConfigValueType.STRING,
        "wincomponents" to ConfigValueType.STRING,
        "videoMemorySize" to ConfigValueType.STRING,
        "launchBionicSteam" to ConfigValueType.BOOLEAN,
        "launchRealSteam" to ConfigValueType.BOOLEAN,
        "steamType" to ConfigValueType.STRING,
    )

    val APPLICABLE_CONFIG_KEYS: Map<String, ConfigValueType> = BEST_CONFIG_KEYS

    fun configValueOf(data: ContainerData, key: String): String? = when (key) {
        "executablePath" -> data.executablePath
        "graphicsDriver" -> data.graphicsDriver
        "graphicsDriverVersion" -> data.graphicsDriverVersion
        "graphicsDriverConfig" -> data.graphicsDriverConfig
        "dxwrapper" -> data.dxwrapper
        "dxwrapperConfig" -> data.dxwrapperConfig
        "execArgs" -> data.execArgs
        "startupSelection" -> data.startupSelection.toString()
        "box64Version" -> data.box64Version
        "box64Preset" -> data.box64Preset
        "containerVariant" -> data.containerVariant
        "wineVersion" -> data.wineVersion
        "emulator" -> data.emulator
        "fexcoreVersion" -> data.fexcoreVersion
        "fexcoreTSOMode" -> data.fexcoreTSOMode
        "fexcoreX87Mode" -> data.fexcoreX87Mode
        "fexcoreMultiBlock" -> data.fexcoreMultiBlock
        "fexcorePreset" -> data.fexcorePreset
        "useLegacyDRM" -> data.useLegacyDRM.toString()
        "steamOfflineMode" -> data.steamOfflineMode.toString()
        "loadMods" -> data.loadMods.toString()
        "epicOfflineMode" -> data.epicOfflineMode.toString()
        "unpackFiles" -> data.unpackFiles.toString()
        "suspendPolicy" -> data.suspendPolicy
        "envVars" -> data.envVars
        "cpuList" -> data.cpuList
        "cpuListWoW64" -> data.cpuListWoW64
        "audioDriver" -> data.audioDriver
        "wincomponents" -> data.wincomponents
        "videoMemorySize" -> data.videoMemorySize
        "launchBionicSteam" -> data.launchBionicSteam.toString()
        "launchRealSteam" -> data.launchRealSteam.toString()
        "steamType" -> data.steamType
        else -> null
    }

    fun coerceConfigValue(type: ConfigValueType, value: Any?): Any? = when (type) {
        ConfigValueType.STRING -> configString(value)
        ConfigValueType.BOOLEAN -> configBoolean(value)
        ConfigValueType.INT -> configInt(value)
    }

    private fun configString(value: Any?): String? = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun configBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun configInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
