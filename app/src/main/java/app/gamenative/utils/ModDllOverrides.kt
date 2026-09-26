package app.gamenative.utils

import app.gamenative.mods.WindowsTargetNamespace
import java.io.File
import java.util.Locale

/** Recomputed for each launch. Automatic entries are never saved into the container's environment. */
object ModDllOverrides {
    const val SETTING = "autoModDllOverrides"
    private val proxies = listOf("dinput8", "winhttp", "version", "winmm", "dsound")
    private val frameworkDirectories = setOf("bepinex", "melonloader", "reframework")

    data class Result(val value: String, val added: List<String>, val preserved: List<String>)
    data class RegistryOverrides(val global: Map<String, String> = emptyMap(), val app: Map<String, String> = emptyMap()) {
        fun preserves(dll: String): Boolean {
            if (app.keys.any { WineDllOverrides.matches(it, dll) }) return true
            return global.any { (name, order) ->
                // WineUtils installs this default itself. A local dinput8 proxy must take priority over it.
                val isInputDefault = name.equals("dinput8", ignoreCase = true) &&
                    order.lowercase(Locale.ROOT).replace("builtin", "b").replace("native", "n").replace(" ", "") == "b,n"
                WineDllOverrides.matches(name, dll) && !isInputDefault
            }
        }
    }

    fun resolveExecutable(path: String, drives: Map<String, File>, driveC: File? = null): File? {
        val normalized = path.trim().removeSurrounding("\"").replace('\\', '/')
        if (!normalized.endsWith(".exe", ignoreCase = true)) return null
        val absolute = Regex("^[a-zA-Z]:/").containsMatchIn(normalized)
        val letter = if (absolute) normalized.substring(0, 1) else "A"
        val root = if (letter.equals("C", ignoreCase = true) && driveC != null) {
            driveC
        } else {
            drives.entries.firstOrNull { it.key.equals(letter, ignoreCase = true) }?.value
        }
        root ?: return null
        val relative = if (absolute) normalized.substring(3) else normalized.removePrefix("./")
        // Never infer mods from Wine's system directories or its built-in launcher programs.
        if (letter.equals("C", ignoreCase = true) && relative.startsWith("windows/", ignoreCase = true)) return null
        // Steam's common directory can be a symlink to a game outside drive_c. Resolve it just as Wine does.
        val executable = WindowsTargetNamespace(root).resolve(relative).takeIf { it.isValid }?.file
            ?.takeIf { it.isFile }?.canonicalFile ?: return null
        val windows = driveC?.let { File(it, "windows").canonicalFile }
        return executable.takeUnless { windows != null && it.toPath().startsWith(windows.toPath()) }
    }

    fun detect(executable: File?): List<String> {
        if (executable?.isFile != true) return emptyList()
        val files = executable.parentFile?.listFiles()?.groupBy { it.name.lowercase(Locale.ROOT) } ?: return emptyList()
        val hasFramework = files.any { (name, entries) ->
            val entry = entries.singleOrNull()
            entry != null &&
                (
                    (name in frameworkDirectories && entry.isDirectory) ||
                        ((name == "doorstop_config.ini" || name.endsWith(".asi")) && entry.isFile)
                    )
        }
        return proxies.filter { dll ->
            files["$dll.dll"]?.singleOrNull()?.isFile == true &&
                (dll == "dinput8" || dll == "winhttp" || hasFramework)
        }
    }

    fun merge(value: String, detected: List<String>, registry: RegistryOverrides = RegistryOverrides()): Result {
        val entries = WineDllOverrides.parse(value) ?: return Result(value, emptyList(), detected)
        val preserved = detected.filter { WineDllOverrides.mentions(entries, it) || registry.preserves(it) }
        val added = detected.filterNot { it in preserved }
        return Result(WineDllOverrides.appendMissing(value, added), added, preserved)
    }

    /** Read only the two relevant sections; do not open an editor or rewrite Wine's live registry. */
    fun readRegistry(file: File, executableName: String): RegistryOverrides {
        if (!file.exists()) return RegistryOverrides()
        val global = mutableMapOf<String, String>()
        val app = mutableMapOf<String, String>()
        val globalKey = "Software\\Wine\\DllOverrides"
        val appKey = "Software\\Wine\\AppDefaults\\$executableName\\DllOverrides"
        var target: MutableMap<String, String>? = null
        file.useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith('[')) {
                    val section = trimmed.substringAfter('[').substringBefore(']').replace("\\\\", "\\")
                    target = when {
                        section.equals(globalKey, ignoreCase = true) -> global
                        section.equals(appKey, ignoreCase = true) -> app
                        else -> null
                    }
                } else if (target != null && trimmed.startsWith('"') && trimmed.contains("\"=")) {
                    val name = trimmed.substring(1).substringBefore("\"=").replace("\\\\", "\\")
                    target?.set(name, trimmed.substringAfter("\"=").removeSurrounding("\""))
                }
            }
        }
        return RegistryOverrides(global, app)
    }
}
