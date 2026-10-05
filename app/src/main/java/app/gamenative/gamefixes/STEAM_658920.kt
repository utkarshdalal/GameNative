package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Half-Life 2: VR Mod (Steam)
 */
val STEAM_Fix_658920: KeyedGameFix = KeyedVulkanExtensionBlacklistFix(
    gameSource = GameSource.STEAM,
    gameId = "658920",
    extensions = listOf("VK_EXT_fragment_density_map"),
)
