package app.gamenative.service.rockstar

import java.io.File

/** Android only stages our bundle. The stub handles game metadata, DLL setup and compatibility. */
object RockstarHelperDeployment {
    const val DIRECTORY = ".gamenative-rockstar"
    private const val LAUNCHER = "Launcher.exe"
    private val files = listOf(
        "scpatch.dll", "bink2w64.dll", "rgscstub32.exe", "scpatch32.dll", "binkw32.dll",
        "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll",
    )

    fun executable(installDir: File): String {
        val titleDir = RockstarHelperArchive.titleDir(installDir) ?: installDir
        val sub = titleDir.relativeTo(installDir).invariantSeparatorsPath
        return (if (sub.isEmpty()) "" else "$sub/") + "$DIRECTORY/$LAUNCHER"
    }

    /** Copies the cached helper into the game directory on every launch, so the game runs exactly what the cache holds. */
    @Synchronized
    fun prepare(filesDir: File, installDir: File) {
        check(RockstarHelperArchive.isReady(filesDir)) { "Rockstar helper archive is not ready" }
        val titleDir = RockstarHelperArchive.titleDir(installDir) ?: installDir
        val target = File(titleDir, DIRECTORY)
        check(target.isDirectory || target.mkdir()) { "Cannot create Rockstar helper directory" }
        val cache = RockstarHelperArchive.directory(filesDir)
        for (name in files + LAUNCHER) {
            val source = File(cache, if (name == LAUNCHER) "rgscstub.exe" else name)
            source.copyTo(File(target, name), overwrite = true)
        }
    }

    /** The stub publishes this after validating title.rgl, for the game's window-exit watcher. */
    fun gameExecutable(installDir: File): String? {
        val titleDir = RockstarHelperArchive.titleDir(installDir) ?: installDir
        val file = File(titleDir, "$DIRECTORY/game-executable.txt")
        if (!file.isFile || file.length() !in 1..240) return null
        return file.readText().takeIf { value ->
            !value.startsWith('/') && !value.startsWith('\\') && ".." !in value &&
                value.none { it in ":\"\r\n\u0000" } && value.endsWith(".exe", true)
        }
    }
}
