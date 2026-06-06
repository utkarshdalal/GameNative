package app.gamenative.utils.installscript

import app.gamenative.data.AppInfo
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.DepotManifest
import java.io.File
import java.util.EnumSet
import timber.log.Timber

object InstallScriptExecutor {

    private const val CLAIMED_OS_TYPE = "10"
    private const val EXIT_CODE_FILE = "installscript_exit"

    data class RunProcessCommand(
        val executable: String,
        val hasRunKey: String?,
    )

    fun collectScripts(
        steamApp: SteamApp,
        appInfo: AppInfo,
        gameDir: File,
        installDir: String,
        language: String,
        appId: Int,
    ): List<InstallScript> {
        val appDirPath = SteamService.getAppDirPath(appId)
        val downloadedDepotIds = appInfo.downloadedDepots.toSet()
        val installedBranch = SteamService.getInstalledApp(appId)?.branch ?: "public"

        val scriptPaths = mutableSetOf<String>()

        for ((depotId, depot) in steamApp.depots) {
            if (depotId !in downloadedDepotIds) continue

            val mi = depot.manifests[installedBranch]
                ?: depot.encryptedManifests[installedBranch]
                ?: depot.manifests["public"]
                ?: continue

            val manifestFile = "$appDirPath/.DepotDownloader/${depotId}_${mi.gid}.manifest"
            val manifest = try {
                DepotManifest.loadFromFile(manifestFile)
            } catch (e: Exception) {
                Timber.tag("InstallScript").d("Could not load manifest for depot $depotId: ${e.message}")
                continue
            } ?: continue

            manifest.files?.forEach { fileData ->
                if (isInstallScript(fileData.flags)) {
                    val path = fileData.fileName.toString().replace('\\', '/')
                    scriptPaths.add(path)
                    Timber.tag("InstallScript").d("Found install script in depot $depotId: $path")
                }
            }
        }

        return scriptPaths.mapNotNull { relativePath ->
            val scriptFile = File(gameDir, relativePath)
            if (!scriptFile.exists()) {
                Timber.tag("InstallScript").w("InstallScript file not found: ${scriptFile.absolutePath}")
                return@mapNotNull null
            }
            InstallScriptParser.parse(scriptFile, installDir, language)
        }
    }

    private fun isInstallScript(flags: Any): Boolean = when (flags) {
        is EnumSet<*> -> flags.contains(EDepotFileFlag.InstallScript)
        is Int -> (flags and EDepotFileFlag.InstallScript.code()) != 0
        is Long -> (flags and EDepotFileFlag.InstallScript.code().toLong()) != 0L
        else -> false
    }

    fun applyRegistryKeys(container: Container, scripts: List<InstallScript>, language: String) {
        val systemRegFile = File(container.rootDir, ".wine/system.reg")
        val userRegFile = File(container.rootDir, ".wine/user.reg")

        val systemActions = mutableListOf<Pair<String, RegistryValues>>()
        val userActions = mutableListOf<Pair<String, RegistryValues>>()

        for (script in scripts) {
            for (action in script.registryActions) {
                val mergedValues = mergeWithLanguage(action, language)
                val strippedKey = stripHivePrefix(action.keyPath)

                if (action.keyPath.startsWith("HKLM", ignoreCase = true) ||
                    action.keyPath.startsWith("HKEY_LOCAL_MACHINE", ignoreCase = true)
                ) {
                    systemActions.add(strippedKey to mergedValues)
                } else {
                    userActions.add(strippedKey to mergedValues)
                }
            }
        }

        if (systemActions.isNotEmpty() && systemRegFile.exists()) {
            writeRegistryValues(systemRegFile, systemActions)
        }
        if (userActions.isNotEmpty() && userRegFile.exists()) {
            writeRegistryValues(userRegFile, userActions)
        }
    }

    fun getRunProcessCommands(
        container: Container,
        scripts: List<InstallScript>,
        screenInfo: String,
        is64Bit: Boolean,
    ): List<RunProcessCommand> {
        val commands = mutableListOf<RunProcessCommand>()

        for (script in scripts) {
            for (action in script.runProcessActions) {
                if (!matchesOS(action.requirementOS, is64Bit)) continue

                val effectiveHasRunKey = action.hasRunKey
                    ?: "Software\\GameNative\\InstallScript\\${script.sourcePath.hashCode()}\\${action.name}"

                if (hasAlreadyRun(container, effectiveHasRunKey, action.minimumHasRunValue)) continue

                val cmdLine = if (action.command.isNotEmpty()) {
                    "${action.process} ${action.command}"
                } else {
                    action.process
                }
                val wrapped = wrapAsGuestExecutable(cmdLine, screenInfo)

                commands.add(RunProcessCommand(wrapped, effectiveHasRunKey))
            }
        }
        return commands
    }

    fun markRunProcessComplete(container: Container, hasRunKey: String) {
        val regFile = resolveRegFile(container, hasRunKey)
        if (!regFile.exists()) return
        try {
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                val keyPath = hasRunKey.substringBeforeLast("\\")
                val valueName = hasRunKey.substringAfterLast("\\")
                editor.setDwordValue(stripHivePrefix(keyPath), valueName, 1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark run process complete: $hasRunKey")
        }
    }

    internal fun mergeWithLanguage(action: RegistryAction, language: String): RegistryValues {
        val langOverride = action.languageOverrides[language.lowercase()]
            ?: return action.values
        return RegistryValues(
            strings = action.values.strings + langOverride.strings,
            dwords = action.values.dwords + langOverride.dwords,
        )
    }

    internal fun matchesOS(requirement: OSRequirement?, is64Bit: Boolean): Boolean {
        if (requirement == null) return true
        if (requirement.is64BitWindows != null && requirement.is64BitWindows != is64Bit) return false
        if (requirement.osType != null && requirement.osType != CLAIMED_OS_TYPE) return false
        return true
    }

    internal fun stripHivePrefix(keyPath: String): String {
        val prefixes = listOf("HKEY_LOCAL_MACHINE\\", "HKEY_CURRENT_USER\\", "HKLM\\", "HKCU\\")
        for (prefix in prefixes) {
            if (keyPath.startsWith(prefix, ignoreCase = true)) {
                return keyPath.substring(prefix.length)
            }
        }
        return keyPath
    }

    private fun isHkcuKey(keyPath: String): Boolean =
        keyPath.startsWith("HKCU", ignoreCase = true) ||
            keyPath.startsWith("HKEY_CURRENT_USER", ignoreCase = true)

    private fun resolveRegFile(container: Container, hasRunKey: String): File {
        val regName = if (isHkcuKey(hasRunKey)) ".wine/user.reg" else ".wine/system.reg"
        return File(container.rootDir, regName)
    }

    private fun hasAlreadyRun(container: Container, hasRunKey: String, minimumHasRunValue: Int): Boolean {
        val regFile = resolveRegFile(container, hasRunKey)
        if (!regFile.exists()) return false
        return try {
            WineRegistryEditor(regFile).use { editor ->
                val keyPath = hasRunKey.substringBeforeLast("\\")
                val valueName = hasRunKey.substringAfterLast("\\")
                val currentValue = editor.getDwordValue(
                    stripHivePrefix(keyPath), valueName, 0,
                ) ?: 0
                currentValue >= maxOf(1, minimumHasRunValue)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeRegistryValues(regFile: File, actions: List<Pair<String, RegistryValues>>) {
        try {
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for ((key, values) in actions) {
                    for ((name, value) in values.strings) {
                        val regName = if (name == "(Default)") null else name
                        editor.setStringValue(key, regName, value)
                    }
                    for ((name, value) in values.dwords) {
                        val regName = if (name == "(Default)") null else name
                        editor.setDwordValue(key, regName, value.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to write InstallScript registry values")
        }
    }

    fun readExitCode(container: Container): Int {
        val exitFile = File(container.rootDir, ".wine/drive_c/$EXIT_CODE_FILE")
        return try {
            val code = exitFile.readText().trim().toIntOrNull() ?: -1
            exitFile.delete()
            code
        } catch (e: Exception) {
            -1
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val exitFile = "C:\\$EXIT_CODE_FILE"
        val wrapped = "winhandler.exe cmd /c \"($cmdChain && echo 0 > $exitFile || echo 1 > $exitFile) " +
            "& taskkill /F /IM explorer.exe & wineserver -k\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }
}
