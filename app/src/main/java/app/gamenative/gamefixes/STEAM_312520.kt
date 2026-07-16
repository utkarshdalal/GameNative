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
        val requiredDllOverrides = ensureWinHttpOverride(dllOverrides)
        if (requiredDllOverrides != dllOverrides) {
            envVars.put("WINEDLLOVERRIDES", requiredDllOverrides)
            container.envVars = envVars.toString()
            changed = true
        }

        val userRegFile = File(container.rootDir, ".wine/user.reg")
        if (!userRegFile.isFile) {
            userRegFile.parentFile?.mkdirs()
            userRegFile.writeText("WINE REGISTRY Version 2\n\n")
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

private fun ensureWinHttpOverride(value: String): String {
    val parts = value.trim().split(';', ' ').filter { it.isNotBlank() }
    val winHttpParts = parts.filter { overridePartContainsWinHttp(it) }
    if (winHttpParts.size == 1 && winHttpParts.single().equals(RAIN_WORLD_WINHTTP_OVERRIDE, ignoreCase = true)) {
        return value
    }

    return (parts.mapNotNull { part ->
        val dllNames = part.substringBefore('=').split(',')
        val keptDllNames = dllNames.filterNot { it.equals("winhttp", ignoreCase = true) }
        when {
            keptDllNames.size == dllNames.size -> part
            keptDllNames.isEmpty() -> null
            part.contains('=') -> keptDllNames.joinToString(",") + part.substring(part.indexOf('='))
            else -> keptDllNames.joinToString(",")
        }
    } + RAIN_WORLD_WINHTTP_OVERRIDE).joinToString(";")
}

private fun overridePartContainsWinHttp(part: String): Boolean =
    part.substringBefore('=').split(',').any { it.equals("winhttp", ignoreCase = true) }
