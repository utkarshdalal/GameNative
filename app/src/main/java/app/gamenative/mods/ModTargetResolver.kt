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
            if (driveC.isDirectory) {
                result += ResolvedModTargetRoot(ModTargetRoot.WINE_C, "C: Drive", driveC)
                val userHome = ModContainerResolver.getWineUserHome(winePrefix)
                result += ResolvedModTargetRoot(ModTargetRoot.DOCUMENTS, "My Documents", File(userHome, "Documents"))
                result += ResolvedModTargetRoot(ModTargetRoot.MY_GAMES, "My Games", File(userHome, "Documents/My Games"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_ROAMING, "AppData / Roaming", File(userHome, "AppData/Roaming"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCAL, "AppData / Local", File(userHome, "AppData/Local"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCALLOW, "AppData / LocalLow", File(userHome, "AppData/LocalLow"))
            }
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
            val target = rawTarget.safeCanonicalFile() ?: return null
            val allowedRoots = roots(gameRootDir, winePrefix).mapNotNull { it.dir.safeCanonicalFile() }
            return target.takeIf { candidate ->
                allowedRoots.any { root -> candidate.isInsideOrEqual(root) }
            }
        }
        val root = roots(gameRootDir, winePrefix).firstOrNull { it.type == rootType }?.dir ?: return null
        val cleanRelative = normalizeRelativePath(targetRelativePath)
        val rootCanonical = root.safeCanonicalFile() ?: return null
        val target = if (cleanRelative.isBlank()) {
            rootCanonical
        } else {
            File(rootCanonical, cleanRelative).safeCanonicalFile() ?: return null
        }
        return target.takeIf { it.isInsideOrEqual(rootCanonical) }
    }

    private fun File.safeCanonicalFile(): File? =
        runCatching { canonicalFile }.getOrNull()

    private fun File.isInsideOrEqual(root: File): Boolean {
        if (this == root) return true
        val rootPath = root.path
        if (rootPath == File.separator) return path.startsWith(rootPath)
        return path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }
}
