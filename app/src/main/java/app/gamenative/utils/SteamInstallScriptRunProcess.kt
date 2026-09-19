package app.gamenative.utils

import com.winlator.core.WineRegistryEditor
import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber
import java.io.File

object SteamInstallScriptRunProcess {
    data class Entry(
        val winPath: String,
        val args: String,
        val hostFile: File,
        val hasRunKey: String = syntheticHasRunKey(winPath),
    ) {
        val exeName: String get() = winPath.substringAfterLast('\\')
        val commandLine: String
            get() {
                val exe = if (winPath.contains(' ')) "\"$winPath\"" else winPath
                return if (args.isBlank()) exe else "$exe ${args.trim()}"
            }
    }

    private const val GAME_DRIVE_ROOT = "A:\\"
    private const val SCRIPT_NAME = "installscript.vdf"
    private const val SYNTHETIC_KEY_ROOT = "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\GameNative"
    private val PROCESS_KEY = Regex("(?i)^process\\s*(\\d+)$")

    fun syntheticHasRunKey(winPath: String): String =
        SYNTHETIC_KEY_ROOT + "\\" + winPath.substringAfter(':').trim('\\')

    fun hasRun(prefixDir: File, entry: Entry): Boolean {
        val systemReg = File(prefixDir, "system.reg")
        if (!systemReg.isFile) return false
        return WineRegistryEditor(systemReg).use { it.hasKey(entry.hasRunKey) }
    }

    fun markRun(prefixDir: File, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val systemReg = File(prefixDir, "system.reg")
        if (!systemReg.isFile) {
            prefixDir.mkdirs()
            systemReg.writeText("WINE REGISTRY Version 2\n\n")
        }
        WineRegistryEditor(systemReg).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            for (entry in entries) editor.setDwordValue(entry.hasRunKey, "Installed", 1)
        }
    }

    fun entries(gameDir: File, installDir: String = GAME_DRIVE_ROOT): List<Entry> =
        scripts(gameDir).flatMap { script ->
            runCatching { parse(script.readText(), gameDir, installDir) }
                .onFailure { Timber.w(it, "Failed to read ${script.absolutePath}") }
                .getOrDefault(emptyList())
        }.distinctBy { it.winPath.lowercase() }

    internal fun parse(vdf: String, gameDir: File, installDir: String = GAME_DRIVE_ROOT): List<Entry> {
        val root = runCatching { KeyValue.loadFromString(vdf) }.getOrNull() ?: return emptyList()
        val runProcess = root["InstallScript"]["Run Process"].takeUnless { it === KeyValue.INVALID }
            ?: root["Run Process"].takeUnless { it === KeyValue.INVALID }
            ?: return emptyList()
        val tokens = SteamInstallScriptRegistry.tokens(installDir)
        val entries = mutableListOf<Entry>()
        for (block in runProcess.children) {
            for (child in block.children) {
                val index = PROCESS_KEY.find(child.name.orEmpty())?.groupValues?.get(1) ?: continue
                val winPath = SteamInstallScriptRegistry.expandTokens(child.value.orEmpty().trim(), tokens)
                if (!winPath.startsWith(installDir, ignoreCase = true)) {
                    Timber.d("Skipping run-process entry outside the install dir: $winPath")
                    continue
                }
                val relative = winPath.substring(installDir.length).replace('\\', '/')
                val hostFile = resolveCaseInsensitive(gameDir, relative) ?: continue
                val args = SteamInstallScriptRegistry.expandTokens(block["command $index"].value.orEmpty(), tokens)
                val hasRunKey = block["HasRunKey"].value?.let { scriptHasRunKey(it) } ?: syntheticHasRunKey(winPath)
                entries += Entry(winPath, args, hostFile, hasRunKey)
            }
        }
        return entries
    }

    private fun scriptHasRunKey(raw: String): String? {
        val (hive, path) = SteamInstallScriptRegistry.splitHive(raw.trim()) ?: return null
        if (hive != SteamInstallScriptRegistry.Hive.HKLM) return null
        return SteamInstallScriptRegistry.redirectTo32BitView(path)
    }

    private fun scripts(gameDir: File): List<File> {
        val root = gameDir.listFiles()?.filter { it.isFile && it.name.equals(SCRIPT_NAME, ignoreCase = true) }.orEmpty()
        val redist = gameDir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("_CommonRedist", ignoreCase = true) }
            ?.walkTopDown()?.filter { it.isFile && it.name.equals(SCRIPT_NAME, ignoreCase = true) }?.toList().orEmpty()
        return root + redist.sortedBy { it.path }
    }

    private fun resolveCaseInsensitive(root: File, relativePath: String): File? {
        var current = root
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            if (segment == "." || segment == "..") return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        return current.takeIf { it.isFile }
    }
}
