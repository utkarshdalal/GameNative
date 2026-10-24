package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Escape from Monkey Island (GOG)
 */
val GOG_Fix_1885026907: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.GOG,
    gameId = "1885026907",
    registryKey = "Software\\Wow6432Node\\LucasArts Entertainment Company LLC\\Monkey4\\Retail",
    defaultValues = mapOf(
        "Install Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
