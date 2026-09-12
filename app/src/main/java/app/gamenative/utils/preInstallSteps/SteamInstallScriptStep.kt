package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import com.winlator.container.Container
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File

/** Applies the Registry section of the install-script VDF shipped by Steam games. */
object SteamInstallScriptStep : PreInstallStep {
    override val marker: Marker = Marker.STEAM_INSTALL_SCRIPT_INSTALLED

    internal var appInfoProvider: (Int) -> SteamApp? = SteamService::getAppInfoOf

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean = gameSource == GameSource.STEAM

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        if (gameSource != GameSource.STEAM) return null
        val numericAppId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }.getOrNull()
            ?: return null
        val app = appInfoProvider(numericAppId) ?: return null
        val scriptName = app.installScript.trim()
        if (scriptName.isEmpty()) return null

        val prefixStamp = File(container.rootDir, ".wine/.steam_install_script_$numericAppId")
        if (MarkerUtils.hasMarker(gameDirPath, marker) && prefixStamp.isFile) return null

        val scriptFile = resolveChildCaseInsensitive(gameDir, scriptName) ?: return null
        val root = runCatching { KeyValue.loadFromString(scriptFile.readText()) }.getOrNull() ?: return null
        val registry = root["InstallScript"]["Registry"].takeUnless { it === KeyValue.INVALID }
            ?: root["Registry"].takeUnless { it === KeyValue.INVALID }
            ?: return null

        val commands = buildRegistryCommands(registry, "A:\\")
        if (commands.isEmpty()) {
            markDone(gameDirPath, prefixStamp)
            return null
        }

        prefixStamp.parentFile?.mkdirs()
        runCatching { prefixStamp.createNewFile() }
        return commands.joinToString(" & ")
    }

    internal fun buildRegistryCommands(registry: KeyValue, installDir: String): List<String> {
        val commands = mutableListOf<String>()
        for (key in registry.children) {
            val keyName = normalizeHive(expandTokens(key.name, installDir)) ?: continue
            addValues(commands, keyName, key["string"], "REG_SZ", installDir)
            addValues(commands, keyName, key["expandstring"], "REG_EXPAND_SZ", installDir)
            addValues(commands, keyName, key["dword"], "REG_DWORD", installDir)
        }
        return commands
    }

    private fun addValues(
        commands: MutableList<String>,
        keyName: String,
        values: KeyValue,
        type: String,
        installDir: String,
    ) {
        if (values === KeyValue.INVALID) return
        for (value in values.children) {
            val safeKey = escapeCmdArgument(keyName) ?: continue
            val name = escapeCmdArgument(value.name) ?: continue
            val data = escapeCmdArgument(expandTokens(value.value.orEmpty(), installDir)) ?: continue
            // Steam's Windows client applies install scripts as a 32-bit process.
            commands += "reg add \"$safeKey\" /v \"$name\" /t $type /d \"$data\" /f /reg:32"
        }
    }

    private fun normalizeHive(path: String): String? {
        val separator = path.indexOf('\\')
        val hive = if (separator < 0) path else path.substring(0, separator)
        val rest = if (separator < 0) "" else path.substring(separator)
        val shortHive = when (hive.uppercase()) {
            "HKEY_LOCAL_MACHINE", "HKLM" -> "HKLM"
            "HKEY_CURRENT_USER", "HKCU" -> "HKCU"
            "HKEY_CLASSES_ROOT", "HKCR" -> "HKCR"
            "HKEY_USERS", "HKU" -> "HKU"
            else -> return null
        }
        return shortHive + rest
    }

    private fun expandTokens(value: String, installDir: String): String {
        val normalizedDir = installDir.trimEnd('\\', '/')
        return value
            .replace(Regex("(?i)%INSTALLDIR%[\\\\/]")) { "$normalizedDir\\" }
            .replace("%INSTALLDIR%", installDir, ignoreCase = true)
    }

    /** Keep parsed VDF data inside a quoted cmd.exe argument. */
    private fun escapeCmdArgument(value: String): String? {
        if (value.any { it == '\"' || it == '\r' || it == '\n' }) return null
        return value.replace("%", "%%")
    }

    private fun resolveChildCaseInsensitive(root: File, relativePath: String): File? {
        var current = root
        for (segment in relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            if (segment == "." || segment == "..") return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current.takeIf { it.isFile }
    }

    private fun markDone(gameDirPath: String, prefixStamp: File) {
        MarkerUtils.addMarker(gameDirPath, marker)
        prefixStamp.parentFile?.mkdirs()
        runCatching { prefixStamp.createNewFile() }
    }

    internal fun resetForTests() {
        appInfoProvider = SteamService::getAppInfoOf
    }
}
