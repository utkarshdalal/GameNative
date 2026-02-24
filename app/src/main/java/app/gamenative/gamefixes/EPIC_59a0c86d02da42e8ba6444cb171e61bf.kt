package app.gamenative.gamefixes

import app.gamenative.data.GameSource

val EPIC_Fix_59a0c86d02da42e8ba6444cb171e61bf: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.EPIC,
    gameId = "59a0c86d02da42e8ba6444cb171e61bf",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
