package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import timber.log.Timber
import java.io.File

/**
 * Some ScummVM releases ship with a shared ScummVM launcher instead of a game-specific exe.
 * This fix finds the bundled ScummVM executable, reads the game-local ini, and rewrites the
 * container launch command so the right game starts with the right config.
 */
class ScummGameFix(
    override val gameSource: GameSource,
    override val gameId: String,
) : KeyedGameFix {
    private val possibleExecutables = listOf(
        "ScummVM/scummvm.exe",
        "ScummVM_Windows/scummvm.exe",
        "scummvm/scummvm.exe",
        "scummvm.exe",
    )

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val scummvmExePath = possibleExecutables.firstOrNull { File(installPath, it).exists() } ?: return false
            val exeFile = File(installPath, scummvmExePath)
            val exeDir = exeFile.parentFile ?: File(installPath)

            val localIni = findScummVmIni(installPath, exeDir)

            if (localIni != null) {
                disableUpdatesCheckInIni(localIni)
                val detectedGameId = getGameIdFromIni(localIni)
                if (detectedGameId == null) {
                    Timber.tag("GameFixes").e("Found .ini but could not detect Game ID in ${localIni.absolutePath}")
                    return false
                }

                val relativeIniPath = localIni.absolutePath.substringAfter(installPath).trimStart(File.separatorChar, '/')
                val windowsIniPath = buildWindowsIniPath(installPathWindows, relativeIniPath)

                container.executablePath = scummvmExePath
                container.execArgs = "-c \"$windowsIniPath\" $detectedGameId"
                container.saveData()

                Timber.tag("GameFixes").i("Detected and using local ScummVM config: $windowsIniPath (Game ID: $detectedGameId)")
                true
            } else {
                Timber.tag("GameFixes").w("No local .ini found for ScummVM game $gameId in ${exeDir.absolutePath} or root")
                false
            }
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to apply ScummVM fix for $gameId")
            false
        }
    }

    private fun buildWindowsIniPath(installPathWindows: String, relativeIniPath: String): String {
        val normalizedRoot = installPathWindows.trimEnd('\\', '/')
        val normalizedRelative = relativeIniPath.replace("/", "\\").replace("\\\\", "\\")
        return if (normalizedRelative.isEmpty()) normalizedRoot else "$normalizedRoot\\$normalizedRelative"
    }

    private fun disableUpdatesCheckInIni(iniFile: File) {
        try {
            val lines = iniFile.readLines().toMutableList()
            var updatesCheckFound = false
            var scummvmSectionIndex = -1

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("updates_check=", ignoreCase = true)) {
                    lines[i] = "updates_check=0"
                    updatesCheckFound = true
                } else if (line.equals("[scummvm]", ignoreCase = true)) {
                    scummvmSectionIndex = i
                }
            }

            if (scummvmSectionIndex == -1) {
                lines.add(0, "[scummvm]")
                scummvmSectionIndex = 0
            }

            if (!updatesCheckFound) {
                lines.add(scummvmSectionIndex + 1, "updates_check=0")
            }

            iniFile.writeText(lines.joinToString("\n"))
            Timber.tag("GameFixes").i("Optimized ScummVM config in ${iniFile.name}: disabled updates check")
        } catch (e: Exception) {
            Timber.tag("GameFixes").w(e, "Failed to optimize ScummVM config in ${iniFile.name}")
        }
    }

    private fun getGameIdFromIni(iniFile: File): String? {
        return try {
            val lines = iniFile.readLines()
            var lastSelected: String? = null
            var currentSection: String? = null
            val sections = linkedSetOf<String>()
            val launchableSections = linkedSetOf<String>()

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("lastselectedgame=", ignoreCase = true) -> {
                        lastSelected = trimmed.substringAfter("=").trim().takeIf { it.isNotEmpty() }
                    }
                    trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                        currentSection = trimmed.substring(1, trimmed.length - 1).trim().takeIf { it.isNotEmpty() }
                        currentSection?.let { sections.add(it) }
                    }
                    currentSection != null && looksLikeLaunchableScummVmEntry(trimmed) -> {
                        currentSection?.let { launchableSections.add(it) }
                    }
                }
            }

            when {
                lastSelected != null && sections.contains(lastSelected) -> lastSelected
                launchableSections.isNotEmpty() -> launchableSections.first()
                else -> sections.firstOrNull { !isIgnoredScummVmSection(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findScummVmIni(installPath: String, exeDir: File): File? {
        val candidates = listOfNotNull(
            exeDir.listFiles()?.firstOrNull { it.extension.lowercase() == "ini" },
            File(installPath).listFiles()?.firstOrNull { it.extension.lowercase() == "ini" },
        )
        return candidates.firstOrNull()
    }

    private fun looksLikeLaunchableScummVmEntry(line: String): Boolean {
        return line.startsWith("path=", ignoreCase = true) ||
            line.startsWith("engineid=", ignoreCase = true) ||
            line.startsWith("gameid=", ignoreCase = true) ||
            line.startsWith("description=", ignoreCase = true)
    }

    private fun isIgnoredScummVmSection(section: String): Boolean {
        return section.equals("scummvm", ignoreCase = true) ||
            section.equals("cloud", ignoreCase = true) ||
            section.equals("keymapper", ignoreCase = true)
    }
}
