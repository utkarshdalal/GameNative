package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.rockstar.RockstarHelperArchive
import app.gamenative.service.rockstar.RockstarRuntime
import com.winlator.container.Container
import java.io.File

/** Rockstar titles need the Social Club runtime in the prefix; the game ships Rockstar's installer for it. */
object SocialClubStep : PreInstallStep {
    override val marker: Marker = Marker.SOCIAL_CLUB_INSTALLED

    override fun appliesTo(container: Container, gameSource: GameSource, gameDirPath: String): Boolean {
        val gameDir = File(gameDirPath)
        return !MarkerUtils.hasMarker(gameDirPath, marker) &&
            RockstarHelperArchive.usesRockstar(gameDir) &&
            RockstarRuntime.installer(gameDir) != null &&
            !RockstarRuntime.isInstalled(File(container.rootDir, ".wine/drive_c"))
    }

    override fun buildCommand(container: Container, appId: String, gameSource: GameSource, gameDir: File, gameDirPath: String): String? {
        val installer = RockstarRuntime.installer(gameDir) ?: return null
        return "\"A:\\$installer\" /silent"
    }
}
