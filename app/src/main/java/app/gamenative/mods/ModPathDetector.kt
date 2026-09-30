package app.gamenative.mods

import app.gamenative.workshop.WorkshopModPathDetector
import app.gamenative.workshop.WorkshopModPathStrategy
import java.io.File

data class ModPathDetectionResult(
    val targetDirs: List<File>,
    val confidence: String,
    val reason: String,
)

object ModPathDetector {
    fun detect(gameRootDir: File?, winePrefix: String, gameName: String): ModPathDetectionResult? {
        if (gameRootDir?.isDirectory != true || winePrefix.isBlank()) return null
        val userHome = ModContainerResolver.getWineUserHome(winePrefix)
        val result = WorkshopModPathDetector().detect(
            gameInstallDir = gameRootDir,
            appDataRoaming = File(userHome, "AppData/Roaming"),
            appDataLocal = File(userHome, "AppData/Local"),
            appDataLocalLow = File(userHome, "AppData/LocalLow"),
            documentsMyGames = File(userHome, "Documents/My Games"),
            documentsDir = File(userHome, "Documents"),
            gameName = gameName,
        )
        val dirs = when (val strategy = result.strategy) {
            WorkshopModPathStrategy.Standard -> emptyList()
            is WorkshopModPathStrategy.SymlinkIntoDir -> strategy.effectiveDirs
            is WorkshopModPathStrategy.CopyIntoDir -> strategy.effectiveDirs
        }
        return if (dirs.isEmpty()) {
            null
        } else {
            ModPathDetectionResult(dirs, result.confidence.name, result.reason)
        }
    }
}
