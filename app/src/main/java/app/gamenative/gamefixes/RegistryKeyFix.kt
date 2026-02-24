package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File

const val INSTALL_PATH_PLACEHOLDER = "<InstallPath>"

class RegistryKeyFix(
    private val registryKey: String,
    private val defaultValues: Map<String, String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
    ): Boolean {
        val imageFs = ImageFs.find(context)
        val systemRegFile = File(imageFs.wineprefix, "system.reg")
        if (!systemRegFile.exists()) {
            Timber.tag("GameFixes").w("system.reg not found at ${systemRegFile.absolutePath}")
            return false
        }
        val installPathWin = installPathWindows.replace("/", "\\")
        val values = defaultValues.mapValues { (_, v) ->
            if (v == INSTALL_PATH_PLACEHOLDER) installPathWin else v
        }
        return try {
            WineRegistryEditor(systemRegFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for ((name, value) in values) {
                    val existing = editor.getStringValue(registryKey, name, null)
                    if (existing == null || existing.isEmpty()) {
                        editor.setStringValue(registryKey, name, value)
                        Timber.tag("GameFixes").d("Set registry $registryKey $name for game $gameId")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to apply registry fix for game $gameId")
            false
        }
    }
}

class KeyedRegistryKeyFix(
    override val gameSource: GameSource,
    override val gameId: String,
    registryKey: String,
    defaultValues: Map<String, String>,
) : KeyedGameFix, GameFix by RegistryKeyFix(registryKey, defaultValues)
