package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import timber.log.Timber

/**
 * Sets per-executable Wine settings (HKCU\Software\Wine\AppDefaults\<exe>\<subkey>) in the container's
 * prefix, e.g. a Direct3D renderer for one exe only. Per-exe values take precedence over the global
 * ones, so the rest of the container is unaffected.
 */
class WineAppDefaultsFix(
    private val exeName: String,
    private val subkey: String,
    private val values: Map<String, String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = try {
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        if (!userRegFile.isFile) {
            userRegFile.parentFile?.mkdirs()
            userRegFile.writeText("WINE REGISTRY Version 2\n\n")
        }
        val key = "Software\\Wine\\AppDefaults\\$exeName\\$subkey"
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            for ((name, value) in values) {
                if (editor.getStringValue(key, name, null) != value) {
                    editor.setStringValue(key, name, value)
                    Timber.tag("GameFixes").i("Set $key $name=$value for game $gameId")
                }
            }
        }
        true
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "Failed to set Wine AppDefaults for $exeName (game $gameId)")
        false
    }
}
