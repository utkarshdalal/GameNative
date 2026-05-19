package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Fallout 3 (GOG)
 */
val GOG_Fix_1454315831: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.GOG,
    gameId = "1454315831",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
