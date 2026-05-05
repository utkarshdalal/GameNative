package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Fallout: New Vegas PCR (Steam)
 */
val STEAM_Fix_22490: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.STEAM,
    gameId = "22490",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
