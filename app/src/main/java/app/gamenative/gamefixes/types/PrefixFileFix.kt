package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import java.nio.charset.StandardCharsets
import timber.log.Timber

/**
 * Writes a fixed file (creating parent directories) at a path relative to the
 * container's `drive_c/` on every launch.
 *
 * Used for games that need a config dropped into the prefix (e.g. an Unreal
 * Engine `Engine.ini` under AppData) before they will run acceptably. The file
 * is only rewritten when its contents differ from [content].
 */
class PrefixFileFix(
    private val driveCRelativePath: String,
    private val content: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val target = File(container.rootDir, ".wine/drive_c/$driveCRelativePath")
        return runCatching {
            val existing = if (target.isFile) target.readText(StandardCharsets.UTF_8) else null
            if (existing == content) {
                return false
            }
            target.parentFile?.mkdirs()
            target.writeText(content, StandardCharsets.UTF_8)
            Timber.tag("GameFixes").i("Wrote '${target.absolutePath}' for game $gameId")
            true
        }.getOrElse { error ->
            Timber.tag("GameFixes").w(error, "Failed to write '${target.absolutePath}' for game $gameId")
            false
        }
    }
}

class KeyedPrefixFileFix(
    override val gameSource: GameSource,
    override val gameId: String,
    driveCRelativePath: String,
    content: String,
) : KeyedGameFix, GameFix by PrefixFileFix(driveCRelativePath, content)
