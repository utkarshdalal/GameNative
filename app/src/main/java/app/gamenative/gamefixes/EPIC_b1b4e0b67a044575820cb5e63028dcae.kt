package app.gamenative.gamefixes

import app.gamenative.data.GameSource

val EPIC_Fix_b1b4e0b67a044575820cb5e63028dcae: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.EPIC,
    gameId = "b1b4e0b67a044575820cb5e63028dcae",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
