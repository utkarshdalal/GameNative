package app.gamenative.enums

import android.content.Context
import app.gamenative.service.SteamService
import com.winlator.xenvironment.ImageFs
import java.nio.file.Paths
import timber.log.Timber

enum class PathType {
    GameInstall,
    SteamUserData,
    WinMyDocuments,
    WinAppDataLocal,
    WinAppDataLocalLow,
    WinAppDataRoaming,
    WinSavedGames,
    LinuxHome,
    LinuxXdgDataHome,
    LinuxXdgConfigHome,
    MacHome,
    MacAppSupport,
    None,
    Root,
    ;

    /**
     * Turns a path type to a full path through the android system to the expected directory in
     * the wine prefix or the steam common app dir. Make sure to run
     * [com.winlator.container.ContainerManager.activateContainer] on the proper
     * [com.winlator.container.Container] beforehand.
     */
    fun toAbsPath(context: Context, appId: Int, accountId: Long): String {
        val path = when (this) {
            GameInstall -> SteamService.getAppDirPath(appId)
            SteamUserData -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId/remote",
            ).toString()
            WinMyDocuments -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/users/",
                ImageFs.USER,
                "Documents/",
            ).toString()
            WinAppDataLocal -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/users/",
                ImageFs.USER,
                "AppData/Local/",
            ).toString()
            WinAppDataLocalLow -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/users/",
                ImageFs.USER,
                "AppData/LocalLow/",
            ).toString()
            WinAppDataRoaming -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/users/",
                ImageFs.USER,
                "AppData/Roaming/",
            ).toString()
            WinSavedGames -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/users/",
                ImageFs.USER,
                "Saved Games/",
            ).toString()
            Root -> Paths.get(
                ImageFs.find(context).rootDir.absolutePath,
                ImageFs.WINEPREFIX,
                "/drive_c/users/",
                ImageFs.USER,
                "",
            ).toString()
            else -> {
                Timber.e("Did not recognize or unsupported path type $this")
                SteamService.getAppDirPath(appId)
            }
        }
        return if (!path.endsWith("/")) "$path/" else path
    }

    val isWindows: Boolean
        get() = when (this) {
            GameInstall,
            SteamUserData,
            WinMyDocuments,
            WinAppDataLocal,
            WinAppDataLocalLow,
            WinAppDataRoaming,
            WinSavedGames,
            -> true
            else -> false
        }

    companion object {
        val DEFAULT = SteamUserData

        /**
         * Resolve GOG path variables (<?VARIABLE?>) to Windows environment variables
         * Converts GOG-specific variables like <?INSTALL?> to actual paths or Windows env vars
         * @param location Path template with GOG variables (e.g., "<?INSTALL?>/saves")
         * @param installPath Game install path (for <?INSTALL?> variable)
         * @return Path with GOG variables resolved (may still contain Windows env vars like %LOCALAPPDATA%)
         */
        fun resolveGOGPathVariables(location: String, installPath: String): String {
            var resolved = location

            // Map of GOG variables to their values
            val variableMap = mapOf(
                "INSTALL" to installPath,
                "SAVED_GAMES" to "%USERPROFILE%/Saved Games",
                "APPLICATION_DATA_LOCAL" to "%LOCALAPPDATA%",
                "APPLICATION_DATA_LOCAL_LOW" to "%APPDATA%\\..\\LocalLow",
                "APPLICATION_DATA_ROAMING" to "%APPDATA%",
                "DOCUMENTS" to "%USERPROFILE%\\Documents"
            )

            // Find and replace <?VARIABLE?> patterns
            val pattern = Regex("<\\?(\\w+)\\?>")
            val matches = pattern.findAll(resolved)

            for (match in matches) {
                val variableName = match.groupValues[1]
                val replacement = variableMap[variableName]
                if (replacement != null) {
                    resolved = resolved.replace(match.value, replacement)
                    Timber.d("Resolved GOG variable <?$variableName?> to $replacement")
                } else {
                    Timber.w("Unknown GOG path variable: <?$variableName?>, leaving as-is")
                }
            }

            return resolved
        }

        /**
         * Convert a GOG Windows path with environment variables to an absolute device path
         * Used for GOG cloud saves which provide Windows paths that need to be mapped to Wine prefix
         * @param context Android context
         * @param gogWindowsPath GOG-provided Windows path that may contain env vars like %LOCALAPPDATA%, %APPDATA%, %USERPROFILE%
         * @return Absolute Unix path in Wine prefix
         */
        fun toAbsPathForGOG(context: Context, gogWindowsPath: String, appId: String? = null): String {
            val imageFs = ImageFs.find(context)
            // For GOG games, use the container-specific wine prefix if appId is provided
            val (winePrefix, useContainerRoot) = if (appId != null) {
                val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, appId)
                val containerRoot = container.rootDir.absolutePath
                Timber.d("[PathType] Using container-specific root for $appId: $containerRoot")
                Pair(containerRoot, true)
            } else {
                Pair(imageFs.rootDir.absolutePath, false)
            }
            val user = ImageFs.USER

            var mappedPath = gogWindowsPath

            // Map Windows environment variables to their Wine prefix equivalents
            // When using container root, paths are relative to containerRoot/.wine/
            // When using imageFs, paths are relative to imageFs/home/xuser/.wine/
            val winePrefixPath = if (useContainerRoot) {
                // Container root is already the container dir, wine is at .wine/
                ".wine"
            } else {
                // ImageFs needs home/xuser/.wine
                ImageFs.WINEPREFIX
            }

            // Handle %USERPROFILE% first to avoid partial replacements
            if (mappedPath.contains("%USERPROFILE%/Saved Games") || mappedPath.contains("%USERPROFILE%\\Saved Games")) {
                val savedGamesPath = Paths.get(
                    winePrefix, winePrefixPath,
                    "drive_c/users/", user, "Saved Games/"
                ).toString()
                mappedPath = mappedPath.replace("%USERPROFILE%/Saved Games", savedGamesPath)
                    .replace("%USERPROFILE%\\Saved Games", savedGamesPath)
            }

            if (mappedPath.contains("%USERPROFILE%/Documents") || mappedPath.contains("%USERPROFILE%\\Documents")) {
                val documentsPath = Paths.get(
                    winePrefix, winePrefixPath,
                    "drive_c/users/", user, "Documents/"
                ).toString()
                mappedPath = mappedPath.replace("%USERPROFILE%/Documents", documentsPath)
                    .replace("%USERPROFILE%\\Documents", documentsPath)
            }

            // Map standard Windows environment variables
            mappedPath = mappedPath.replace("%LOCALAPPDATA%",
                Paths.get(winePrefix, winePrefixPath, "drive_c/users/", user, "AppData/Local/").toString())
            mappedPath = mappedPath.replace("%APPDATA%",
                Paths.get(winePrefix, winePrefixPath, "drive_c/users/", user, "AppData/Roaming/").toString())
            mappedPath = mappedPath.replace("%USERPROFILE%",
                Paths.get(winePrefix, winePrefixPath, "drive_c/users/", user, "").toString())

            // Normalize path separators
            mappedPath = mappedPath.replace("\\", "/")

            // Check if path is already absolute (after env var replacement)
            val isAlreadyAbsolute = mappedPath.startsWith(winePrefix)

            // Normalize path to resolve ../ and ./ components
            // Split by /, process each component, and rebuild
            val pathParts = mappedPath.split("/").toMutableList()
            val normalizedParts = mutableListOf<String>()
            for (part in pathParts) {
                when {
                    part == ".." && normalizedParts.isNotEmpty() && normalizedParts.last() != ".." -> {
                        // Go up one directory
                        normalizedParts.removeAt(normalizedParts.lastIndex)
                    }
                    part != "." && part.isNotEmpty() -> {
                        // Add non-empty, non-current-dir parts
                        normalizedParts.add(part)
                    }
                    // Skip "." and empty parts
                }
            }
            mappedPath = normalizedParts.joinToString("/")

            // Build absolute path - but skip if already absolute after env var replacement
            val absolutePath = when {
                isAlreadyAbsolute -> {
                    // Path was already made absolute by env var replacement, use as-is
                    mappedPath
                }
                mappedPath.startsWith("drive_c/") || mappedPath.startsWith("/drive_c/") -> {
                    val cleanPath = mappedPath.removePrefix("/")
                    Paths.get(winePrefix, winePrefixPath, cleanPath).toString()
                }
                mappedPath.startsWith(winePrefix) -> {
                    // Already absolute
                    mappedPath
                }
                else -> {
                    // Relative path, assume it's in drive_c
                    Paths.get(winePrefix, winePrefixPath, "drive_c", mappedPath).toString()
                }
            }

            // Ensure path ends with / for directories
            val finalPath = if (!absolutePath.endsWith("/") && !absolutePath.endsWith("\\")) {
                "$absolutePath/"
            } else {
                absolutePath
            }

            return finalPath
        }

        fun from(keyValue: String?): PathType {
            return when (keyValue?.lowercase()) {
                "%${GameInstall.name.lowercase()}%",
                GameInstall.name.lowercase(),
                -> GameInstall
                "%${SteamUserData.name.lowercase()}%",
                SteamUserData.name.lowercase(),
                -> SteamUserData
                "%${WinMyDocuments.name.lowercase()}%",
                WinMyDocuments.name.lowercase(),
                -> WinMyDocuments
                "%${WinAppDataLocal.name.lowercase()}%",
                WinAppDataLocal.name.lowercase(),
                -> WinAppDataLocal
                "%${WinAppDataLocalLow.name.lowercase()}%",
                WinAppDataLocalLow.name.lowercase(),
                -> WinAppDataLocalLow
                "%${WinAppDataRoaming.name.lowercase()}%",
                WinAppDataRoaming.name.lowercase(),
                -> WinAppDataRoaming
                "%${WinSavedGames.name.lowercase()}%",
                WinSavedGames.name.lowercase(),
                -> WinSavedGames
                "%${LinuxHome.name.lowercase()}%",
                LinuxHome.name.lowercase(),
                -> LinuxHome
                "%${LinuxXdgDataHome.name.lowercase()}%",
                LinuxXdgDataHome.name.lowercase(),
                -> LinuxXdgDataHome
                "%${LinuxXdgConfigHome.name.lowercase()}%",
                LinuxXdgConfigHome.name.lowercase(),
                -> LinuxXdgConfigHome
                "%${MacHome.name.lowercase()}%",
                MacHome.name.lowercase(),
                -> MacHome
                "%${MacAppSupport.name.lowercase()}%",
                MacAppSupport.name.lowercase(),
                -> MacAppSupport
                "%ROOT_MOD%",
                "ROOT_MOD",
                -> Root
                else -> {
                    if (keyValue != null) {
                        Timber.w("Could not identify $keyValue as PathType")
                    }
                    None
                }
            }
        }
    }
}
