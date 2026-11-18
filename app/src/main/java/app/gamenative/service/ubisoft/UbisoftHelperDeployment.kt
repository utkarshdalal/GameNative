package app.gamenative.service.ubisoft

import java.io.File

/** Bionic Steam starts the stub from the game folder; the stub starts the game, serves it and reports how it ended. */
object UbisoftHelperDeployment {
    const val DIRECTORY = ".gamenative-ubisoft"
    const val EXECUTABLE = "$DIRECTORY/${UbisoftHelperArchive.STUB}"
    private const val GAME_EXECUTABLE_FILE = "game-executable.txt"

    /** Copies the cached stub into the game directory on every launch, so the game runs exactly what the cache holds. */
    @Synchronized
    fun prepare(filesDir: File, installDir: File) {
        check(UbisoftHelperArchive.isReady(filesDir)) { "Ubisoft helper archive is not ready" }
        val target = File(installDir, DIRECTORY)
        check(target.isDirectory || target.mkdir()) { "Cannot create Ubisoft helper directory" }
        UbisoftHelperArchive.stub(filesDir).copyTo(File(target, UbisoftHelperArchive.STUB), overwrite = true)
        UbisoftHelperArchive.configFile(filesDir).copyTo(File(target, UbisoftHelperArchive.CONFIG), overwrite = true)
        File(target, GAME_EXECUTABLE_FILE).delete()
    }

    fun isDeployed(installDir: File): Boolean = File(installDir, EXECUTABLE).isFile

    /** `[--workdir=<dir>] <launch exe> [launch arguments]`, with absolute Windows paths. */
    fun stubArguments(originalExeWindows: String, arguments: String, workingDirWindows: String? = null): String {
        val workdir = workingDirWindows?.trimEnd('\\')?.takeIf { it.isNotEmpty() }?.let { "\"--workdir=$it\" " }.orEmpty()
        return workdir + "\"$originalExeWindows\"" + if (arguments.isNotBlank()) " ${arguments.trim()}" else ""
    }

    /** The stub publishes the real game's file name here, for the game's window-exit watcher. */
    fun gameExecutable(installDir: File): String? {
        val file = File(installDir, "$DIRECTORY/$GAME_EXECUTABLE_FILE")
        if (!file.isFile || file.length() !in 1..240) return null
        return file.readText().trim().takeIf { value ->
            value.isNotEmpty() && !value.startsWith('/') && !value.startsWith('\\') && ".." !in value &&
                value.none { it in ":\"\r\n\u0000" } && value.endsWith(".exe", true)
        }
    }
}
