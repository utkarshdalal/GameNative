package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import timber.log.Timber

private const val FORZA_WEB_HELPER_DLL_OVERRIDES_KEY =
    "Software\\Wine\\AppDefaults\\ForzaWebHelper.exe\\DllOverrides"
private const val FORZA_WEB_HELPER_DIRECT3D_KEY =
    "Software\\Wine\\AppDefaults\\ForzaWebHelper.exe\\Direct3D"
private val FORZA_WEB_HELPER_BUILTIN_DLLS = listOf("dxgi", "d3d11", "d3d9")

/**
 * Forza Horizon 4 (Steam)
 *
 * The CEF overlay (ForzaWebHelper.exe) does not render with GPU acceleration.
 * libcef.dll statically imports d3d9/d3d11, so they cannot be disabled outright;
 * instead route them to wine's builtin wined3d with renderer=no3d for that exe
 * only. D3D device creation then fails at runtime, ANGLE gives up, and CEF
 * falls back to software rendering while the game keeps DXVK.
 */
val STEAM_Fix_1293830: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.STEAM
    override val gameId = "1293830"

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
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            for (dll in FORZA_WEB_HELPER_BUILTIN_DLLS) {
                if (editor.getStringValue(FORZA_WEB_HELPER_DLL_OVERRIDES_KEY, dll, null) != "builtin") {
                    editor.setStringValue(FORZA_WEB_HELPER_DLL_OVERRIDES_KEY, dll, "builtin")
                }
            }
            if (editor.getStringValue(FORZA_WEB_HELPER_DIRECT3D_KEY, "renderer", null) != "no3d") {
                editor.setStringValue(FORZA_WEB_HELPER_DIRECT3D_KEY, "renderer", "no3d")
            }
        }
        true
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "Failed to apply Forza Horizon 4 web helper DLL overrides")
        false
    }
}
