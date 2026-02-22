package app.gamenative.gamefixes

val STEAM_Fix_22330: GameFix = RegistryKeyFix(
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
