package app.gamenative.optimize

import java.io.File

/**
 * A cheap guess at a game's engine from its install folder, for titles the server does not know. Looks at most
 * [MAX_DEPTH] folders down and at most [MAX_DIRS] folders; null when nothing tells.
 */
object EngineHint {
    const val UNREAL = "unreal"
    const val UNITY = "unity"
    const val SOURCE = "source"
    const val GODOT = "godot"
    const val IDTECH = "idtech"

    private const val MAX_DEPTH = 3
    private const val MAX_DIRS = 500
    private const val CONFIG_LOCAL = "config.local"

    private val SHIPPING_EXE = Regex("^.+-(Win64|WinGDK)-Shipping\\.exe$", RegexOption.IGNORE_CASE)

    fun detect(install: File?): String? {
        if (install == null || !install.isDirectory) return null
        val found = mutableSetOf<String>()
        var level = listOf(install)
        var visited = 0
        for (depth in 0..MAX_DEPTH) {
            val next = mutableListOf<File>()
            for (dir in level) {
                if (visited++ >= MAX_DIRS) break
                val entries = dir.listFiles() ?: continue
                look(dir, entries, found)
                if (depth < MAX_DEPTH) next += entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            }
            if (next.isEmpty() || visited >= MAX_DIRS) break
            level = next
        }
        return listOf(UNREAL, UNITY, SOURCE, GODOT, IDTECH).firstOrNull { it in found }
    }

    private fun look(dir: File, entries: Array<File>, found: MutableSet<String>) {
        val files = entries.filter { it.isFile }.map { it.name.lowercase() }
        val dirs = entries.filter { it.isDirectory }
        val name = dir.name.lowercase()
        if (files.any { SHIPPING_EXE.matches(it) }) found += UNREAL
        if (name == "engine" && dirs.any { it.name.equals("Binaries", ignoreCase = true) }) found += UNREAL
        if ("unityplayer.dll" in files || dirs.any { it.name.endsWith("_Data", ignoreCase = true) }) found += UNITY
        if ("hl2.exe" in files || (name == "bin" && "engine.dll" in files)) found += SOURCE
        if (files.any { it.endsWith(".pck") } && files.any { it.endsWith(".exe") }) found += GODOT
        if (files.any { it.endsWith(CONFIG_LOCAL) && it.length > CONFIG_LOCAL.length } || (name == "base" && files.any { it.endsWith(".resources") })) found += IDTECH
    }
}
