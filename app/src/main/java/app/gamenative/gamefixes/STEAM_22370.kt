package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Fallout 3 GOTY (Steam)
 */
val STEAM_Fix_22370: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.STEAM,
    gameId = "22370",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
