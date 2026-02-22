package app.gamenative.gamefixes

val STEAM_Fix_22300: GameFix = RegistryKeyFix(
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
