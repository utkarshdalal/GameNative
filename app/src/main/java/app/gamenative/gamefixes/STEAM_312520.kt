package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

private const val RAIN_WORLD_DLL_OVERRIDES_KEY =
    "Software\\Wine\\AppDefaults\\RainWorld.exe\\DllOverrides"
private const val RAIN_WORLD_WINHTTP_OVERRIDE = "winhttp=native,builtin"

val STEAM_Fix_312520: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.STEAM
    override val gameId = "312520"

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = try {
        var changed = false
        val envVars = EnvVars(container.envVars)
        val dllOverrides = envVars.get("WINEDLLOVERRIDES")
        if (!hasWinHttpOverride(dllOverrides)) {
            envVars.put("WINEDLLOVERRIDES", appendWinHttpOverride(dllOverrides))
            container.envVars = envVars.toString()
            changed = true
        }

        val userRegFile = File(container.rootDir, ".wine/user.reg")
        if (!userRegFile.isFile) {
            userRegFile.parentFile?.mkdirs()
            userRegFile.writeText("REGEDIT4\n")
        }
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            val existing = editor.getStringValue(RAIN_WORLD_DLL_OVERRIDES_KEY, "winhttp", "")
            if (existing != "native,builtin") {
                editor.setStringValue(RAIN_WORLD_DLL_OVERRIDES_KEY, "winhttp", "native,builtin")
                changed = true
            }
        }

        if (changed) container.saveData()
        true
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "Failed to apply Rain World Doorstop DLL override")
        false
    }
}

private fun appendWinHttpOverride(value: String): String =
    value.trim().takeIf { it.isNotEmpty() }
        ?.let { "$it;$RAIN_WORLD_WINHTTP_OVERRIDE" }
        ?: RAIN_WORLD_WINHTTP_OVERRIDE

private fun hasWinHttpOverride(value: String): Boolean =
    value.split(';', ' ')
        .map { it.substringBefore('=').split(',') }
        .flatten()
        .any { it.equals("winhttp", ignoreCase = true) }
