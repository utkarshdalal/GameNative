package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import java.io.File

data class ResolvedModTargetRoot(
    val type: ModTargetRoot,
    val label: String,
    val dir: File,
)

object ModTargetResolver {
    fun normalizeRelativePath(path: String): String =
        path.trim().replace('\\', '/').trim('/')

    fun roots(gameRootDir: File?, winePrefix: String): List<ResolvedModTargetRoot> {
        val result = mutableListOf<ResolvedModTargetRoot>()
        if (gameRootDir?.isDirectory == true) {
            result += ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game Directory", gameRootDir)
        }
        if (winePrefix.isNotBlank()) {
            val driveC = File(winePrefix, "drive_c")
            if (driveC.isDirectory) result += ResolvedModTargetRoot(ModTargetRoot.WINE_C, "C: Drive", driveC)
            val userHome = ModContainerResolver.getWineUserHome(winePrefix)
            val documents = File(userHome, "Documents")
            if (documents.isDirectory) result += ResolvedModTargetRoot(ModTargetRoot.DOCUMENTS, "My Documents", documents)
            val myGames = File(userHome, "Documents/My Games")
            if (myGames.isDirectory) result += ResolvedModTargetRoot(ModTargetRoot.MY_GAMES, "My Games", myGames)
            val roaming = File(userHome, "AppData/Roaming")
            if (roaming.isDirectory) result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_ROAMING, "AppData / Roaming", roaming)
            val local = File(userHome, "AppData/Local")
            if (local.isDirectory) result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCAL, "AppData / Local", local)
            val localLow = File(userHome, "AppData/LocalLow")
            if (localLow.isDirectory) result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCALLOW, "AppData / LocalLow", localLow)
        }
        return result
    }

    fun resolve(
        targetRoot: String,
        targetRelativePath: String,
        gameRootDir: File?,
        winePrefix: String,
    ): File? {
        val rootType = runCatching { ModTargetRoot.valueOf(targetRoot) }.getOrNull() ?: return null
        if (rootType == ModTargetRoot.CUSTOM_ABSOLUTE) {
            val rawTarget = File(targetRelativePath.trim().replace('\\', '/'))
            if (!rawTarget.isAbsolute) return null
            val target = rawTarget.canonicalFile
            val allowedRoots = roots(gameRootDir, winePrefix).map { it.dir.canonicalFile }
            return target.takeIf { candidate ->
                allowedRoots.any { root -> candidate.isInsideOrEqual(root) }
            }
        }
        val root = roots(gameRootDir, winePrefix).firstOrNull { it.type == rootType }?.dir ?: return null
        val cleanRelative = normalizeRelativePath(targetRelativePath)
        val rootCanonical = root.canonicalFile
        val target = if (cleanRelative.isBlank()) {
            rootCanonical
        } else {
            File(rootCanonical, cleanRelative).canonicalFile
        }
        return target.takeIf { it.isInsideOrEqual(rootCanonical) }
    }

    private fun File.isInsideOrEqual(root: File): Boolean =
        this == root || path.startsWith(root.path + File.separator)
}
