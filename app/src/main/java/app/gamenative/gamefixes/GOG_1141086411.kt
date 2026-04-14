package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Silent Hill 4 (GOG)
 */
val GOG_Fix_1141086411: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.GOG,
    gameId = "1141086411",
    registryKey = "Software\\Wow6432Node\\KONAMI\\SILENT HILL 4\\1.00.000",
    defaultValues = mapOf(
        "Install Language" to "English",
        "Install Path" to INSTALL_PATH_PLACEHOLDER,
        "Movie Install" to INSTALL_PATH_PLACEHOLDER,
        "Uninstall Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
