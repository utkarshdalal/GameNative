package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import timber.log.Timber

const val WOW_FOREVER_DXVK_VERSION = "2.4.1-wowfix-1"

/**
 * WoW Forever (custom game, WowB-ARM64.exe)
 *
 * The client is a native Windows ARM64 executable, so it cannot load the x64 or
 * ARM64EC DXVK builds that every other DXVK version ships. Point the container at
 * the ARM64-only DXVK 2.4.1 wowfix package from the manifest; the pre-launch step
 * downloads it when it is not installed yet.
 */
val CUSTOM_GAME_Fix_WowB_ARM64: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.CUSTOM_GAME
    override val gameId = "wowb-arm64.exe"

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = try {
        if (container.containerVariant != Container.BIONIC) {
            Timber.tag("GameFixes").i("WoW Forever DXVK fix skipped: container is not bionic")
            false
        } else {
            var changed = false
            if (container.dxWrapper != "dxvk") {
                container.dxWrapper = "dxvk"
                changed = true
            }
            val dxConfig = KeyValueSet(container.dxWrapperConfig)
            if (dxConfig.get("version") != WOW_FOREVER_DXVK_VERSION) {
                dxConfig.put("version", WOW_FOREVER_DXVK_VERSION)
                container.dxWrapperConfig = dxConfig.toString()
                changed = true
            }
            if (changed) {
                container.saveData()
                Timber.tag("GameFixes").i("Applied WoW Forever DXVK fix: dxvk $WOW_FOREVER_DXVK_VERSION")
            }
            true
        }
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "Failed to apply WoW Forever DXVK fix")
        false
    }
}
