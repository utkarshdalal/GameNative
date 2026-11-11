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
    const val GAMEMAKER = "gamemaker"
    const val RE_ENGINE = "re_engine"
    const val FROSTBITE = "frostbite"
    const val CRYENGINE = "cryengine"

    private const val MAX_DEPTH = 3
    private const val MAX_DIRS = 500
    private const val CONFIG_LOCAL = "config.local"
    private const val APP_INFO = "app.info"
    private const val MAX_APP_INFO_BYTES = 4096L
    private const val MAX_META_LENGTH = 128

    /** Most telling first: a Unity-looking _Data folder or a stray .pck says less than an Unreal shipping exe. */
    private val ORDER = listOf(UNREAL, UNITY, SOURCE, RE_ENGINE, FROSTBITE, CRYENGINE, GAMEMAKER, GODOT, IDTECH)

    private val SHIPPING_EXE = Regex("^.+-(Win64|WinGDK)-Shipping\\.exe$", RegexOption.IGNORE_CASE)
    private val CRY_DLL = Regex("^cry(system|engine|renderd3d1[12]|action|game)\\.dll$")

    /** Unity's app.info: the company and product names the game was built with. */
    data class Meta(val company: String?, val product: String?)

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
        return ORDER.firstOrNull { it in found }
    }

    private fun look(dir: File, entries: Array<File>, found: MutableSet<String>) {
        val files = entries.filter { it.isFile }.map { it.name.lowercase() }
        val dirs = entries.filter { it.isDirectory }
        val name = dir.name.lowercase()
        if (files.any { SHIPPING_EXE.matches(it) }) found += UNREAL
        if (name == "engine" && dirs.any { it.name.equals("Binaries", ignoreCase = true) }) found += UNREAL
        if ("unityplayer.dll" in files || "gameassembly.dll" in files || dirs.any { it.name.endsWith("_Data", ignoreCase = true) }) found += UNITY
        if ("hl2.exe" in files || (name == "bin" && "engine.dll" in files)) found += SOURCE
        if (files.any { it.endsWith(".pck") } && files.any { it.endsWith(".exe") }) found += GODOT
        if (files.any { it.endsWith(CONFIG_LOCAL) && it.length > CONFIG_LOCAL.length } || (name == "base" && files.any { it.endsWith(".resources") })) found += IDTECH
        if ("data.win" in files) found += GAMEMAKER
        if ("re_chunk_000.pak" in files) found += RE_ENGINE
        if (name == "data" && ("initfs_win32" in files || "layout.toc" in files)) found += FROSTBITE
        if (files.any { CRY_DLL.matches(it) } || ("system.cfg" in files && dirs.any { it.name.equals("Bin64", ignoreCase = true) })) found += CRYENGINE
    }

    /**
     * Company and product from a Unity game's `<Game>_Data/app.info` in the install folder or one folder down; null
     * when there is none or it names neither.
     */
    fun unityMeta(install: File?): Meta? {
        if (install == null || !install.isDirectory) return null
        val top = install.listFiles()?.filter { it.isDirectory }.orEmpty()
        val candidates = top.filter { it.name.endsWith("_Data", ignoreCase = true) } +
            top.flatMap { d -> d.listFiles()?.filter { it.isDirectory && it.name.endsWith("_Data", ignoreCase = true) }.orEmpty() }
        for (data in candidates) {
            val info = File(data, APP_INFO).takeIf { it.isFile && it.length() in 1..MAX_APP_INFO_BYTES } ?: continue
            parseAppInfo(runCatching { info.readText() }.getOrNull() ?: continue)?.let { return it }
        }
        return null
    }

    /** app.info is two lines: the company, then the product. */
    fun parseAppInfo(text: String): Meta? {
        val lines = text.lines().map { it.trim().take(MAX_META_LENGTH) }
        val company = lines.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val product = lines.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return if (company == null && product == null) null else Meta(company, product)
    }
}
