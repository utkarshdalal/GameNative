package app.gamenative.utils

import app.gamenative.enums.Marker
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File

/** Windows path -> installer args, checked against host filesystem to see which exist. */
private val vcRedistMap: Map<String, String> = mapOf(
    "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x86.exe" to "/Q",
    "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x64.exe" to "/Q",
    "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x86.exe" to "/qb!",
    "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x64.exe" to "/qb!",
    "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2022\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2022\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2022\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\redist\\vcredist_x86.exe" to "",
    "A:\\redist\\vcredist_x64.exe" to "",
    "A:\\_CommonRedist\\MSVC2005\\vcredist_x86.exe" to "/Q",
    "A:\\_CommonRedist\\MSVC2005_x64\\vcredist_x64.exe" to "/Q",
    "A:\\_CommonRedist\\MSVC2008\\vcredist_x86.exe" to "/qb!",
    "A:\\_CommonRedist\\MSVC2008_x64\\vcredist_x64.exe" to "/qb!",
    "A:\\_CommonRedist\\MSVC2010\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2010_x64\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2012\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2012_x64\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2013\\vcredist_x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2013_x64\\vcredist_x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2015\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2015_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2017_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2019\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2019_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2022\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2022_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\VC_redist.x64.exe" to "/install /passive /norestart",
)

object VcRedistStep : PreInstallStep {
    override val marker: Marker = Marker.VCREDIST_INSTALLED

    /**
     * Per-version marker prefix written at the container root so we can tell
     * which redistributable years are already installed system-wide. Pairs
     * with [Marker.VCREDIST_INSTALLED] (which marks the step "complete" for
     * the current game) but is keyed per-year so a later game bundling a
     * different year is not silently skipped.
     */
    private const val PER_VERSION_MARKER_PREFIX = ".vcredist_installed_"

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        // vcredist installs system-wide into the Wine prefix, not per-game. We
        // track installed years/arches at the container root (one marker per
        // year+arch) so reinstalling the game (which wipes the game dir)
        // doesn't force a redundant re-run, while still detecting when a
        // *different* game bundles a year/arch we have not yet installed.
        val gameDir = File(gameDirPath)
        val required = requiredVersions(gameDir)
        if (required.isEmpty()) {
            return false
        }
        val containerRoot = container.rootDir?.absolutePath
        migrateLegacyContainerMarker(containerRoot, required)
        migrateLegacyArchlessMarkers(containerRoot)
        val installed = installedVersions(containerRoot)
        if (containerRoot != null && installed.isNotEmpty()) {
            // Per-year/arch markers are the source of truth here. The coarse
            // game-dir marker is ignored — if anything required is missing we
            // must run buildCommand for the missing set.
            return required.any { it !in installed }
        }
        val missing = required - installed
        if (missing.isEmpty()) {
            return false
        }
        // No per-year markers to consult: fall back to the legacy game-dir
        // marker so an in-flight install that already ran for this exact game
        // directory still short-circuits.
        return !MarkerUtils.hasMarker(gameDirPath, Marker.VCREDIST_INSTALLED)
    }

    /**
     * One-shot migration for containers that were tagged by the previous
     * coarse `.vcredist_installed` marker at the prefix root: convert it
     * into per-year sidecars covering [requiredForCurrentGame], then drop
     * the legacy file. Years not bundled by the current game are *not*
     * marked installed, since we have no way to know if the previous
     * launch's game actually installed them. Worst case the next launch
     * re-runs one installer once.
     */
    private fun migrateLegacyContainerMarker(
        containerRoot: String?,
        requiredForCurrentGame: Set<String>,
    ) {
        if (containerRoot == null) return
        val legacy = File(containerRoot, Marker.VCREDIST_INSTALLED.fileName)
        if (!legacy.isFile) return
        for (version in requiredForCurrentGame) {
            val marker = File(containerRoot, "$PER_VERSION_MARKER_PREFIX$version")
            if (!marker.exists()) {
                runCatching { marker.createNewFile() }
            }
        }
        runCatching { legacy.delete() }
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val containerRoot = container.rootDir?.absolutePath
        migrateLegacyArchlessMarkers(containerRoot)
        val installed = installedVersions(containerRoot)
        val parts = mutableListOf<String>()
        for ((winPath, args) in vcRedistMap) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val rest = winPath.substring(3)
            val lastSep = rest.lastIndexOf('\\')
            if (lastSep < 0) continue
            val hostFile = File(gameDir, rest.replace('\\', '/'))
            if (!hostFile.isFile) continue
            // Skip installer entries for year+arch combos already installed
            // system-wide so we don't pop a fresh installer window per launch
            // (the 963d7999 fix). Year+arch combos we haven't seen still run.
            val version = versionKey(winPath)
            if (installed.contains(version)) continue
            parts.add(if (args.isEmpty()) winPath else "$winPath $args")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }

    /**
     * Persist per-version markers for the years detected in [gameDir] at the
     * container root. Called after the install step completes so future
     * launches (and other games sharing the container) know which years are
     * already covered without having to rerun any installer.
     */
    fun recordInstalledVersions(container: Container, gameDir: File) {
        val containerRoot = container.rootDir?.absolutePath ?: return
        val versions = requiredVersions(gameDir)
        for (version in versions) {
            val marker = File(containerRoot, "$PER_VERSION_MARKER_PREFIX$version")
            if (!marker.exists()) {
                runCatching { marker.createNewFile() }
            }
        }
    }

    /** The set of redistributable years bundled with the game at [gameDir]. */
    private fun requiredVersions(gameDir: File): Set<String> {
        val out = linkedSetOf<String>()
        for (winPath in vcRedistMap.keys) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val rest = winPath.substring(3)
            if (rest.lastIndexOf('\\') < 0) continue
            val hostFile = File(gameDir, rest.replace('\\', '/'))
            if (!hostFile.isFile) continue
            out.add(versionKey(winPath))
        }
        return out
    }

    /** Read the set of years already installed system-wide in the container. */
    private fun installedVersions(containerRoot: String?): Set<String> {
        if (containerRoot == null) return emptySet()
        val dir = File(containerRoot)
        if (!dir.isDirectory) return emptySet()
        val files = dir.listFiles() ?: return emptySet()
        val out = linkedSetOf<String>()
        for (f in files) {
            val name = f.name
            if (f.isFile && name.startsWith(PER_VERSION_MARKER_PREFIX)) {
                out.add(name.substring(PER_VERSION_MARKER_PREFIX.length))
            }
        }
        return out
    }

    /**
     * Map an installer's Windows path to a stable "$year-$arch" key (or
     * "legacy-$arch" when no year is present). x86 and x64 installers from
     * the same year get distinct keys so installing one does not mask the
     * other. When no architecture hint is detectable we default to x86,
     * matching the most common older redistributable layout.
     */
    private fun versionKey(winPath: String): String {
        val lower = winPath.lowercase()
        val arch = archKey(lower)
        for (year in YEAR_KEYS) {
            if (lower.contains("\\$year\\") || lower.contains("\\msvc$year\\") ||
                lower.contains("\\msvc${year}_x64\\")
            ) {
                return "$year-$arch"
            }
        }
        return "legacy-$arch"
    }

    private fun archKey(lowerWinPath: String): String {
        val x64Hints = listOf("x64", "amd64", "wow64", "x86_64", "_x64\\", ".x64.")
        if (x64Hints.any { lowerWinPath.contains(it) }) return "x64"
        if (lowerWinPath.contains("x86") || lowerWinPath.contains(".x86.")) return "x86"
        return "x86"
    }

    /**
     * One-time upgrade for markers written by the prior round-5 fix that were
     * keyed by year only (e.g. `.vcredist_installed_2005`, `.vcredist_installed_legacy`).
     * We don't know which arch was actually installed back then, so we
     * conservatively claim only x86 coverage; if x64 was also installed it
     * will be re-run on the next launch (one redundant installer at most).
     */
    private fun migrateLegacyArchlessMarkers(containerRoot: String?) {
        if (containerRoot == null) return
        val dir = File(containerRoot)
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            if (!name.startsWith(PER_VERSION_MARKER_PREFIX)) continue
            val suffix = name.substring(PER_VERSION_MARKER_PREFIX.length)
            if (suffix.contains('-')) continue
            val migrated = File(containerRoot, "$PER_VERSION_MARKER_PREFIX$suffix-x86")
            if (!migrated.exists()) {
                runCatching { migrated.createNewFile() }
            }
            runCatching { f.delete() }
        }
    }

    private val YEAR_KEYS = listOf(
        "2005", "2008", "2010", "2012", "2013",
        "2015", "2017", "2019", "2022",
    )
}
