package app.gamenative.workshop

import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Detects where a game expects Workshop mod content to be placed,
 * using static analysis of the game install (no runtime required).
 *
 * Heuristics used:
 *  1. Binary scan — extracts ASCII strings from game executables looking for
 *     mod-directory path references (AppData paths or install-relative paths)
 *  2. Install-dir scan — shallow-recursive search for known mod-directory names
 *  3. Config file scan — parses .ini/.cfg/.json/etc. for mod-path keys
 *  4. AppData fuzzy walk — searches Wine AppData for game-named directories
 *     containing mod subdirectories
 */
class WorkshopModPathDetector {

    enum class Confidence { LOW, MEDIUM, HIGH }

    data class DetectionResult(
        val strategy: WorkshopModPathStrategy,
        val confidence: Confidence,
        val reason: String,
        /** True when the game binary contains ISteamUGC / GetItemInstallInfo strings. */
        val stdSeen: Boolean = false,
    )

    companion object {
        const val TAG = "WorkshopModPathDetector"
        const val MAX_BINARY_SCAN_BYTES = 64L * 1024 * 1024
        const val MIN_STRING_LEN = 5
        const val MAX_CONFIG_BYTES = 1L * 1024 * 1024
        const val MAX_WALK_DEPTH = 3
        const val INSTALL_SCAN_DEPTH = 2

        val HIGH_CONFIDENCE_NAMES = setOf(
            "mods", "mod", "addons", "addon", "plugins", "plugin",
            "workshop_mods", "usermods", "user_mods",
            "modules", "module", "ugc",
            "resourcepacks", "resource_packs",
        )
        val MEDIUM_CONFIDENCE_NAMES = setOf(
            "levels", "level",
            "scenarios", "scenario", "missions", "mission",
            "workshop", "override", "gamedata",
            "maps",
            "packs", "outfits", "skins", "characters",
            "textures", "sounds", "audio",
        )
        val LOW_CONFIDENCE_NAMES = setOf(
            "custom", "usercontent", "user_content", "community",
            "packages", "package", "downloads", "download", "downloaded",
            "extras", "expansion", "expansions",
            "content", "assets", "data",
        )
        /** Union of all three confidence tiers — used for fast membership checks. */
        val ALL_MOD_DIR_NAMES = HIGH_CONFIDENCE_NAMES + MEDIUM_CONFIDENCE_NAMES + LOW_CONFIDENCE_NAMES

        val APPDATA_TOKENS = listOf(
            "%appdata%", "%localappdata%",
            "appdata\\roaming", "appdata\\local\\", "appdata\\locallow",
        )
        val BINARY_STANDARD_SIGNALS = listOf(
            "GetItemInstallInfo", "ISteamUGC", "SteamUGC()",
            "workshop\\content", "workshop/content",
        )
        val CONFIG_EXTENSIONS = setOf("ini", "cfg", "conf", "json", "jsonc", "xml", "txt", "yaml", "yml", "toml")
        val CONFIG_MOD_KEY_FRAGMENTS = listOf(
            "modpath", "mod_path", "modfolder", "mod_folder", "moddir", "mod_dir",
            "moddirectory", "mod_directory",
            "workshoppath", "workshop_path", "addonspath", "addons_path",
            "pluginpath", "plugin_path",
            "mappath", "map_path", "mapdir", "campaignpath", "campaign_path",
            "contentpath", "content_path",
            "localmods", "local_mods", "usermods", "user_mods",
            "custompath", "custom_path", "downloaddir", "download_dir",
        )
        val CONFIG_SKIP_FILES = setOf(
            "steam_appid.txt", "steam_api.ini", "unins000.dat",
            "changelog.txt", "readme.txt", "readme.md", "license.txt", "license.md", "credits.txt",
        )
        val CONFIG_SKIP_DIRS = setOf(
            "assets", "resources", "textures", "texture", "sounds", "sound", "audio",
            "shaders", "shader", "fonts", "font", "videos", "video", "movies",
            "localization", "locale", "lang",
        )
        val INSTALL_SKIP_DIRS = setOf(
            ".depotdownloader", "steam_settings", ".git", ".svn",
            "_commonredist", "__macosx", "directx",
            "engine", "binaries", "help",
        )
        val APPDATA_SKIP_DIRS = setOf(
            "Microsoft", "Windows", "Packages", "Temp", "Google", "Mozilla", "Apple", "Adobe", "Intel",
        )
    }

    private data class CandidateDir(val dir: File, val confidence: Confidence, val source: String)
    private data class AppDataRoot(val root: File, val envToken: String)

    // ── Public entry point ────────────────────────────────────────────────────

    fun detect(
        gameInstallDir: File,
        appDataRoaming: File,
        appDataLocal: File,
        appDataLocalLow: File,
        documentsMyGames: File = File(""),
        documentsDir: File = File(""),
        gameName: String,
        developerName: String = "",
    ): DetectionResult {
        Timber.tag(TAG).i("Detecting for '$gameName': ${gameInstallDir.absolutePath}")
        if (!gameInstallDir.exists() || !gameInstallDir.isDirectory)
            return defaultResult("Install directory does not exist")

        val appDataRoots = buildList<AppDataRoot> {
            if (appDataRoaming.isDirectory) add(AppDataRoot(appDataRoaming, "%APPDATA%"))
            if (appDataLocal.isDirectory) add(AppDataRoot(appDataLocal, "%LOCALAPPDATA%"))
            if (appDataLocalLow.isDirectory) add(AppDataRoot(appDataLocalLow, "LocalLow"))
            if (documentsMyGames.isDirectory) add(AppDataRoot(documentsMyGames, "Documents\\My Games"))
            if (documentsDir.isDirectory) add(AppDataRoot(documentsDir, "Documents"))
        }

        val found = LinkedHashMap<String, CandidateDir>()
        fun add(c: CandidateDir) {
            val key = runCatching { c.dir.canonicalPath }.getOrElse { c.dir.absolutePath }
            val existing = found[key]
            if (existing == null || c.confidence > existing.confidence) {
                found[key] = c
                Timber.tag(TAG).d("  +[${c.confidence}] ${c.dir.absolutePath} (${c.source})")
            }
        }

        val binaryResult = collectFromBinaries(gameInstallDir, appDataRoots)
        binaryResult.candidates.forEach { add(it) }
        collectModsDirectories(gameInstallDir, 0).forEach { add(it) }
        (listOf(gameInstallDir) + appDataRoots.map { it.root })
            .forEach { root -> collectFromConfigFiles(root, appDataRoots).forEach { add(it) } }
        collectFromAppDataFuzzy(appDataRoots, gameName, developerName).forEach { add(it) }

        if (found.isEmpty()) {
            return if (binaryResult.stdSeen) {
                Timber.tag(TAG).i(
                    "[WS-DETECT] game='$gameName' candidates=0 decision: Standard (Steam API strings seen)"
                )
                DetectionResult(
                    WorkshopModPathStrategy.Standard, Confidence.MEDIUM,
                    "Standard Steam Workshop API detected, no local mod dirs found",
                    stdSeen = true,
                )
            } else {
                Timber.tag(TAG).i(
                    "[WS-DETECT] game='$gameName' candidates=0 decision: Standard/LOW (no signals)"
                )
                defaultResult("No mod path signals found")
            }
        }

        val sorted = found.values.sortedByDescending { it.confidence }
        val topConf = sorted.first().confidence
        val dirs = sorted.map { it.dir }

        val strongFamilyOf: (CandidateDir) -> String? = { c ->
            when {
                c.source.startsWith("binary") -> "binary"
                c.source.startsWith("config(appdata)") -> "config-appdata"
                else -> null
            }
        }
        val distinctStrongFamilies = sorted
            .filter { it.confidence >= Confidence.MEDIUM && strongFamilyOf(it) != null }
            .mapNotNull { strongFamilyOf(it) }
            .toSet()
        val fanOut = if (distinctStrongFamilies.size >= 2)
            WorkshopModPathStrategy.FanOutPolicy.ALL_DIRS
        else
            WorkshopModPathStrategy.FanOutPolicy.PRIMARY_ONLY

        val reason = sorted.joinToString("; ") { "${it.dir.name}[${it.confidence}](${it.source})" }

        Timber.tag(TAG).i("[WS-DETECT] game='$gameName' candidates=${sorted.size}")
        sorted.forEachIndexed { i, c ->
            val family = strongFamilyOf(c) ?: "weak"
            Timber.tag(TAG).i(
                "[WS-DETECT]   #$i dir=${c.dir.name.padEnd(20)} " +
                    "conf=${c.confidence.name.padEnd(6)} " +
                    "source=${c.source.padEnd(30)} family=$family"
            )
        }
        Timber.tag(TAG).i(
            "[WS-DETECT] decision: SymlinkIntoDir fanOut=$fanOut " +
                "strongFamilies=$distinctStrongFamilies topConf=$topConf"
        )

        return DetectionResult(WorkshopModPathStrategy.SymlinkIntoDir(dirs, fanOut), topConf, reason, binaryResult.stdSeen)
    }

    // ── Heuristic 1: Binary scan ──────────────────────────────────────────────

    private data class BSF(val std: Boolean, val appData: List<String>, val install: List<String>)
    private data class BinaryResult(val candidates: List<CandidateDir>, val stdSeen: Boolean)

    private fun collectFromBinaries(installDir: File, appDataRoots: List<AppDataRoot>): BinaryResult {
        val exes = findMainExecutables(installDir)
        if (exes.isEmpty()) return BinaryResult(emptyList(), false)
        val findings = exes.map { scanOneBinary(it) }
        val stdSeen = findings.any { it.std }
        val adPaths = findings.flatMap { it.appData }.distinct()
        val instPaths = findings.flatMap { it.install }.distinct()
        val results = mutableListOf<CandidateDir>()
        for (raw in adPaths) {
            val r = resolveAppDataPath(raw, appDataRoots) ?: continue
            // Only accept directories that actually exist
            if (!r.isDirectory) continue
            results.add(CandidateDir(r, Confidence.HIGH, "binary(appdata)"))
        }
        for (raw in instPaths) {
            val r = resolveInstallPath(raw, installDir) ?: continue
            // Only accept directories that actually exist on disk
            if (!r.isDirectory) continue
            val conf = when (r.name.lowercase()) {
                in HIGH_CONFIDENCE_NAMES -> Confidence.HIGH
                in MEDIUM_CONFIDENCE_NAMES -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            results.add(CandidateDir(r, conf, "binary(install)"))
        }
        return BinaryResult(results, stdSeen)
    }

    private fun scanOneBinary(file: File): BSF {
        var stdFound = false
        val adPaths = mutableListOf<String>()
        val instPaths = mutableListOf<String>()
        val buf = ByteArray(65_536)
        val sb = StringBuilder(512)
        var total = 0L

        fun flush() {
            val s = sb.toString().trimEnd()
            sb.clear()
            if (s.length < MIN_STRING_LEN) return
            val lo = s.lowercase()
            if (!stdFound && BINARY_STANDARD_SIGNALS.any { lo.contains(it.lowercase()) }) stdFound = true
            if (APPDATA_TOKENS.any { lo.contains(it) }) {
                if (ALL_MOD_DIR_NAMES.any { m ->
                        lo.contains("\\$m\\") || lo.contains("/$m/") ||
                            lo.endsWith("\\$m") || lo.endsWith("/$m")
                    }) adPaths.add(s)
                return
            }
            if ((s.contains('\\') || s.contains('/')) && ALL_MOD_DIR_NAMES.any { m ->
                    lo.contains("\\$m\\") || lo.contains("/$m/") ||
                        lo.endsWith("\\$m") || lo.endsWith("/$m")
                }) instPaths.add(s)
        }

        try {
            BufferedInputStream(FileInputStream(file), buf.size).use { st ->
                var r: Int
                while (st.read(buf).also { r = it } != -1) {
                    for (i in 0 until r) {
                        val b = buf[i].toInt() and 0xFF
                        if (b in 0x20..0x7E) sb.append(b.toChar()) else flush()
                    }
                    total += r
                    if (total >= MAX_BINARY_SCAN_BYTES) break
                }
                flush()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Binary scan error ${file.name}: ${e.message}")
        }
        return BSF(stdFound, adPaths, instPaths)
    }

    private fun resolveAppDataPath(raw: String, appDataRoots: List<AppDataRoot>): File? {
        val norm = raw.replace('\\', '/').trim()
        for (adRoot in appDataRoots) {
            val rem = when {
                norm.startsWith(adRoot.envToken, ignoreCase = true) ->
                    norm.substring(adRoot.envToken.length).trimStart('/')
                norm.contains("appdata/${adRoot.root.name}", ignoreCase = true) -> {
                    val m = "appdata/${adRoot.root.name}"
                    val i = norm.indexOf(m, ignoreCase = true)
                    norm.substring(i + m.length).trimStart('/')
                }
                else -> null
            } ?: continue
            if (rem.isBlank()) continue
            return File(adRoot.root, rem)
        }
        return null
    }

    private fun resolveInstallPath(raw: String, installDir: File): File? {
        val segs = raw.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }
        val idx = segs.indexOfFirst { it.lowercase() in ALL_MOD_DIR_NAMES }
        if (idx < 0) return null
        // Return only the mod directory itself, not the full sub-path.
        // E.g. for "Data/Music/Special/song.xwm" with match at "Data",
        // return installDir/Data — not the deep file path.
        return File(installDir, segs[idx])
    }

    // ── Heuristic 2: Install-dir scan ─────────────────────────────────────────

    private fun collectModsDirectories(dir: File, depth: Int): List<CandidateDir> {
        if (depth > INSTALL_SCAN_DEPTH) return emptyList()
        val results = mutableListOf<CandidateDir>()
        dir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            val lo = child.name.lowercase()
            // Skip internal/infrastructure directories
            if (lo in INSTALL_SKIP_DIRS || child.name.startsWith(".")) return@forEach
            // Skip Unity engine data directories (contain Plugins/ with native DLLs, not mods)
            if (lo.endsWith("_data") && child.listFiles()?.any {
                    it.name.equals("Managed", ignoreCase = true) ||
                        it.name.equals("Resources", ignoreCase = true)
                } == true) return@forEach
            val populated = (child.listFiles()?.size ?: 0) > 0
            val conf: Confidence? = when {
                lo in HIGH_CONFIDENCE_NAMES -> Confidence.HIGH
                lo in MEDIUM_CONFIDENCE_NAMES && populated -> Confidence.MEDIUM
                lo in MEDIUM_CONFIDENCE_NAMES && depth == 0 -> Confidence.LOW
                else -> null
            }
            if (conf != null) {
                results.add(
                    CandidateDir(
                        child, conf,
                        if (populated) "install-dir(pop,d$depth)" else "install-dir(empty,d$depth)"
                    )
                )
            }
            if (lo !in HIGH_CONFIDENCE_NAMES && lo !in MEDIUM_CONFIDENCE_NAMES
                && lo !in CONFIG_SKIP_DIRS && lo !in INSTALL_SKIP_DIRS) {
                results += collectModsDirectories(child, depth + 1)
            }
        }
        return results
    }

    // ── Heuristic 3: Config file scanning ────────────────────────────────────

    private fun collectFromConfigFiles(root: File, appDataRoots: List<AppDataRoot>): List<CandidateDir> {
        val candidates = mutableListOf<File>()
        collectConfigCandidates(root, 0, 3, candidates)
        return candidates.flatMap { parseConfigFile(it, root, appDataRoots) }
    }

    private fun collectConfigCandidates(dir: File, depth: Int, max: Int, out: MutableList<File>) {
        if (depth > max) return
        dir.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.extension.lowercase() in CONFIG_EXTENSIONS &&
                entry.name.lowercase() !in CONFIG_SKIP_FILES && entry.length() in 1..MAX_CONFIG_BYTES
            ) {
                out.add(entry)
            } else if (entry.isDirectory && depth < max && entry.name.lowercase() !in CONFIG_SKIP_DIRS) {
                collectConfigCandidates(entry, depth + 1, max, out)
            }
        }
    }

    private fun parseConfigFile(file: File, scanRoot: File, appDataRoots: List<AppDataRoot>): List<CandidateDir> {
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .recoverCatching { file.readText(Charsets.ISO_8859_1) }
            .getOrNull() ?: return emptyList()
        val results = mutableListOf<CandidateDir>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#") || line.startsWith(";") ||
                line.startsWith("//") || line.startsWith("<!--")
            ) continue
            if (!CONFIG_MOD_KEY_FRAGMENTS.any { line.lowercase().contains(it) }) continue
            val di = line.indexOfFirst { it == '=' || it == ':' }.takeIf { it >= 0 } ?: continue
            val rv = line.substring(di + 1).trim().trim('"', '\'', ',').ifBlank { null } ?: continue
            if (APPDATA_TOKENS.any { rv.lowercase().contains(it) }) {
                resolveAppDataPath(rv, appDataRoots)?.let {
                    results.add(CandidateDir(it, Confidence.MEDIUM, "config(appdata):${file.name}"))
                }
            } else {
                val resolved = file.parentFile?.let { resolveToExistingDir(rv, it) }
                    ?: resolveToExistingDir(rv, scanRoot)
                resolved?.let {
                    results.add(CandidateDir(it, Confidence.MEDIUM, "config:${file.name}"))
                }
            }
        }
        return results
    }

    private fun resolveToExistingDir(raw: String, baseDir: File): File? {
        val cleaned = raw.replace("%gamedir%", baseDir.absolutePath, ignoreCase = true)
            .replace("%game_dir%", baseDir.absolutePath, ignoreCase = true)
            .replace('\\', '/')
        File(baseDir, cleaned).takeIf { it.isDirectory }?.let { return it }
        val last = cleaned.substringAfterLast('/').trim().lowercase()
        if (last in HIGH_CONFIDENCE_NAMES) {
            File(baseDir, last).takeIf { it.isDirectory }?.let { return it }
        }
        return null
    }

    // ── Heuristic 4: AppData fuzzy walk ───────────────────────────────────────

    private fun collectFromAppDataFuzzy(
        appDataRoots: List<AppDataRoot>,
        gameName: String,
        developerName: String,
    ): List<CandidateDir> {
        if (gameName.isBlank()) return emptyList()
        val tokens = buildFuzzyTokens(gameName, developerName)
        val results = mutableListOf<CandidateDir>()
        appDataRoots.forEach { walkAppDataForGame(it, tokens, 0, results) }
        return results
    }

    private fun buildFuzzyTokens(gameName: String, developerName: String): Set<String> {
        fun tok(s: String): Set<String> {
            val c = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
            val w = c.split(Regex("\\s+")).filter { it.length >= 3 }
            return buildSet { add(c.replace(" ", "")); addAll(w); w.firstOrNull()?.let { add(it) } }
        }
        return (tok(gameName) + tok(developerName)).filter { it.isNotEmpty() }.toSet()
    }

    private fun walkAppDataForGame(
        adRoot: AppDataRoot,
        tokens: Set<String>,
        depth: Int,
        results: MutableList<CandidateDir>,
    ) {
        if (depth > MAX_WALK_DEPTH || !adRoot.root.isDirectory) return
        val children = adRoot.root.listFiles() ?: return

        for (child in children) {
            if (!child.isDirectory) continue
            val norm = child.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            val matched = tokens.any { t ->
                norm == t || norm.contains(t) || (norm.length >= 5 && t.contains(norm))
            }
            if (matched) {
                val modDirs = child.listFiles()?.filter {
                    it.isDirectory && it.name.lowercase() in HIGH_CONFIDENCE_NAMES + MEDIUM_CONFIDENCE_NAMES
                }
                if (!modDirs.isNullOrEmpty()) {
                    val label = "${adRoot.envToken}\\${child.name}"
                    modDirs.forEach { modDir ->
                        val conf = if (modDir.name.lowercase() in HIGH_CONFIDENCE_NAMES) Confidence.HIGH else Confidence.MEDIUM
                        results.add(CandidateDir(modDir, conf, "appdata-fuzzy:$label"))
                        Timber.tag(TAG).i("H4 hit: $label\\${modDir.name} [$conf]")
                    }
                } else {
                    // Game data directory matched by name but has no existing
                    // mod subdirectories. Add a synthetic Mods/ candidate —
                    // many games (e.g. Going Medieval at Documents/Foxy Voxel/
                    // Going Medieval/Mods/) expect mods here but don't create
                    // the directory until first use. The symlinker will create
                    // it on demand. Require depth > 0 to avoid false positives
                    // at AppData roots (e.g. AppData/Roaming/GameName/).
                    if (depth > 0) {
                        val syntheticMods = File(child, "Mods")
                        val label = "${adRoot.envToken}\\${child.name}"
                        results.add(CandidateDir(syntheticMods, Confidence.MEDIUM, "appdata-fuzzy-synthetic:$label"))
                        Timber.tag(TAG).i("H4 synthetic: $label\\Mods [MEDIUM]")
                    }
                    walkAppDataForGame(
                        AppDataRoot(child, "${adRoot.envToken}\\${child.name}"),
                        tokens, depth + 1, results,
                    )
                }
            }
        }
        if (depth < 2) {
            for (child in children) {
                if (!child.isDirectory || child.name.firstOrNull()?.isUpperCase() != true) continue
                if (child.name in APPDATA_SKIP_DIRS) continue
                val norm = child.name.lowercase().replace(Regex("[^a-z0-9]"), "")
                if (!tokens.any { t ->
                        norm == t || norm.contains(t) || (norm.length >= 5 && t.contains(norm))
                    }) {
                    walkAppDataForGame(
                        AppDataRoot(child, "${adRoot.envToken}\\${child.name}"),
                        tokens, depth + 1, results,
                    )
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findMainExecutables(installDir: File): List<File> =
        (installDir.listFiles { f ->
            f.isFile && f.extension.lowercase() == "exe" && f.length() > 512 * 1024 &&
                !f.name.lowercase().let { n ->
                    n.contains("unins") || n.contains("crash") || n.contains("report") ||
                        n.contains("setup") || n.contains("install") || n.contains("redist") ||
                        n.contains("vcredist")
                }
        } ?: emptyArray()).sortedByDescending { it.length() }.take(2)

    private fun defaultResult(reason: String) =
        DetectionResult(WorkshopModPathStrategy.Standard, Confidence.LOW, reason)
}
