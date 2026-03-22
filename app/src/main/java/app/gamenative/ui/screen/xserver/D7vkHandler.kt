package app.gamenative.ui.screen.xserver

import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File
import com.winlator.core.FileUtils

/**
 * Resolves a game's containing directory from a Windows executable path.
 * Handles both absolute Windows paths (e.g. "C:\dir\game.exe", "D:\dir\game.exe")
 * and relative paths (e.g. "game.exe") by searching the container's mapped drives.
 */
fun resolveGameDirForD7vk(
    container: Container,
    imageFs: ImageFs,
    executablePath: String,
): File? {
    val normalized = executablePath.replace("\\", "/")
    val rootDir = imageFs.getRootDir()
    return if (normalized.length >= 2 && normalized[1] == ':') {
        val driveLetter = normalized[0].uppercaseChar()
        val pathFromRoot = normalized.substring(2) // e.g. "/Game/game.exe"
        if (driveLetter == 'C') {
            File(rootDir, "${ImageFs.WINEPREFIX}/drive_c$pathFromRoot").parentFile
        } else {
            for (drive in container.drivesIterator()) {
                if (drive[0].uppercase() == driveLetter.toString()) {
                    return File(drive[1] + pathFromRoot).parentFile
                }
            }
            null
        }
    } else {
        // Relative path: find the mapped drive whose root contains this executable
        for (drive in container.drivesIterator()) {
            val candidate = File(drive[1], normalized)
            if (candidate.exists()) return candidate.parentFile
        }
        null
    }
}

/**
 * Copies D7VK ddraw.dll to the game's executable directory.
 * D7VK requires ddraw.dll to be placed next to the game executable
**/
fun copyD7vkToGameDirectory(
    container: Container,
    imageFs: ImageFs,
    gameExecutablePath: String,
) {
    try {
        // Check if d7vk is enabled
        val dxwrapper = container.getExtra("dxwrapper", "")
        if (!dxwrapper.startsWith("d7vk")) {
            Timber.d("D7VK not enabled, skipping ddraw.dll copy")
            return
        }

        Timber.i("Copying D7VK ddraw.dll to game directory for executable: $gameExecutablePath")

        val rootDir = imageFs.getRootDir()
        val d7vkStagingDir = File(rootDir, ImageFs.CACHE_PATH + "/d7vk")

        // Check if staging directory exists
        if (!d7vkStagingDir.exists()) {
            Timber.w("D7VK staging directory not found, skipping copy")
            return
        }

        val gameDir = resolveGameDirForD7vk(container, imageFs, gameExecutablePath)
        if (gameDir == null || !gameDir.exists()) {
            Timber.w("D7VK: could not resolve game directory for: $gameExecutablePath")
            return
        }

        Timber.i("Game directory: ${gameDir.absolutePath}")

        // D7VK is 32-bit only, so we copy from syswow64; fall back to system32
        val sourceDll = File(d7vkStagingDir, "syswow64/ddraw.dll").takeIf { it.exists() }
            ?: File(d7vkStagingDir, "system32/ddraw.dll").takeIf { it.exists() }
            ?: run {
                Timber.w("D7VK ddraw.dll not found in staging directory")
                return
            }

        val targetDll = File(gameDir, "ddraw.dll")
        val backupDll = File(gameDir, "ddraw.dll.bak")
        if (targetDll.exists() && !backupDll.exists()) {
            targetDll.renameTo(backupDll)
            Timber.i("Backed up original ddraw.dll to: ${backupDll.absolutePath}")
        }
        FileUtils.copy(sourceDll, targetDll)
        Timber.i("Copied D7VK ddraw.dll to: ${targetDll.absolutePath}")
    } catch (e: Exception) {
        Timber.e(e, "Failed to copy D7VK ddraw.dll to game directory")
    }
}

/**
 * Removes D7VK ddraw.dll from the game directory during cleanup.
 *
 **/
fun cleanupD7vkFromGameDirectory(
    container: Container,
    imageFs: ImageFs,
) {
    try {
        val dxwrapper = container.getExtra("dxwrapper", "")
        if (!dxwrapper.startsWith("d7vk")) {
            return
        }

        val executablePath = container.executablePath
        if (executablePath.isEmpty()) {
            Timber.d("No executable path set, skipping D7VK cleanup")
            return
        }

            Timber.i("Cleaning up D7VK ddraw.dll from game directory: $executablePath")

            val gameDir = resolveGameDirForD7vk(container, imageFs, executablePath)
            if (gameDir == null || !gameDir.exists()) {
                Timber.w("D7VK: could not resolve game directory for cleanup: $executablePath")
                return
            }

            // Restore original ddraw.dll if it was backed up, otherwise remove D7VK's copy
            val ddrawDll = File(gameDir, "ddraw.dll")
            val backupDll = File(gameDir, "ddraw.dll.bak")
            when {
                backupDll.exists() -> {
                    ddrawDll.delete()
                    backupDll.renameTo(ddrawDll)
                    Timber.i("Restored original ddraw.dll at: ${ddrawDll.absolutePath}")
                }
                ddrawDll.exists() -> {
                    val deleted = ddrawDll.delete()
                    if (deleted) {
                        Timber.i("Removed D7VK ddraw.dll from: ${ddrawDll.absolutePath}")
                    } else {
                        Timber.w("Failed to remove D7VK ddraw.dll from: ${ddrawDll.absolutePath}")
                    }
                }
                else -> Timber.d("D7VK ddraw.dll not found in game directory, nothing to clean")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup D7VK ddraw.dll from game directory")
        }
    }

