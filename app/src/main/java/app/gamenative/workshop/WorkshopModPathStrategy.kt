package app.gamenative.workshop

import java.io.File

/**
 * Describes how downloaded Workshop items must be presented to a game.
 *
 * Some games (Garry's Mod, Arma 3, Cities: Skylines) consume Workshop content
 * from more than one directory. However, blindly placing every item into every
 * detected directory is not universally safe. The detector therefore only
 * produces a multi-dir strategy when it has strong corroborating evidence.
 */
sealed class WorkshopModPathStrategy {

    /**
     * Controls which directories in [SymlinkIntoDir.targetDirs] /
     * [CopyIntoDir.targetDirs] are actually written to.
     */
    enum class FanOutPolicy {
        /** Only link/copy into targetDirs[0] — the highest-confidence dir. */
        PRIMARY_ONLY,
        /** Link/copy into every directory in targetDirs. */
        ALL_DIRS,
    }

    /**
     * Game reads mods via ISteamUGC::GetItemInstallInfo, which gbe_fork
     * routes to the standard Workshop content path. No filesystem manipulation
     * required beyond the gbe_fork steam_settings/mods/ symlinks.
     */
    data object Standard : WorkshopModPathStrategy()

    /**
     * Game scans one or more of its own directories for mod subdirectories.
     * Each Workshop item is symlinked into the directories selected by [fanOut].
     */
    data class SymlinkIntoDir(
        val targetDirs: List<File>,
        val fanOut: FanOutPolicy = FanOutPolicy.PRIMARY_ONLY,
    ) : WorkshopModPathStrategy() {
        constructor(targetDir: File) : this(listOf(targetDir), FanOutPolicy.PRIMARY_ONLY)

        val effectiveDirs: List<File> get() = when (fanOut) {
            FanOutPolicy.PRIMARY_ONLY -> listOf(targetDirs.first())
            FanOutPolicy.ALL_DIRS -> targetDirs
        }
    }

    /**
     * Like [SymlinkIntoDir] but copies files instead of symlinking.
     * Used only when the game or engine cannot follow symlinks.
     */
    data class CopyIntoDir(
        val targetDirs: List<File>,
        val fanOut: FanOutPolicy = FanOutPolicy.PRIMARY_ONLY,
    ) : WorkshopModPathStrategy() {
        constructor(targetDir: File) : this(listOf(targetDir), FanOutPolicy.PRIMARY_ONLY)

        val effectiveDirs: List<File> get() = when (fanOut) {
            FanOutPolicy.PRIMARY_ONLY -> listOf(targetDirs.first())
            FanOutPolicy.ALL_DIRS -> targetDirs
        }
    }
}
