package app.gamenative.gamefixes

import app.gamenative.data.GameSource

val STEAM_Fix_22300: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.STEAM,
    gameId = "22300",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
