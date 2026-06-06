package app.gamenative.utils.installscript

data class RegistryValues(
    val strings: Map<String, String> = emptyMap(),
    val dwords: Map<String, Long> = emptyMap(),
)

data class RegistryAction(
    val keyPath: String,
    val values: RegistryValues,
    val languageOverrides: Map<String, RegistryValues> = emptyMap(),
)

data class OSRequirement(
    val is64BitWindows: Boolean? = null,
    val osType: String? = null,
)

data class RunProcessAction(
    val name: String,
    val process: String,
    val command: String = "",
    val hasRunKey: String? = null,
    val noCleanUp: Boolean = false,
    val minimumHasRunValue: Int = 0,
    val requirementOS: OSRequirement? = null,
    val asCurrentUser: Boolean = false,
)

data class InstallScript(
    val sourcePath: String,
    val registryActions: List<RegistryAction> = emptyList(),
    val runProcessActions: List<RunProcessAction> = emptyList(),
)
