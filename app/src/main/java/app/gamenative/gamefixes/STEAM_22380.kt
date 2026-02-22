package app.gamenative.gamefixes

val STEAM_Fix_22380: GameFix = RegistryKeyFix(
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\FalloutNV",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
