package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.SteamUtils
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import timber.log.Timber

/**
 * Collapses every per-user save folder under [documentsRelativePath] onto the folder
 * named after the current 64-bit Steam ID.
 *
 * Some games build that folder name from a buffer that no longer exists once the
 * function that filled it has returned (FFXII TZA returns a pointer to its own dead
 * stack frame). Under wine the buffer is partly overwritten, so the game reads and
 * writes a folder such as `76` instead of `76561198116602818`. Cloud sync only looks
 * at the Steam ID folder, so those saves are never uploaded.
 *
 * On every launch this moves the files of any other folder into the Steam ID folder
 * and leaves a symlink behind under the old name, so whichever name the game builds
 * next, the saves land in the folder cloud sync watches.
 */
class SaveFolderCollapseFix(
    private val documentsRelativePath: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val steamId64 = SteamUtils.getSteamId64()?.toString()
        if (steamId64.isNullOrEmpty() || steamId64 == "0") {
            Timber.tag(TAG).w("No Steam ID available, skipping save collapse for game $gameId")
            return false
        }

        val saveRoot = File(
            container.rootDir,
            ".wine/drive_c/users/${ImageFs.USER}/Documents/$documentsRelativePath",
        )
        if (!saveRoot.isDirectory) return false

        return runCatching {
            val canonical = File(saveRoot, steamId64)
            if (!canonical.isDirectory && !canonical.mkdirs()) {
                Timber.tag(TAG).w("Could not create '${canonical.absolutePath}' for game $gameId")
                return false
            }

            var changed = false
            saveRoot.listFiles().orEmpty().forEach { child ->
                if (child.name == steamId64) return@forEach
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                if (!child.isDirectory) return@forEach
                if (collapse(child, canonical, steamId64, gameId)) changed = true
            }
            changed
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed collapsing saves in '${saveRoot.absolutePath}' for game $gameId")
            false
        }
    }

    private fun collapse(stray: File, canonical: File, steamId64: String, gameId: String): Boolean {
        stray.walkTopDown().filter { it.isFile }.forEach { source ->
            val relativePath = source.relativeTo(stray).path
            val target = File(canonical, relativePath)
            if (target.isFile && target.lastModified() >= source.lastModified()) {
                Timber.tag(TAG).i("Kept newer '$relativePath' for game $gameId")
                return@forEach
            }
            target.parentFile?.mkdirs()
            val modified = source.lastModified()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target.setLastModified(modified)
        }

        if (!stray.deleteRecursively()) {
            Timber.tag(TAG).w("Could not remove '${stray.absolutePath}' for game $gameId")
            return false
        }

        Files.createSymbolicLink(stray.toPath(), Paths.get(steamId64))
        Timber.tag(TAG).i("Linked '${stray.name}' to '$steamId64' for game $gameId")
        return true
    }

    private companion object {
        const val TAG = "GameFixes"
    }
}

class KeyedSaveFolderCollapseFix(
    override val gameSource: GameSource,
    override val gameId: String,
    documentsRelativePath: String,
) : KeyedGameFix, GameFix by SaveFolderCollapseFix(documentsRelativePath)
