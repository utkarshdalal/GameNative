package app.gamenative.gamefixes

import app.gamenative.data.GameSource

val GOG_Fix_1458058109: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.GOG,
    gameId = "1458058109",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
