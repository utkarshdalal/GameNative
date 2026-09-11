package app.gamenative.service.rockstar

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android only stages our bundle. The stub handles game metadata, DLL setup and compatibility. */
object RockstarHelperDeployment {
    const val DIRECTORY = ".gamenative-rockstar"
    const val EXECUTABLE = "$DIRECTORY/Launcher.exe"
    private val files = listOf("scpatch.dll", "bink2w64.dll", "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll")

    @Synchronized
    fun prepare(filesDir: File, gameDir: File) {
        check(RockstarHelperArchive.isReady(filesDir)) { "Rockstar helper archive is not ready" }
        val target = File(gameDir, DIRECTORY)
        check(target.canonicalFile.parentFile == gameDir.canonicalFile) { "Rockstar helper directory escapes the game installation" }
        check(target.isDirectory || target.mkdir()) { "Cannot create Rockstar helper directory" }
        val cache = RockstarHelperArchive.directory(filesDir)
        for (name in files + "Launcher.exe") {
            val source = File(cache, if (name == "Launcher.exe") "rgscstub.exe" else name)
            val destination = File(target, name)
            check(destination.canonicalFile.parentFile == target.canonicalFile) { "Rockstar helper file escapes its directory" }
            val bytes = source.readBytes()
            if (destination.isFile && destination.length() == bytes.size.toLong() && destination.readBytes().contentEquals(bytes)) continue
            val stage = File.createTempFile(".helper-", ".new", target)
            try {
                stage.outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { stage.delete() }
        }
    }

    /** The stub publishes this after validating title.rgl, for the game's window-exit watcher. */
    fun gameExecutable(gameDir: File): String? {
        val file = File(gameDir, "$DIRECTORY/game-executable.txt")
        if (!file.isFile || file.length() !in 1..240) return null
        return file.readText().takeIf { value ->
            !value.startsWith('/') && !value.startsWith('\\') && ".." !in value &&
                value.none { it in ":\"\r\n\u0000" } && value.endsWith(".exe", true)
        }
    }
}
