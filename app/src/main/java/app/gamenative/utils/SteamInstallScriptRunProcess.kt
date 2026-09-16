package app.gamenative.utils

import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber
import java.io.File

object SteamInstallScriptRunProcess {
    data class Entry(
        val winPath: String,
        val args: String,
        val hostFile: File,
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
    private val PROCESS_KEY = Regex("(?i)^process\\s*(\\d+)$")

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
                entries += Entry(winPath, args, hostFile)
            }
        }
        return entries
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
