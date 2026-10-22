package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import timber.log.Timber

class VulkanExtensionBlacklistFix(
    private val extensions: List<String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val config = KeyValueSet(container.graphicsDriverConfig)
            val current = config.get("blacklistedExtensions")
            val entries = current.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
            val missing = extensions.filter { it !in entries }
            if (missing.isEmpty()) {
                return true
            }

            val updated = (listOf(current.trim()).filter { it.isNotEmpty() } + missing).joinToString("|")
            config.put("blacklistedExtensions", updated)
            container.graphicsDriverConfig = config.toString()
            container.saveData()
            Timber.tag("GameFixes").i("Blacklisted Vulkan extensions '$missing' for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to blacklist Vulkan extensions '$extensions' for game $gameId")
            false
        }
    }
}

class KeyedVulkanExtensionBlacklistFix(
    override val gameSource: GameSource,
    override val gameId: String,
    extensions: List<String>,
) : KeyedGameFix, GameFix by VulkanExtensionBlacklistFix(extensions)
