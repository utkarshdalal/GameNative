package app.gamenative.gamefixes

import app.gamenative.data.GameSource

val EPIC_Fix_dabb52e328834da7bbe99691e374cb84: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.EPIC,
    gameId = "dabb52e328834da7bbe99691e374cb84",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
