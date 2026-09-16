package app.gamenative.service.rockstar

import java.io.File

/** The Social Club runtime the game's own library needs; every Rockstar title ships its installer. */
object RockstarRuntime {
    const val SOCIAL_CLUB_DIR = "Program Files/Rockstar Games/Social Club"
    private val installers = listOf("Redistributables/Social-Club-Setup.exe", "Installers/Social-Club-Setup.exe")

    fun socialClubDir(prefixDriveC: File) = File(prefixDriveC, SOCIAL_CLUB_DIR)

    fun isInstalled(prefixDriveC: File) = File(socialClubDir(prefixDriveC), "socialclub.dll").isFile

    /** Relative to the game directory, Windows form, or null when the game does not ship it. */
    fun installer(gameDir: File): String? = installers.firstOrNull { File(gameDir, it).isFile }?.replace('/', '\\')
}
