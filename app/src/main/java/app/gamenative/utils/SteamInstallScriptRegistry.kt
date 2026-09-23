package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber
import java.io.File

/**
 * Applies the Registry section of a Steam game's install script (installscript.vdf) by
 * writing the values straight into the container's Wine prefix, the same way game fixes do.
 * Steam's Windows client is a 32-bit process, so HKLM\Software keys land under Wow6432Node.
 *
 * Completion is tracked by a marker in the game directory (cleared by verify) and a stamp in
 * the prefix, so the values are re-applied when either the install or the prefix is recreated.
 */
object SteamInstallScriptRegistry {
    enum class Hive { HKLM, HKCU }
    enum class ValueType { STRING, EXPAND_STRING, DWORD }

    data class Entry(
        val hive: Hive,
        val key: String,
        val name: String?,
        val type: ValueType,
        val data: String,
    )

    private const val GAME_DRIVE_ROOT = "A:\\"
    private const val DEFAULT_LANGUAGE = "english"
    private const val USER_PROFILE = "C:\\users\\${ImageFs.USER}"
    private val TOKEN_PATTERN = Regex("(?i)%([A-Z_]+)%([\\\\/]?)")

    private fun tokens(installDir: String): Map<String, String> = mapOf(
        "INSTALLDIR" to installDir,
        "ROOTDRIVE" to installDir.substringBefore(':'),
        "WINDIR" to "C:\\windows",
        "APPDATA" to "$USER_PROFILE\\AppData\\Roaming",
        "LOCALAPPDATA" to "$USER_PROFILE\\AppData\\Local",
        "USER_MYDOCS" to "$USER_PROFILE\\Documents",
        "COMMON_MYDOCS" to "C:\\users\\Public\\Documents",
        "STEAMPATH" to "C:\\Program Files (x86)\\Steam",
    )

    fun applyForLaunch(container: Container, appId: String) {
        if (ContainerUtils.extractGameSourceFromContainerId(appId) != GameSource.STEAM) return
        val numericAppId = ContainerUtils.extractGameIdFromContainerId(appId) ?: return
        val gameDir = PreInstallSteps.getGameDir(container) ?: return
        val app = SteamService.getAppInfoOf(numericAppId) ?: return
        val scriptName = app.installScript.trim()
        if (scriptName.isEmpty()) return

        val prefixDir = File(container.rootDir, ".wine")
        val prefixStamp = File(prefixDir, "${Marker.STEAM_INSTALL_SCRIPT_INSTALLED.fileName}_$numericAppId")
        if (MarkerUtils.hasMarker(gameDir.absolutePath, Marker.STEAM_INSTALL_SCRIPT_INSTALLED) && prefixStamp.isFile) return

        val scriptFile = resolveChildCaseInsensitive(gameDir, scriptName)
        if (scriptFile == null) {
            Timber.w("Install script $scriptName not found in ${gameDir.absolutePath}")
            return
        }
        val entries = parse(scriptFile.readText(), GAME_DRIVE_ROOT, container.language)
        write(prefixDir, entries)
        Timber.i("Applied ${entries.size} install-script registry values for app $numericAppId")

        MarkerUtils.addMarker(gameDir.absolutePath, Marker.STEAM_INSTALL_SCRIPT_INSTALLED)
        prefixDir.mkdirs()
        runCatching { prefixStamp.createNewFile() }
    }

    internal fun parse(vdf: String, installDir: String, language: String = DEFAULT_LANGUAGE): List<Entry> {
        val root = runCatching { KeyValue.loadFromString(vdf) }.getOrNull() ?: return emptyList()
        val registry = root["InstallScript"]["Registry"].takeUnless { it === KeyValue.INVALID }
            ?: root["Registry"].takeUnless { it === KeyValue.INVALID }
            ?: return emptyList()

        val tokens = tokens(installDir)
        val entries = mutableListOf<Entry>()
        for (key in registry.children) {
            val split = splitHive(expandTokens(key.name.orEmpty(), tokens))
            if (split == null) {
                Timber.d("Skipping unsupported registry key ${key.name}")
                continue
            }
            val (hive, path) = split
            val redirected = if (hive == Hive.HKLM) redirectTo32BitView(path) else path
            addValues(entries, hive, redirected, key["string"], ValueType.STRING, tokens, language)
            addValues(entries, hive, redirected, key["expandstring"], ValueType.EXPAND_STRING, tokens, language)
            addValues(entries, hive, redirected, key["dword"], ValueType.DWORD, tokens, language)
        }
        return entries
    }

    internal fun write(prefixDir: File, entries: List<Entry>) {
        for (hive in Hive.entries) {
            val hiveEntries = entries.filter { it.hive == hive }
            if (hiveEntries.isEmpty()) continue
            val regFile = File(prefixDir, if (hive == Hive.HKLM) "system.reg" else "user.reg")
            if (!regFile.isFile) {
                regFile.parentFile?.mkdirs()
                regFile.writeText("WINE REGISTRY Version 2\n\n")
            }
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for (entry in hiveEntries) {
                    when (entry.type) {
                        ValueType.STRING -> editor.setStringValue(entry.key, entry.name, entry.data)
                        ValueType.EXPAND_STRING -> editor.setExpandStringValue(entry.key, entry.name, entry.data)
                        ValueType.DWORD -> {
                            val value = runCatching { java.lang.Long.decode(entry.data).toInt() }.getOrNull()
                            if (value == null) {
                                Timber.w("Skipping non-numeric dword ${entry.key}\\${entry.name}=${entry.data}")
                            } else {
                                editor.setDwordValue(entry.key, entry.name, value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addValues(
        entries: MutableList<Entry>,
        hive: Hive,
        key: String,
        values: KeyValue,
        type: ValueType,
        tokens: Map<String, String>,
        language: String,
    ) {
        if (values === KeyValue.INVALID) return
        val (languageBlocks, plainValues) = values.children.partition { it.value == null && it.children.isNotEmpty() }
        val selectedBlock = languageBlocks.firstOrNull { it.name.equals(language, ignoreCase = true) }
            ?: languageBlocks.firstOrNull { it.name.equals(DEFAULT_LANGUAGE, ignoreCase = true) }
        for (value in plainValues + selectedBlock?.children.orEmpty()) {
            entries += Entry(
                hive = hive,
                key = key,
                name = value.name.orEmpty().takeUnless { it.isEmpty() || it.equals("(Default)", ignoreCase = true) },
                type = type,
                data = expandTokens(value.value.orEmpty(), tokens),
            )
        }
    }

    private fun splitHive(path: String): Pair<Hive, String>? {
        val separator = path.indexOf('\\')
        val hiveName = if (separator < 0) path else path.substring(0, separator)
        val rest = if (separator < 0) "" else path.substring(separator + 1).trim('\\')
        if (rest.isEmpty()) return null
        val hive = when (hiveName.uppercase()) {
            "HKEY_LOCAL_MACHINE", "HKLM" -> Hive.HKLM
            "HKEY_CURRENT_USER", "HKCU" -> Hive.HKCU
            else -> return null
        }
        return hive to rest
    }

    private fun redirectTo32BitView(path: String): String {
        val segments = path.split('\\')
        if (segments.size < 2 || !segments[0].equals("Software", ignoreCase = true)) return path
        if (segments[1].equals("Wow6432Node", ignoreCase = true)) return path
        return (listOf(segments[0], "Wow6432Node") + segments.drop(1)).joinToString("\\")
    }

    private fun expandTokens(value: String, tokens: Map<String, String>): String =
        TOKEN_PATTERN.replace(value) { match ->
            val replacement = tokens[match.groupValues[1].uppercase()] ?: return@replace match.value
            val separator = match.groupValues[2]
            if (separator.isEmpty()) replacement else replacement.trimEnd('\\', '/') + "\\"
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
}
