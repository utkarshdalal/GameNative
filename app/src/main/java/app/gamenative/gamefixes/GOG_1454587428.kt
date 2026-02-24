package app.gamenative.gamefixes

import app.gamenative.data.GameSource

val GOG_Fix_1454587428: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.GOG,
    gameId = "1454587428",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
